package io.github.aerithmetiq.alabasterdawnbox.steam

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One save file on one side (this device, or Steam Cloud). [name] is relative to `Saves/`, with `/`. */
data class SaveFile(val name: String, val sha: String, val size: Long, val modifiedSec: Long)

/** What a sync does with one save file. */
enum class SaveAction {
    /** Both sides hold the same bytes. */
    SAME,
    /** Steam Cloud has a newer version, or a file this device does not have: fetch it. */
    DOWNLOAD,
    /** This device has a newer version, or a file Steam Cloud does not have: send it. Writes to Steam. */
    UPLOAD,
    /** Steam Cloud deleted a file this device has not touched since: move the local copy to the backup. */
    REMOVE_LOCAL,
    /** Deleted on this device, unchanged in Steam Cloud: left alone (this app never deletes from Steam). */
    DELETED_HERE,
    /** Both sides changed since the last sync, or there has never been one: the user decides. */
    CONFLICT,
}

data class SaveStep(val name: String, val action: SaveAction, val local: SaveFile?, val cloud: SaveFile?)

/**
 * The decisions of the Steam Cloud save sync, kept apart from Steam and Android so they can be
 * unit-tested: how Cloud names map to the device's save folder, what to do with each file, and how the
 * result is worded.
 *
 * The sync is three-way. Besides the two sides it keeps a **base**: per file, the SHA-1 both sides had
 * the last time they were made equal. A side whose file still matches the base has not changed; when
 * only one side changed, that side wins without asking, and when both did, it is a conflict.
 */
object CloudSaves {

    /**
     * Alabaster Dawn's Steam Cloud names start with this token, then the path under the game's
     * `Saves/` folder (`%WinAppDataLocal%Default/System.save`); see the `ufs` rules in CLAUDE.md.
     */
    const val ROOT = "%WinAppDataLocal%"

    /** The name under `Saves/` for a Cloud name, or null when it is not one of this game's saves. */
    fun relative(cloudName: String): String? {
        if (!cloudName.startsWith(ROOT)) return null
        val rel = cloudName.removePrefix(ROOT).replace('\\', '/')
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rel
    }

    fun cloudName(relative: String): String = ROOT + relative

    fun plan(local: List<SaveFile>, cloud: List<SaveFile>, base: Map<String, String>): List<SaveStep> {
        val l = local.associateBy { it.name }
        val c = cloud.associateBy { it.name }
        return (l.keys + c.keys).sorted().map { name ->
            val here = l[name]
            val there = c[name]
            val b = base[name]
            val action = when {
                here != null && there != null -> when {
                    here.sha == there.sha -> SaveAction.SAME
                    b == here.sha -> SaveAction.DOWNLOAD
                    b == there.sha -> SaveAction.UPLOAD
                    else -> SaveAction.CONFLICT
                }
                there != null -> if (b == there.sha) SaveAction.DELETED_HERE else SaveAction.DOWNLOAD
                else -> if (b == here!!.sha) SaveAction.REMOVE_LOCAL else SaveAction.UPLOAD
            }
            SaveStep(name, action, here, there)
        }
    }

    /**
     * Settles every conflict one way: the saves are a set (slots plus `System.save`), so the user
     * picks a side for all of them. Steam Cloud wins: its versions are downloaded. This device wins:
     * its versions are to be uploaded.
     */
    fun resolve(steps: List<SaveStep>, cloudWins: Boolean): List<SaveStep> = steps.map {
        if (it.action != SaveAction.CONFLICT) it
        else it.copy(action = if (cloudWins) SaveAction.DOWNLOAD else SaveAction.UPLOAD)
    }

    /**
     * The base after a sync. [done] are the steps that were carried out. An upload that was not carried
     * out (uploads are off, or it failed) keeps its old base, so it is still "changed here" next time,
     * unless it settled a conflict in this device's favour ([keptLocal]): then the Cloud version it was
     * weighed against becomes the base, and the question is not asked again until Steam Cloud changes.
     */
    fun newBase(
        base: Map<String, String>,
        steps: List<SaveStep>,
        done: Set<String>,
        keptLocal: Set<String> = emptySet(),
    ): Map<String, String> {
        val out = HashMap<String, String>()
        for (s in steps) {
            val old = base[s.name]
            val now = when (s.action) {
                SaveAction.SAME -> s.local!!.sha
                SaveAction.DOWNLOAD -> if (s.name in done) s.cloud!!.sha else old
                SaveAction.UPLOAD -> when {
                    s.name in done -> s.local!!.sha
                    s.name in keptLocal -> s.cloud?.sha ?: old
                    else -> old
                }
                SaveAction.REMOVE_LOCAL -> if (s.name in done) null else old
                SaveAction.DELETED_HERE, SaveAction.CONFLICT -> old
            }
            if (now != null) out[s.name] = now
        }
        return out
    }

