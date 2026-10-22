package io.github.aerithmetiq.alabasterdawnbox.steam

import java.io.File
import java.io.IOException

/**
 * Syncs the device's save folder with Alabaster Dawn's Steam Cloud files. The decisions are
 * [CloudSaves]'; this class reads the two sides and carries the steps out.
 *
 * - [check] is **read-only** on both sides: it lists Steam Cloud, hashes the local files and plans.
 * - [apply] changes this device (downloads, with every replaced file first copied to [backups]) and,
 *   only when `uploads` is true, **writes to Steam Cloud**. It never deletes a Steam Cloud file.
 *
 * Run it only while the game is not running. Every call blocks.
 *
 * @param saves the game's `Saves/` folder on this device
 * @param baseFile where the last-synced checksums are kept (see [CloudSaves])
 * @param backups where local files go before a sync replaces or removes them
 */
class CloudSync(
    private val cloud: SteamCloudFiles,
    private val saves: File,
    private val baseFile: File,
    private val backups: File,
) {

    class Check(val steps: List<SaveStep>, val base: Map<String, String>)

    class Outcome(
        val downloaded: Int,
        val uploaded: Int,
        /** Files changed here that were not sent: uploads are off, or [uploadError] stopped them. */
        val notUploaded: Int,
        val uploadError: String?,
    )

    fun check(): Check {
        val base = base(baseFile)
        val there = cloud.list().mapNotNull { f -> CloudSaves.relative(f.name)?.let { f.copy(name = it) } }
        return Check(CloudSaves.plan(local(saves), there, base), base)
    }

    /**
     * Carries out [steps], which must hold no conflict (see [CloudSaves.resolve]). Downloads are all
     * fetched and checked before any local file is touched, so a failed download changes nothing and
     * throws. Uploads come last, one by one; the first that fails stops the rest and is reported in
     * the [Outcome] rather than thrown, because by then this device is already up to date.
     */
    fun apply(
        check: Check,
        steps: List<SaveStep>,
        uploads: Boolean,
        keptLocal: Set<String>,
        status: (String) -> Unit,
    ): Outcome {
        require(steps.none { it.action == SaveAction.CONFLICT }) { "conflicts must be settled first" }
        val done = HashSet<String>()

        val downloads = steps.filter { it.action == SaveAction.DOWNLOAD }
        val fetched = downloads.mapIndexed { i, s ->
            status("Downloading your saves from Steam Cloud (${i + 1} of ${downloads.size})…")
            s to cloud.download(s.cloud!!.copy(name = CloudSaves.cloudName(s.name)))
        }
        val removals = steps.filter { it.action == SaveAction.REMOVE_LOCAL }
        if (fetched.isNotEmpty() || removals.isNotEmpty()) {
            val backup = File(backups, SaveBackups.stamp())
            for ((s, bytes) in fetched) {
                val target = File(saves, s.name)
                if (target.isFile) target.copyTo(File(backup, s.name), overwrite = true)
                writeAtomically(target, bytes)
                target.setLastModified(s.cloud!!.modifiedSec * 1000)
                done.add(s.name)
            }
            for (s in removals) {
                val target = File(saves, s.name)
                target.copyTo(File(backup, s.name), overwrite = true)
                if (target.delete()) done.add(s.name)
            }
            SaveBackups.prune(backups)
        }
        /* This device is now up to date; record that before anything is sent. */
        saveBase(CloudSaves.newBase(check.base, steps, done, keptLocal))

        val toSend = steps.filter { it.action == SaveAction.UPLOAD }
        var uploaded = 0
        var error: String? = null
        if (uploads) {
            for (s in toSend) {
                status("Uploading your saves to Steam Cloud (${uploaded + 1} of ${toSend.size})…")
                try {
                    val file = File(saves, s.name)
                    val bytes = file.readBytes()
                    if (SteamCloudFiles.sha1(bytes) != s.local!!.sha) throw IOException("${s.name} changed during the sync")
                    cloud.upload(CloudSaves.cloudName(s.name), bytes, s.local.modifiedSec)
                    done.add(s.name)
                    uploaded++
                    saveBase(CloudSaves.newBase(check.base, steps, done, keptLocal))
                } catch (e: Exception) {
                    error = SteamProbe.describe(e)
                    break
                }
            }
        }
        return Outcome(fetched.size, uploaded, toSend.size - uploaded, error)
    }

    private fun saveBase(base: Map<String, String>) {
        baseFile.parentFile?.mkdirs()
        writeAtomically(baseFile, CloudSaves.formatBase(base).toByteArray())
    }

    /** Through a temp file and a rename, so a crash mid-write cannot leave half a save. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".cloudtmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    companion object {
        /** The checksums of the last sync, or none when there has not been one. */
        fun base(baseFile: File): Map<String, String> =
            runCatching { CloudSaves.parseBase(baseFile.readText()) }.getOrDefault(emptyMap())

        /**
         * The save files on this device. Empty files are left out: a zero-byte save is a write that
         * never finished, and must not be weighed against (or sent over) a real one.
         */
        fun local(saves: File): List<SaveFile> =
            saves.walkTopDown().filter { it.isFile && it.length() > 0 && !it.name.endsWith(".cloudtmp") }.map {
                SaveFile(
                    name = it.relativeTo(saves).invariantSeparatorsPath,
                    sha = SteamCloudFiles.sha1(it.readBytes()),
                    size = it.length(),
                    modifiedSec = it.lastModified() / 1000,
                )
            }.toList()
    }
}
