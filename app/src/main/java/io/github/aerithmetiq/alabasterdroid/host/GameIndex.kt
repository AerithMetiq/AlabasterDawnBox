/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. GameEntry and GameIndex are unchanged; the folder walk (GameTree) is new: Alabaster
 * Droid reads the game from its own files folder instead of a folder picked through SAF.
 */
package io.github.aerithmetiq.alabasterdroid.host

import java.io.File


/** One file or folder of the game tree, keyed by its path relative to the root. [docId] is its absolute path. */
class GameEntry(val docId: String, val size: Long, val isDir: Boolean)

/**
 * Immutable in-memory index of the picked game folder.
 *
 * Keys are paths relative to the picked root (e.g. `terra/media/gui/loading.png`), which is what
 * both the asset handler (URLs under `/game/`) and the fs bridge (paths relative to the page root)
 * need. Built once per launch by [GameFiles.indexTree]; never mutated afterwards, so the
 * background [GameAssetHandler] threads can read it without locking.
 *
 * [pageRoot] is the selected [GameProfile]'s page root: the game-space prefix a page-relative path
 * resolves under. The tree is indexed before the game is known (its entry page is what says which
 * game it is), so the raw index carries `""` and [bind] returns the index the host uses.
 */
class GameIndex(private val entries: Map<String, GameEntry>, val pageRoot: String) {

    val size: Int get() = entries.size

    /** The same tree under a profile's page root. Called once, with the detected profile. */
    fun bind(pageRoot: String): GameIndex = GameIndex(entries, pageRoot)

    fun find(path: String): GameEntry? = entries[path]

    /**
     * Game-space fs paths are page-relative: the engines' own roots are empty strings, so a resource
     * asks for `data/...` (or a sound for `media/audio/sfx/x.ogg`) while the picked root holds it
     * under [pageRoot]. Try both spellings; the index is a map lookup, so the extra probe is free.
     */
    fun findGamePath(path: String): GameEntry? {
        val p = path.trimStart('/')
        entries[p]?.let { return it }
        return entries["$pageRoot/$p"]
    }

    /** Relative index key of the directory [path] names, or null when it is not a directory. */
    fun findGameDir(path: String): String? {
        /* The engines build directory paths with a trailing separator (`assets/extension/`). */
        val p = path.trim('/')
        if (p.isEmpty() || p == pageRoot) return pageRoot
        if (entries[p]?.isDir == true) return p
        if (entries["$pageRoot/$p"]?.isDir == true) return "$pageRoot/$p"
        return null
    }

    /** Immediate children of an index-key directory. Only the map editor browses, so O(n) is fine. */
    fun children(dir: String): List<DirEntry> {
        val out = ArrayList<DirEntry>()
        val head = if (dir.isEmpty()) "" else "$dir/"
        for ((key, entry) in entries) {
            if (!key.startsWith(head)) continue
            val rest = key.substring(head.length)
            if (rest.isEmpty() || rest.indexOf('/') != -1) continue
            out.add(DirEntry(rest, entry.isDir))
        }
        return out
    }

    /** Every index key, sorted: used for diagnostics only. */
    fun paths(): List<String> = entries.keys.sorted()

    /** Every entry as built, for the cache (see [GameIndexCache]). */
    fun snapshot(): Map<String, GameEntry> = entries
}

/** Builds the [GameIndex] of the downloaded game: one walk of the install folder per launch. */
object GameTree {

    fun index(root: File): GameIndex {
        val entries = HashMap<String, GameEntry>(4096)
        val base = root.absolutePath.length + 1
        root.walkTopDown().forEach { f ->
            if (f == root) return@forEach
            val rel = f.absolutePath.substring(base).replace(File.separatorChar, '/')
            entries[rel] = if (f.isDirectory) GameEntry(f.absolutePath, 0L, true) else GameEntry(f.absolutePath, f.length(), false)
        }
        return GameIndex(entries, "")
    }
}
