package io.github.aerithmetiq.alabasterdroid.steam

import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.cdn.DepotChunk
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
import `in`.dragonbra.javasteam.util.SteamKitWebRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The download cannot work as things are (no access, no such depot, no space); retrying will not help. */
class GameDownloadException(message: String) : Exception(message)

/**
 * Downloads a game's web build (`terra/`) from Steam into [root], using a signed-in [SteamSession].
 * **Read-only against Steam**: product info, a depot key, a manifest and file chunks.
 *
 * The depot is not assumed: each of the app's own depots is asked for its manifest until one lists
 * `terra/index.html`, and only the files under that `terra/` are fetched. Every chunk is checked
 * against the manifest's checksum by JavaSteam as it is decrypted. Files already on disk with the
 * manifest's size and SHA-1 are kept, so an interrupted download carries on where it stopped.
 *
 * [run] blocks; drive it from a worker thread. [cancel] is safe from any thread.
 */
class GameDownloader(private val session: SteamSession, private val appId: Int, private val root: File) {

    private val apps = session.client.getHandler(SteamApps::class.java) ?: error("JavaSteam has no SteamApps handler")
    private val content = session.client.getHandler(SteamContent::class.java) ?: error("JavaSteam has no SteamContent handler")
    private val cdn = Client(session.client)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var cancelled = false
    private val failure = AtomicReference<Throwable?>(null)

    private var servers: List<Server> = emptyList()
    private var proxy: Server? = null
    private val nextServer = AtomicInteger(0)
    /** CDN auth tokens by host, asked for only when a server answers 403. */
    private val cdnTokens = ConcurrentHashMap<String, String>()

    fun cancel() {
        cancelled = true
    }

    /**
     * Downloads or completes the game. [status] gets the step being worked on; once file data is
     * moving, [bytes] gets (done, total) a few times a second.
     */
    fun run(status: (String) -> Unit, bytes: (done: Long, total: Long) -> Unit) {
        try {
            status("Asking Steam about the game…")
            val tokens = apps.picsGetAccessTokens(listOf(appId), emptyList()).toFuture().get(TIMEOUT_SEC, TimeUnit.SECONDS)
            val info = apps.picsGetProductInfo(listOf(PICSRequest(appId, tokens.appTokens[appId] ?: 0L)), emptyList())
                .toFuture().get(TIMEOUT_SEC, TimeUnit.SECONDS)
            val product = info.results.flatMap { it.apps.entries }.firstOrNull { it.key == appId }?.value
                ?: throw GameDownloadException("Steam returned no information about the game.")
            val kv = product.keyValues
            val candidates = GameDownload.candidates(ProbeReport.depots(kv), kv["common"]["oslist"].value)
            if (candidates.isEmpty()) {
                throw GameDownloadException("Steam lists no downloadable files for this account. Does it own Alabaster Dawn?")
            }

            status("Finding Steam's download servers…")
            val all = await(content.getServersForSteamPipe(parentScope = scope))
            proxy = all.firstOrNull { it.useAsProxy }
            servers = all
                .filter { (it.allowedAppIds.isEmpty() || appId in it.allowedAppIds) && (it.type == "SteamCache" || it.type == "CDN") }
                .sortedBy { it.weightedLoad }
            if (servers.isEmpty()) throw IOException("Steam returned no download servers")

            status("Looking for the game's files…")
            val skipped = ArrayList<String>()
            var found: Found? = null
            for (depot in candidates) {
                check()
                found = open(depot, skipped)
                if (found != null) break
            }
            if (found == null) {
                throw GameDownloadException("Could not find terra/index.html in the game's Steam depots (${skipped.joinToString("; ")}).")
            }
            download(found, bytes)

            val build = kv["depots"]["branches"]["public"]["buildid"].value
            File(root, GameDownload.MARKER).writeText(
                "app=$appId\ndepot=${found.depotId}\nmanifest=${found.manifest.manifestGID.toULong()}\nbuild=${build ?: "unknown"}\n"
            )
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            scope.cancel()
        }
    }

    private class Found(val depotId: Int, val key: ByteArray, val manifest: DepotManifest, val prefix: String)

