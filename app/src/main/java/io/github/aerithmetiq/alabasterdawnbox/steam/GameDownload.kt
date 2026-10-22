package io.github.aerithmetiq.alabasterdawnbox.steam

import java.util.Locale

/**
 * The decisions of the game download, kept apart from Steam and Android so they can be unit-tested:
 * which depots to look in, where the web game ([WEB_ROOT]) sits inside a depot, and which of a depot's
 * files are wanted and where they go.
 */
object GameDownload {

    /** The web game: the one folder of the Steam install that is downloaded (the rest is NW.js). */
    const val WEB_ROOT = "terra"

    /** The engine's entry page; the depot that holds it is the one to download from. */
    const val ENTRY = "terra/index.html"

    /** Written into the install folder once every file is in place and verified. */
    const val MARKER = "installed.txt"

    /**
     * The depots that could hold the web game, most likely first: those with a public manifest that
     * belong to this app and are not DLC, with the depots shared by every platform the game ships on
     * ([platforms], PICS `common/oslist`) ahead of the per-platform ones (the desktop runtimes).
     */
    fun candidates(depots: List<DepotSummary>, platforms: String?): List<DepotSummary> {
        val all = list(platforms)
        return depots
            .filter { it.publicManifest?.toULongOrNull() != null && it.fromApp == null && it.dlcAppId == null }
            .sortedWith(compareBy({ !list(it.oslist).let { os -> os.isEmpty() || os.containsAll(all) } }, { it.id }))
    }

    /**
     * The depots added to the one that holds the game ([main]): the app's other depots with a public
     * manifest that are not tied to a platform (no `oslist`), DLC included. Steam installs these on
     * every platform alongside the platform's own depot, and their files land in the same folder.
     * Depots of DLC the account does not own are refused a key and left out when downloading.
     */
    fun extras(depots: List<DepotSummary>, main: Int): List<DepotSummary> =
        depots.filter { it.id != main && it.publicManifest?.toULongOrNull() != null && it.fromApp == null && list(it.oslist).isEmpty() }
            .sortedBy { it.id }

    /**
     * The files of several depots in one map, by their path below the install folder. A later layer's
     * file replaces an earlier one's at the same path, as an added depot's would in a Steam install.
     * Also returns how many were replaced, for the log.
     */
    fun <T> merge(layers: List<Map<String, T>>): Pair<Map<String, T>, Int> {
        val out = LinkedHashMap<String, T>()
        var replaced = 0
        for (layer in layers) for ((path, file) in layer) {
            if (out.put(path, file) != null) replaced++
        }
        return out to replaced
    }

    /**
     * What comes before [WEB_ROOT] in a depot's file names ("" when it sits at the depot's top), or null
     * when the depot does not hold the web game at all.
     */
    fun webPrefix(fileNames: Iterable<String>): String? =
        fileNames.map(::normalize)
            .filter { it == ENTRY || it.endsWith("/$ENTRY") }
            .minByOrNull { it.length }
            ?.removeSuffix(ENTRY)

    /**
     * Where a depot file goes under the install folder (`terra/…`, with `/` separators), or null when
     * the file is outside the web game or its name is not a plain relative path.
     */
    fun target(fileName: String, prefix: String): String? {
        val name = normalize(fileName)
        if (!name.startsWith("$prefix$WEB_ROOT/")) return null
        val relative = name.removePrefix(prefix)
        if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return relative
    }

    /** The marker file's `key=value` lines (app, depot, manifest, build). */
    fun parseMarker(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val cut = line.indexOf('=')
            if (cut <= 0) null else line.substring(0, cut).trim() to line.substring(cut + 1).trim()
        }.toMap()

    /**
     * Whether Steam's public build is another one than the installed one. Unknown on either side is
     * "no": an update is only offered when both are known and differ.
     */
    fun updateAvailable(installedBuild: String?, steamBuild: String?): Boolean {
        val here = installedBuild?.toLongOrNull() ?: return false
        val there = steamBuild?.toLongOrNull() ?: return false
        return here != there
    }

    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else (done.coerceIn(0, total) * 100 / total).toInt()

    fun progressLine(done: Long, total: Long): String =
        "Downloading Alabaster Dawn: ${mib(done.coerceAtMost(total))} of ${mib(total)} MiB (${percent(done, total)}%)"

    private fun mib(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1048576.0)

    /**
     * [items] largest first and smallest first in turn (largest, smallest, second largest, ...), so a
     * download moves big and small files together: one big file's chunks keep the bandwidth busy
     * while the small files' round trips overlap them.
     */
    fun <T> interleaveBySize(items: List<T>, size: (T) -> Long): List<T> {
        val sorted = items.sortedByDescending(size)
        val out = ArrayList<T>(sorted.size)
        var big = 0
        var small = sorted.size - 1
        while (big <= small) {
            out.add(sorted[big++])
            if (big <= small) out.add(sorted[small--])
        }
        return out
    }

    /** Depot manifests may use either separator, depending on how the manifest was read. */
    private fun normalize(fileName: String): String = fileName.replace('\\', '/').trimStart('/')

    private fun list(csv: String?): Set<String> =
        csv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
