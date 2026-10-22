/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Changed: the package name; the SAF-backed SafStore is left out and game files are
 * read from disk (AlabasterDawnBox keeps the game and its saves in its own files folder); FileStore can
 * carry a label.
 */
package io.github.aerithmetiq.alabasterdawnbox.host

import android.content.Context
import android.util.Log
import java.io.File

class StatInfo(val mtimeMillis: Long, val size: Long, val isDir: Boolean)

class DirEntry(val name: String, val isDir: Boolean)

/**
 * The save root, in one of two shapes. Both preserve the Steam layout exactly
 * (`Saves/Default/Save_ID_0000.save`, `Saves/Default/System.save`, `Saves/Backups`, `Saves/Backups2`),
 * so a desktop `Saves/` folder can be copied in, and the files can be copied back out.
 *
 * Paths are relative to the save root and never contain `..` (rejected by [FsBridge]).
 */
interface SaveStore {
    /** Human readable location for the pre-game screen; null when the store is app-private. */
    val label: String?

    fun exists(rel: String): Boolean
    fun mkdir(rel: String): Boolean
    fun list(rel: String): List<DirEntry>?
    fun stat(rel: String): StatInfo?
    fun read(rel: String): String?
    fun write(rel: String, data: String): Boolean

    /** True only when a document was actually deleted (`StorageTools.deleteFile` counts successes). */
    fun rm(rel: String): Boolean
    fun rename(from: String, to: String): Boolean
}

/**
 * Carrying saves from one store into another.
 *
 * The two stores are alternatives, not a chain (see `PortActivity.openSaveStore`): saves made while
 * app storage was in use — no folder set, or the set one missing — would be left behind, unreachable,
 * the moment a folder is picked. App storage is only ever the port's own fallback, never a folder the
 * user chose, so carrying **out of it** is always safe. Two user folders are deliberately never
 * merged: that would overwrite a newer save with an older one.
 */
object SaveCarry {

    /**
     * Copies everything under [from] that [to] does not already have, or has an older copy of.
     * Nothing is deleted, and the paths actually written are returned (in visit order).
     */
    fun copyNewer(from: SaveStore, to: SaveStore): List<String> {
        val copied = ArrayList<String>()
        copyInto(from, to, "", copied)
        return copied
    }

    private fun copyInto(from: SaveStore, to: SaveStore, dir: String, copied: MutableList<String>) {
        for (entry in from.list(dir).orEmpty()) {
            val rel = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            if (entry.isDir) {
                to.mkdir(rel)
                copyInto(from, to, rel, copied)
                continue
            }
            val here = from.stat(rel) ?: continue
            val there = to.stat(rel)
            if (there != null && here.mtimeMillis <= there.mtimeMillis) continue
            val text = from.read(rel) ?: continue
            if (to.write(rel, text)) copied.add(rel)
        }
    }
}

/**
 * A picked saves folder whose game files were copied *flat* — the desktop folder's contents dropped
 * straight in, not the folder itself (see [GameProfile.saveSubdir]/[GameProfile.saveFlatMarker]).
 * The game still asks for `Saves/…` (Alabaster Dawn) or `Default/…` (CrossCode); every relative path
 * that leads with that one directory is served [prefix]-less instead, so reads, writes and the
 * game's own `Default` → `Backups` rotation all land at the picked root.
 *
 * Only the leading segment is dropped, and only when it exactly is the prefix: `Save_ID.save` at the
 * root, the port's own `pad-layout.json`/log, and any deeper file are untouched. Detection happens
 * once, at store open ([SaveLayout.detect]), so a folder is never half one shape and half the other.
 */
class SaveLayoutStore(private val inner: SaveStore, private val prefix: String) : SaveStore {

    override val label: String? get() = inner.label

    /** The path as the game names it, mapped onto the flattened folder. */
    private fun flatten(rel: String): String =
        if (rel == prefix) "" else if (rel.startsWith("$prefix/")) rel.substring(prefix.length + 1) else rel

    override fun exists(rel: String): Boolean = inner.exists(flatten(rel))
    override fun mkdir(rel: String): Boolean = inner.mkdir(flatten(rel))
    override fun list(rel: String): List<DirEntry>? = inner.list(flatten(rel))
    override fun stat(rel: String): StatInfo? = inner.stat(flatten(rel))
    override fun read(rel: String): String? = inner.read(flatten(rel))
    override fun write(rel: String, data: String): Boolean = inner.write(flatten(rel), data)
    override fun rm(rel: String): Boolean = inner.rm(flatten(rel))
    override fun rename(from: String, to: String): Boolean =
        inner.rename(flatten(from), flatten(to))
}

/**
 * Picks the saves layout for a store: the game's own [GameProfile.saveSubdir] when it is there, or
 * [GameProfile.saveFlatMarker] with the subfolder absent, which means the desktop saves were copied
 * flat. Anything else (an empty or new folder) keeps the standard shape.
 */
object SaveLayout {
    fun detect(store: SaveStore, profile: GameProfile): SaveStore {
        if (store.stat(profile.saveSubdir)?.isDir == true) return store
        if (!store.exists(profile.saveFlatMarker)) return store
        return SaveLayoutStore(store, profile.saveSubdir)
    }
}

/** Saves in app-private storage, used only when the user has not granted a saves folder. */
class FileStore(private val base: File, override val label: String? = null) : SaveStore {

    private fun file(rel: String): File = File(base, rel)

    override fun exists(rel: String): Boolean = rel.isEmpty() || file(rel).exists()

    override fun mkdir(rel: String): Boolean = rel.isEmpty() || file(rel).mkdirs() || file(rel).isDirectory

