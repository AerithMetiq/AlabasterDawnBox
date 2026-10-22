package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Asks a signed-in [SteamSession] what the port will need to know before it can download a game or
 * sync its saves, and writes it down. **Read-only**: it requests access tokens, product info (PICS)
 * and the Steam Cloud file list, and nothing else — no download, no upload, no "playing" status.
 *
 * Per game the report has: whether the account holds a token for it, its branches and build ids, its
 * depots (which hold the game files, per OS), its `ufs` block (the Steam Cloud rules: quota, and where
 * the save files live on each OS), and the account's Steam Cloud files for it. It leaves out the
 * account name and the Cloud's machine names, so a report can be shared as it is.
 */
class SteamProbe(private val session: SteamSession) {

    fun run(games: List<SteamGame>, header: List<String>, progress: (String) -> Unit): String {
        val apps = session.client.getHandler(SteamApps::class.java) ?: error("JavaSteam has no SteamApps handler")
        val cloud = session.client.getHandler(SteamCloud::class.java) ?: error("JavaSteam has no SteamCloud handler")
        val out = ArrayList<String>(header)

        progress("Asking Steam for access tokens…")
        val tokens = apps.picsGetAccessTokens(games.map { it.appId }, emptyList()).toFuture()
            .get(TIMEOUT_SEC, TimeUnit.SECONDS)

        progress("Asking Steam for product info…")
        val requests = games.map { PICSRequest(it.appId, tokens.appTokens[it.appId] ?: 0L) }
        val info = apps.picsGetProductInfo(requests, emptyList()).toFuture().get(TIMEOUT_SEC, TimeUnit.SECONDS)
        val byApp: Map<Int, PICSProductInfo> = info.results.flatMap { it.apps.entries }.associate { it.key to it.value }
        val unknown = info.results.flatMap { it.unknownApps }.toSet()

        for (game in games) {
            out.add("")
            out.add("== ${game.displayName} (app ${game.appId}) ==")
            out.add(
                when (game.appId) {
                    in tokens.appTokens -> "access token: granted"
                    in tokens.appTokensDenied -> "access token: denied"
                    else -> "access token: none returned"
                }
            )

            val product = byApp[game.appId]
            when {
                game.appId in unknown -> out.add("product info: Steam does not know this app")
                product == null -> out.add("product info: none returned")
                else -> {
                    val kv = product.keyValues
                    out.add("product info: change ${product.changeNumber}" + if (product.isMissingToken) ", public part only (no token)" else "")
                    kv["common"]["name"].value?.let { out.add("name: $it") }
                    kv["common"]["oslist"].value?.let { out.add("platforms: $it") }
                    out.addAll(ProbeReport.branches(kv))
                    val depots = ProbeReport.depots(kv)
                    out.add("depots: ${depots.size}")
                    depots.forEach { out.add("  " + ProbeReport.depotLine(it)) }
                    val ufs = kv["ufs"]
                    if (ufs.children.isEmpty()) {
                        out.add("ufs (Steam Cloud rules): none")
                    } else {
                        out.add("ufs (Steam Cloud rules):")
                        ProbeReport.tree(ufs).forEach { out.add("  $it") }
                    }
                }
            }

            progress("Listing ${game.displayName}'s Steam Cloud files…")
            try {
                val list = cloud.getAppFileListChange(game.appId).get(TIMEOUT_SEC, TimeUnit.SECONDS)
                val files = list.files.map {
                    CloudFile(
                        prefix = list.pathPrefixes.getOrNull(it.pathPrefixIndex),
                        name = it.filename,
                        size = it.rawFileSize.toLong(),
                        modifiedEpochSec = it.timestamp.time / 1000,
                        platforms = it.platformsToSync,
                    )
                }
                out.add("cloud: ${files.size} file(s), ${list.pathPrefixes.size} path prefix(es), change ${list.currentChangeNumber}")
                list.pathPrefixes.forEachIndexed { i, p -> out.add("  prefix $i: $p") }
                ProbeReport.cloudLines(files).forEach { out.add("  $it") }
            } catch (e: Exception) {
                out.add("cloud: could not list (${describe(e)})")
            }
        }
        return out.joinToString("\n")
    }

    companion object {
        private const val TIMEOUT_SEC = 30L

        /** The useful part of an exception from a future: its cause's message, or its type. */
        fun describe(e: Throwable): String {
            val cause = if (e is ExecutionException) e.cause ?: e else e
            return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        }
    }
}