    /** Fetches one depot's manifest; null (with the reason added to [skipped]) when it is not the one. */
    private fun open(depot: DepotSummary, skipped: MutableList<String>): Found? {
        val manifestId = depot.publicManifest?.toULongOrNull()?.toLong() ?: return null
        val key = apps.getDepotDecryptionKey(depot.id, appId).toFuture().get(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (key.result != EResult.OK) {
            skipped.add("depot ${depot.id}: no key (${key.result})")
            return null
        }
        val code = await(content.getManifestRequestCode(depot.id, appId, manifestId, "public", null, scope))
        if (code == 0L) {
            skipped.add("depot ${depot.id}: no manifest access")
            return null
        }
        val manifest = fetch(depot.id, "manifest of depot ${depot.id}") { server, token ->
            cdn.downloadManifest(depot.id, manifestId, code.toULong(), server, key.depotKey, proxy, token)
        }
        if (manifest.filenamesEncrypted) {
            skipped.add("depot ${depot.id}: file names could not be read")
            return null
        }
        val prefix = GameDownload.terraPrefix(manifest.files.map { it.fileName })
        if (prefix == null) {
            skipped.add("depot ${depot.id}: no terra/")
            return null
        }
        return Found(depot.id, key.depotKey, manifest, prefix)
    }

    private fun download(found: Found, bytes: (Long, Long) -> Unit) {
        /* What is wanted: the files under terra/, by their path below the install folder. */
        val wanted = LinkedHashMap<String, FileData>()
        for (file in found.manifest.files) {
            val target = GameDownload.target(file.fileName, found.prefix) ?: continue
            if (EDepotFileFlag.Symlink in file.flags) continue
            if (EDepotFileFlag.Directory in file.flags) File(root, target).mkdirs() else wanted[target] = file
        }
        val total = wanted.values.sumOf { it.totalSize }
        root.mkdirs()
        File(root, GameDownload.MARKER).delete()
        val missing = wanted.entries.filter { File(root, it.key).length() != it.value.totalSize }.sumOf { it.value.totalSize }
        val free = root.usableSpace
        if (free < missing + SPARE_BYTES) {
            throw GameDownloadException(
                "Not enough free storage: the download needs ${(missing + SPARE_BYTES) / 1048576} MiB and ${free / 1048576} MiB is free."
            )
        }

        val done = AtomicLong(0)
        val lastReport = AtomicLong(0)
        fun add(n: Long) {
            val now = done.addAndGet(n)
            val t = System.nanoTime()
            val last = lastReport.get()
            if (t - last > REPORT_NANOS && lastReport.compareAndSet(last, t)) bytes(now, total)
        }
        bytes(0, total)

        /* Largest first, so the big files overlap with the many small ones instead of trailing alone. */
        val pool = Executors.newFixedThreadPool(WORKERS)
        try {
            val jobs = wanted.entries.sortedByDescending { it.value.totalSize }.map { (target, file) ->
                pool.submit {
                    try {
                        check()
                        val dest = File(root, target)
                        if (isCurrent(dest, file)) add(file.totalSize)
                        else downloadFile(found, file, dest, ::add)
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }
            }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        failure.get()?.let { throw it }
        check()
        bytes(total, total)

        /* Files a game update no longer ships, and anything left from an interrupted run. */
        File(root, "terra").walkBottomUp().forEach { f ->
            if (f.isFile && f.relativeTo(root).invariantSeparatorsPath !in wanted) f.delete()
        }
    }

    private fun downloadFile(found: Found, file: FileData, dest: File, add: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        RandomAccessFile(part, "rw").use { out ->
            out.setLength(0)
            for (chunk in file.chunks.sortedBy { it.offset }) {
                val data = fetch(found.depotId, "part of ${dest.name}") { server, token ->
                    val raw = ByteArray(chunk.compressedLength)
                    cdn.downloadDepotChunk(found.depotId, chunk, server, raw, found.key, proxy, token)
                    ByteArray(chunk.uncompressedLength).also { DepotChunk.process(chunk, raw, it, found.key) }
                }
                out.seek(chunk.offset)
                out.write(data)
                add(data.size.toLong())
            }
            out.setLength(file.totalSize)
        }
        if (!part.renameTo(dest)) {
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("could not write ${dest.name}")
        }
    }

    /** Whether [dest] already is the manifest's file: the same size and SHA-1. */
    private fun isCurrent(dest: File, file: FileData): Boolean {
        if (!dest.isFile || dest.length() != file.totalSize) return false
        if (file.totalSize == 0L || file.fileHash.all { it.toInt() == 0 }) return true
        val sha = MessageDigest.getInstance("SHA-1")
        dest.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return sha.digest().contentEquals(file.fileHash)
    }

    /**
     * Runs one CDN request, moving to the next server on a failure. A 403 means the server wants a CDN
     * auth token, which is then asked for once per host.
     */
    private fun <T> fetch(depotId: Int, what: String, call: suspend (Server, String?) -> T): T {
        var last: Exception? = null
        repeat(ATTEMPTS) {
            check()
            val server = servers[(nextServer.getAndIncrement() and Int.MAX_VALUE) % servers.size]
            val host = server.host
            try {
                return runBlocking { call(server, host?.let { cdnTokens[it] }) }
            } catch (e: SteamKitWebRequestException) {
                last = e
                if (e.statusCode == 403 && host != null && !cdnTokens.containsKey(host)) {
                    runCatching { await(content.getCDNAuthToken(appId, depotId, host, scope)) }.getOrNull()
                        ?.takeIf { it.result == EResult.OK }
                        ?.let { cdnTokens[host] = it.token }
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw IOException("the $what could not be downloaded: ${last?.message ?: "no answer"}", last)
    }

    /** Waits for one of JavaSteam's Steam requests; a dropped connection must not hang the download. */
    private fun <T : Any> await(deferred: Deferred<T>): T =
        runBlocking { withTimeoutOrNull(TIMEOUT_SEC * 1000) { deferred.await() } }
            ?: throw IOException("no answer from Steam in ${TIMEOUT_SEC}s")

    private fun check() {
        if (cancelled || failure.get() != null) throw CancellationException("download cancelled")
    }

    private companion object {
        const val TIMEOUT_SEC = 30L
        const val WORKERS = 6
        const val ATTEMPTS = 6
        const val REPORT_NANOS = 250_000_000L
        /** Kept free beyond the download itself. */
        const val SPARE_BYTES = 64L * 1024 * 1024
    }
}