    override fun list(rel: String): List<DirEntry>? {
        val dir = file(rel)
        val names = dir.list() ?: return null
        return names.map { DirEntry(it, File(dir, it).isDirectory) }
    }

    override fun stat(rel: String): StatInfo? {
        val f = file(rel)
        if (!f.exists()) return null
        return StatInfo(f.lastModified(), f.length(), f.isDirectory)
    }

    override fun read(rel: String): String? = try {
        file(rel).readText(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    override fun write(rel: String, data: String): Boolean = try {
        val f = file(rel)
        f.parentFile?.mkdirs()
        f.writeText(data, Charsets.UTF_8)
        true
    } catch (e: Exception) {
        Log.e("RfPort", "write $rel failed", e)
        false
    }

    override fun rm(rel: String): Boolean {
        val f = file(rel)
        return f.exists() && f.deleteRecursively()
    }

    override fun rename(from: String, to: String): Boolean {
        if (!file(from).exists()) return false
        val target = file(to)
        target.parentFile?.mkdirs()
        return file(from).renameTo(target)
    }

    companion object {
        fun appPrivate(context: Context): FileStore = FileStore(File(context.filesDir, "saves"))
    }
}

/**
 * Backs the shim's `fs` object. Two namespaces:
 *
 * | path | backend | access |
 * |---|---|---|
 * | `/saves/...` | the picked saves tree | read-write |
 * | anything else (`media/gui/x.png`) | the picked game tree, from [GameIndex] | read-only |
 *
 * `nw.App.dataPath` is the literal `/saves`; the game concatenates `/Saves/...` onto it, so the
 * whole Steam layout lands inside the picked folder. Nothing here ever throws across the JNI
 * boundary: failures come back as `false` / `null`.
 */
class FsBridge(
    private val gameIndex: GameIndex,
    private val store: SaveStore,
) {

    fun exists(path: String): Boolean {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return false
            return store.exists(rel)
        }
        return gameIndex.findGamePath(path) != null
    }

    fun mkdir(path: String): Boolean {
        if (!isSavePath(path)) {
            Log.w(TAG, "ignored mkdir outside the saves folder: $path")
            return false
        }
        val rel = saveRel(path) ?: return false
        return store.mkdir(rel)
    }

    fun list(path: String): List<DirEntry>? {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return null
            return store.list(rel)
        }
        val dir = gameIndex.findGameDir(path) ?: return null
        return gameIndex.children(dir)
    }

    fun stat(path: String): StatInfo? {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return null
            return store.stat(rel)
        }
        val entry = gameIndex.findGamePath(path) ?: return null
        return StatInfo(0L, entry.size, entry.isDir)
    }

    fun read(path: String): String? {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return null
            return store.read(rel)
        }
        val entry = gameIndex.findGamePath(path) ?: return null
        if (entry.isDir) return null
        return try {
            File(entry.docId).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "cannot read game file $path", e)
            null
        }
    }

    fun write(path: String, data: String): Boolean {
        if (!isSavePath(path)) {
            Log.w(TAG, "ignored write outside the saves folder: $path")
            return false
        }
        val rel = saveRel(path) ?: return false
        return store.write(rel, data)
    }

    fun rm(path: String): Boolean {
        if (!isSavePath(path)) {
            Log.w(TAG, "ignored delete outside the saves folder: $path")
            return false
        }
        val rel = saveRel(path) ?: return false
        return store.rm(rel)
    }

    fun rename(from: String, to: String): Boolean {
        if (!isSavePath(from) || !isSavePath(to)) {
            Log.w(TAG, "ignored rename outside the saves folder: $from -> $to")
            return false
        }
        val src = saveRel(from) ?: return false
        val dst = saveRel(to) ?: return false
        return store.rename(src, dst)
    }

    fun copy(from: String, to: String): Boolean {
        if (!isSavePath(from) || !isSavePath(to)) {
            Log.w(TAG, "ignored copy outside the saves folder: $from -> $to")
            return false
        }
        val src = saveRel(from) ?: return false
        val dst = saveRel(to) ?: return false
        val data = store.read(src) ?: return false
        return store.write(dst, data)
    }

    val storeLabel: String? get() = store.label

    companion object {
        private const val TAG = "RfPort"
        const val SAVES_ROOT = "/saves"

        /** Whether [path] addresses the picked saves tree rather than the read-only game tree. */
        internal fun isSavePath(path: String): Boolean = path == SAVES_ROOT ||
            path.startsWith("$SAVES_ROOT/") || path.startsWith("$SAVES_ROOT\\")

        /** Path relative to the save root, or null when it escapes it. */
        internal fun saveRel(path: String): String? = normalize(path.removePrefix(SAVES_ROOT))

        /**
         * Splits, drops empty and `.` segments, rejects `..`. Both separators are separators here:
         * the demo-era engine builds its save paths with backslashes (`dataPath + "\Saves\Default\"`,
         * which reaches this bridge as `/saves\Saves\Default\`) and the released one with forward
         * slashes, and both mean the same Steam layout inside the picked folder. A path that is *not*
         * under [SAVES_ROOT] - the game probes `\Default\Saves\Default\...` in its own storage fix -
         * never gets here, because [isSavePath] rejects it first.
         */
        private fun normalize(path: String): String? {
            val segments = ArrayList<String>(4)
            for (segment in path.split('/', '\\')) {
                when (segment) {
                    "", "." -> continue
                    ".." -> return null
                    else -> segments.add(segment)
                }
            }
            return segments.joinToString("/")
        }
    }
}
