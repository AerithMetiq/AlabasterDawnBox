package io.github.aerithmetiq.alabasterdawnbox.steam

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The local save backups: a folder per moment (`yyyyMMdd-HHmmss`), each holding the save files that
 * were about to be replaced or removed, under their names below `Saves/`. A sync makes one before it
 * changes this device's saves ([CloudSync]); [restore] puts one back. Files only, no Steam.
 *
 * A folder whose name ends in [FROM_STEAM] holds Steam Cloud's copies of the files an upload was about
 * to replace there ([CloudSync] downloads them first). Restoring one puts those versions on this
 * device; the next sync then counts them as changed here.
 */
object SaveBackups {

    class Backup(val name: String, val files: List<String>) {
        /** Steam Cloud's copies, kept before an upload replaced them (see [FROM_STEAM]). */
        val fromSteam: Boolean get() = name.endsWith(FROM_STEAM)

        /** "2026-10-12 19:18" (with "· from Steam Cloud" for [fromSteam]), or the folder's name when it is not one of ours. */
        val label: String
            get() = runCatching {
                LABEL.format(LocalDateTime.parse(name.removeSuffix(FROM_STEAM), STAMP)) + if (fromSteam) "  ·  from Steam Cloud" else ""
            }.getOrDefault(name)
    }

    /** A new backup folder's name for [now]. */
    fun stamp(now: LocalDateTime = LocalDateTime.now()): String = STAMP.format(now)

    /** A new folder's name for Steam Cloud's copies at [now]. */
    fun steamStamp(now: LocalDateTime = LocalDateTime.now()): String = stamp(now) + FROM_STEAM

    /** The backups, newest first; empty folders are left out. */
    fun list(backups: File): List<Backup> =
        backups.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }.mapNotNull { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
            if (files.isEmpty()) null else Backup(dir.name, files)
        }

    /**
     * Puts the files of backup [name] back into [saves]. Each save file it replaces is first copied
     * into a new backup (named [stamp]), so a restore can itself be undone. Files the backup does not
     * hold are left as they are. Returns how many files were put back.
     */
    fun restore(saves: File, backups: File, name: String, stamp: String = stamp()): Int {
        val from = File(backups, name)
        val chosen = list(backups).firstOrNull { it.name == name } ?: return 0
        val undo = File(backups, if (stamp == name) "$stamp-1" else stamp)
        for (rel in chosen.files) {
            val target = File(saves, rel)
            if (target.isFile) target.copyTo(File(undo, rel), overwrite = true)
            File(from, rel).copyTo(target, overwrite = true)
        }
        prune(backups)
        return chosen.files.size
    }

    /**
     * Keeps the newest [KEEP] backup folders of each kind (this device's, and Steam Cloud's copies), so
     * the copies an upload makes every session do not push out the device's own; names sort by time.
     */
    fun prune(backups: File) {
        val folders = backups.listFiles { f -> f.isDirectory }.orEmpty()
        for (kind in folders.groupBy { it.name.endsWith(FROM_STEAM) }.values) {
            kind.sortedByDescending { it.name }.drop(KEEP).forEach { it.deleteRecursively() }
        }
    }

    const val KEEP = 8
    const val FROM_STEAM = "-steam"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}