    /* ------------------------------------------------------------ the base file ---- */

    /** One `sha name` line per file; names may hold spaces, a SHA-1 never does. */
    fun formatBase(base: Map<String, String>): String =
        base.toSortedMap().entries.joinToString("") { "${it.value} ${it.key}\n" }

    fun parseBase(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val cut = line.indexOf(' ')
            if (cut != 40 || cut == line.length - 1) null else line.substring(cut + 1) to line.substring(0, cut)
        }.toMap()

    /* ----------------------------------------------------------------- wording ---- */

    fun count(steps: List<SaveStep>, action: SaveAction): Int = steps.count { it.action == action }

    /** The newest file's time on each side among the conflicts: (this device, Steam Cloud), epoch seconds. */
    fun conflictTimes(steps: List<SaveStep>): Pair<Long, Long> {
        val conflicts = steps.filter { it.action == SaveAction.CONFLICT }
        return (conflicts.maxOfOrNull { it.local?.modifiedSec ?: 0 } ?: 0) to
            (conflicts.maxOfOrNull { it.cloud?.modifiedSec ?: 0 } ?: 0)
    }

    fun time(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (epochSec <= 0) "unknown" else TIME.withZone(zone).format(Instant.ofEpochSecond(epochSec))

    /** What a finished sync did, in a sentence or two for the launcher's status line. */
    fun summary(downloaded: Int, uploaded: Int, notUploaded: Int, uploadsOn: Boolean, uploadError: String?): String {
        val parts = ArrayList<String>()
        if (downloaded > 0) parts.add("Downloaded ${files(downloaded)} from Steam Cloud.")
        if (uploaded > 0) parts.add("Uploaded ${files(uploaded)} to Steam Cloud.")
        if (notUploaded > 0) {
            parts.add(
                when {
                    uploadError != null -> "Couldn't upload ${files(notUploaded)} ($uploadError); they stay on this device and are tried again next time."
                    !uploadsOn -> "${files(notUploaded).replaceFirstChar { it.uppercase() }} changed on this device and not uploaded: uploads are off (Settings)."
                    else -> "${files(notUploaded).replaceFirstChar { it.uppercase() }} not uploaded."
                }
            )
        }
        return if (parts.isEmpty()) "Your saves match Steam Cloud." else parts.joinToString(" ")
    }

    /** How many of this device's save files differ from what was last in step with Steam Cloud. */
    fun changedSince(local: List<SaveFile>, base: Map<String, String>): Int = local.count { base[it.name] != it.sha }

    /**
     * The launcher's standing line about the saves: when the last sync with Steam Cloud finished
     * ([lastSyncMs], 0 for never) and how many files have changed on this device since.
     */
    fun syncStatus(nowMs: Long, lastSyncMs: Long, changed: Int): String {
        val synced = if (lastSyncMs <= 0) "Not synced with Steam Cloud yet" else "Synced with Steam Cloud ${ago(nowMs - lastSyncMs)}"
        return if (changed > 0) "$synced · ${files(changed)} changed here since" else synced
    }

    private fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
            minutes < 48 * 60 -> (minutes / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    private fun files(n: Int) = if (n == 1) "1 save file" else "$n save files"

    /**
     * The check as a report: one line per file with what a sync would do. It holds file names, sizes
     * and times only (no account name, no machine names), so it can be shared as it is.
     */
    fun report(steps: List<SaveStep>, uploads: Boolean): List<String> {
        if (steps.isEmpty()) return listOf("No save files on this device or in Steam Cloud.")
        val out = ArrayList<String>()
        for (s in steps) {
            val what = when (s.action) {
                SaveAction.SAME -> "same on both"
                SaveAction.DOWNLOAD -> "would DOWNLOAD from Steam Cloud"
                SaveAction.UPLOAD -> if (uploads) "would UPLOAD to Steam Cloud" else "changed here; NOT uploaded (uploads are off)"
                SaveAction.REMOVE_LOCAL -> "deleted in Steam Cloud; would move this device's copy to the backup"
                SaveAction.DELETED_HERE -> "deleted here; left in Steam Cloud"
                SaveAction.CONFLICT -> "CONFLICT: changed on both sides; you would be asked"
            }
            out.add("${s.name}: $what")
            out.add("    here:  ${side(s.local)}")
            out.add("    cloud: ${side(s.cloud)}")
        }
        return out
    }

    private fun side(f: SaveFile?): String =
        if (f == null) "none"
        else "${kib(f.size)} KiB  ${time(f.modifiedSec, ZoneOffset.UTC)}Z  sha ${f.sha.take(8)}"

    private fun kib(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1024.0)

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}
