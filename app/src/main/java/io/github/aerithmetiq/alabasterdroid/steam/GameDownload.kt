package io.github.aerithmetiq.alabasterdroid.steam

import java.util.Locale

/**
 * The decisions of the game download, kept apart from Steam and Android so they can be unit-tested:
 * which depots to look in, where the web game (`terra/`) sits inside a depot, and which of a depot's
 * files are wanted and where they go.
 */
object GameDownload {

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
     * What comes before `terra/` in a depot's file names ("" when it sits at the depot's top), or null
     * when the depot does not hold the web game at all.
     */
    fun terraPrefix(fileNames: Iterable<String>): String? =
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
        if (!name.startsWith(prefix + "terra/")) return null
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

    /** Depot manifests may use either separator, depending on how the manifest was read. */
    private fun normalize(fileName: String): String = fileName.replace('\\', '/').trimStart('/')

    private fun list(csv: String?): Set<String> =
        csv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
