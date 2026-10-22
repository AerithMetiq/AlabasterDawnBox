package io.github.aerithmetiq.alabasterdawnbox.steam

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
import android.util.Log
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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * **Read-only**: the build id of the game's public branch on Steam, or null when Steam does not say.
 * The launcher compares it with the installed build to offer an update.
 */
fun steamPublicBuild(session: SteamSession, appId: Int): String? {
    val apps = session.client.getHandler(SteamApps::class.java) ?: return null
    val tokens = apps.picsGetAccessTokens(listOf(appId), emptyList()).toFuture().get(30, TimeUnit.SECONDS)
    val info = apps.picsGetProductInfo(listOf(PICSRequest(appId, tokens.appTokens[appId] ?: 0L)), emptyList())
        .toFuture().get(30, TimeUnit.SECONDS)
    val product = info.results.flatMap { it.apps.entries }.firstOrNull { it.key == appId }?.value ?: return null
    return product.keyValues["depots"]["branches"]["public"]["buildid"].value
}

/** The download cannot work as things are (no access, no such depot, no space); retrying will not help. */
class GameDownloadException(message: String) : Exception(message)

/**
 * Downloads a game's web build ([GameDownload.WEB_ROOT]) from Steam into [root], using a signed-in [SteamSession].
 * **Read-only against Steam**: product info, a depot key, a manifest and file chunks.
 *
 * Most of the web game's thousands of files are one small chunk each, so the time goes on waiting for answers,
 * not on bandwidth. [CONNECTIONS] chunk requests are kept in flight at once, across all files, to
 * Steam's least loaded servers, over connections that are kept open (the download session's HTTP
 * client is sized for it, see [SteamSession]). Big and small files are interleaved so the rate stays
 * even instead of collapsing into a tail of small files at the end.
 *
 * The depot is not assumed: each of the app's own depots is asked for its manifest until one lists
 * [GameDownload.ENTRY], and only the files under that [GameDownload.WEB_ROOT] are fetched, together with
 * those of the depots every platform installs alongside, owned DLC included ([GameDownload.extras]). Every chunk is checked
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

    /** The download servers, least loaded first; one that fails goes to the back. Guarded by itself. */
    private val servers = ArrayList<Server>()
    private var proxy: Server? = null
    private val nextServer = AtomicInteger(0)
    /** CDN auth tokens by host, asked for only when a server answers 403. */
    private val cdnTokens = ConcurrentHashMap<String, String>()
    /** Requests that failed and were tried again, for the log. */
    private val retries = AtomicInteger(0)

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
            servers.addAll(
                all.filter { (it.allowedAppIds.isEmpty() || appId in it.allowedAppIds) && (it.type == "SteamCache" || it.type == "CDN") }
                    .sortedBy { it.weightedLoad }
            )
            if (servers.isEmpty()) throw IOException("Steam returned no download servers")
            Log.i(TAG, "download: Steam offered ${all.size} servers, ${servers.size} usable; using " +
                servers.take(ACTIVE_SERVERS).joinToString { "${it.host} (${it.type}, load ${it.load}, weighted ${it.weightedLoad})" })

            status("Looking for the game's files…")
            val skipped = ArrayList<String>()
            var found: Found? = null
            for (depot in candidates) {
                check()
                found = open(depot, skipped)
                if (found != null) break
            }
            if (found == null) {
                throw GameDownloadException("Could not find ${GameDownload.ENTRY} in the game's Steam depots (${skipped.joinToString("; ")}).")
            }
            /* The content every platform installs alongside, and DLC this account owns. */
            val extras = ArrayList<Found>()
            val extraDepots = GameDownload.extras(ProbeReport.depots(kv), found.depotId)
            if (extraDepots.isNotEmpty()) status("Looking for the game's other content and DLC…")
            for (depot in extraDepots) {
                check()
                val before = skipped.size
                open(depot, skipped, found.prefix)?.let { extras.add(it) }
                if (skipped.size > before) Log.i(TAG, "download: left out ${skipped.last()}")
            }
            download(listOf(found) + extras, bytes)

            val build = kv["depots"]["branches"]["public"]["buildid"].value
            File(root, GameDownload.MARKER).writeText(
                "app=$appId\ndepot=${found.depotId}\nmanifest=${found.manifest.manifestGID.toULong()}\nbuild=${build ?: "unknown"}\n" +
                    (if (extras.isEmpty()) "" else "extra=" + extras.joinToString(",") { "${it.depotId}:${it.manifest.manifestGID.toULong()}" } + "\n")
            )
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            scope.cancel()
        }
    }

    private class Found(val depotId: Int, val key: ByteArray, val manifest: DepotManifest, val prefix: String)

    /**
     * Fetches one depot's manifest; null (with the reason added to [skipped]) when it is not the one.
     * With [prefix] given, the depot is an added one ([GameDownload.extras]): its files sit where the
     * main depot's do, and it need not hold the entry page.
     */
    private fun open(depot: DepotSummary, skipped: MutableList<String>, prefix: String? = null): Found? {
        val manifestId = depot.publicManifest?.toULongOrNull()?.toLong() ?: return null
        val key = apps.getDepotDecryptionKey(depot.id, appId).toFuture().get(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (key.result != EResult.OK) {
            skipped.add("depot ${depot.id}: no key (${key.result})" + if (depot.dlcAppId != null) ", DLC ${depot.dlcAppId} not owned" else "")
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
        val at = prefix ?: GameDownload.webPrefix(manifest.files.map { it.fileName })
        if (at == null) {
            skipped.add("depot ${depot.id}: no ${GameDownload.WEB_ROOT}/")
            return null
        }
        return Found(depot.id, key.depotKey, manifest, at)
    }

    private fun download(depots: List<Found>, bytes: (Long, Long) -> Unit) {
        /* What is wanted: the files under the web game's folder, by their path below the install folder,
         * each with the depot it comes from; an added depot's file replaces the main one's. */
        val layers = depots.map { found ->
            val files = LinkedHashMap<String, Pair<Found, FileData>>()
            for (file in found.manifest.files) {
                val target = GameDownload.target(file.fileName, found.prefix) ?: continue
                if (EDepotFileFlag.Symlink in file.flags) continue
                if (EDepotFileFlag.Directory in file.flags) File(root, target).mkdirs() else files[target] = found to file
            }
            Log.i(TAG, "download: depot ${found.depotId}: ${files.size} files under ${GameDownload.WEB_ROOT}/, " +
                "${files.values.sumOf { it.second.totalSize } / 1048576} MiB")
            files
        }
        val (merged, replaced) = GameDownload.merge(layers)
        if (replaced > 0) Log.i(TAG, "download: $replaced files of the main depot are replaced by an added one")
        val wanted = merged.mapValues { it.value.second }
        val source = merged.mapValues { it.value.first }
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
        val fetched = AtomicLong(0)
        val lastReport = AtomicLong(0)
        fun add(n: Long) {
            val now = done.addAndGet(n)
            val t = System.nanoTime()
            val last = lastReport.get()
            if (t - last > REPORT_NANOS && lastReport.compareAndSet(last, t)) bytes(now, total)
        }
        bytes(0, total)

        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(CONNECTIONS)
        val parts = ArrayList<Part>()
        try {
            /* First, which files are already right (an interrupted download, or a verify): hashed in
             * parallel, and counted as done. */
            val needed = ConcurrentLinkedQueue<Pair<String, FileData>>()
            wanted.entries.map { (target, file) ->
                pool.submit {
                    guarded {
                        check()
                        if (isCurrent(File(root, target), file)) add(file.totalSize) else needed.add(target to file)
                    }
                }
            }.forEach { it.get() }
            failure.get()?.let { throw it }
            val checkedNanos = System.nanoTime() - started

            /* Then every chunk of every other file, as one queue. Files are taken alternately from the
             * big end and the small end, and each file's chunks are queued together, so only a few
             * files are open at a time. */
            val jobs = ArrayList<Future<*>>()
            for ((target, file) in GameDownload.interleaveBySize(needed.toList()) { it.second.totalSize }) {
                val part = Part(File(root, target), file)
                parts.add(part)
                if (file.chunks.isEmpty()) {
                    part.finish()
                    continue
                }
                val found = source.getValue(target)
                for (chunk in file.chunks.sortedBy { it.offset }) {
                    jobs.add(pool.submit {
                        guarded {
                            check()
                            val data = fetch(found.depotId, "part of ${part.dest.name}") { server, token ->
                                val raw = ByteArray(chunk.compressedLength)
                                cdn.downloadDepotChunk(found.depotId, chunk, server, raw, found.key, proxy, token)
                                ByteArray(chunk.uncompressedLength).also { DepotChunk.process(chunk, raw, it, found.key) }
                            }
                            part.write(chunk.offset, data)
                            fetched.addAndGet(data.size.toLong())
                            add(data.size.toLong())
                        }
                    })
                }
            }
            jobs.forEach { it.get() }
            failure.get()?.let { throw it }
            check()
            val seconds = (System.nanoTime() - started) / 1e9
            Log.i(
                TAG,
                "download: ${needed.size} files, ${fetched.get() / 1048576} MiB fetched in %.1f s (%.1f MiB/s); ".format(
                    seconds, fetched.get() / 1048576.0 / seconds.coerceAtLeast(0.001),
                ) + "${wanted.size - needed.size} files already current, checked in %.1f s; ".format(checkedNanos / 1e9) +
                    "${retries.get()} requests tried again",
            )
        } finally {
            pool.shutdownNow()
            parts.forEach { it.abandon() }
        }
        bytes(total, total)

        /* Files a game update no longer ships, and anything left from an interrupted run. */
        File(root, GameDownload.WEB_ROOT).walkBottomUp().forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (f.isFile && rel !in wanted && !GameDownload.kept(rel)) f.delete()
        }
    }

    /** Runs [body], recording its first failure for every other job to see (see [check]). */
    private inline fun guarded(body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            failure.compareAndSet(null, e)
        }
    }

    /**
     * One file being written: its chunks arrive in any order from any worker and are written at
     * their offsets into `<name>.part`, which is opened at the first chunk and renamed to [dest] after
     * the last. A file whose download failed keeps its `.part`, which the next run starts again.
     */
    private class Part(val dest: File, private val file: FileData) {
        private val tmp = File(dest.path + ".part")
        private var out: RandomAccessFile? = null
        private var channel: FileChannel? = null
        private var left = file.chunks.size

        @Synchronized
        fun write(offset: Long, data: ByteArray) {
            val ch = channel ?: open()
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) ch.write(buffer, offset + buffer.position())
            if (--left == 0) finish()
        }

        @Synchronized
        fun finish() {
            if (channel == null) open()
            out!!.setLength(file.totalSize)
            close()
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("could not write ${dest.name}")
            }
        }

        /** Closes the file if a failed or cancelled download left it open. */
        @Synchronized
        fun abandon() = close()

        private fun open(): FileChannel {
            dest.parentFile?.mkdirs()
            val file = RandomAccessFile(tmp, "rw")
            file.setLength(0)
            out = file
            return file.channel.also { channel = it }
        }

        private fun close() {
            runCatching { out?.close() }
            out = null
            channel = null
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
     * Runs one CDN request. A failure moves on to another server, after a pause that doubles each
     * time ([BACKOFF_MS] up to [MAX_BACKOFF_MS]): Steam's servers answer 503 when they are busy, and
     * asking again at once is what made a whole download fail on one chunk (seen on the Nova,
     * 2026-10-21). A 403 means the server wants a CDN auth token, which is then asked for once per
     * host and the request repeated at once.
     */
    private fun <T> fetch(depotId: Int, what: String, call: suspend (Server, String?) -> T): T {
        var last: Exception? = null
        var wait = false
        for (attempt in 0 until ATTEMPTS) {
            check()
            if (wait) pause(minOf(BACKOFF_MS shl (attempt - 1).coerceAtMost(10), MAX_BACKOFF_MS))
            val server = nextServer()
            val host = server.host
            try {
                return runBlocking { call(server, host?.let { cdnTokens[it] }) }
            } catch (e: SteamKitWebRequestException) {
                last = e
                wait = true
                if (e.statusCode == 403 && host != null && !cdnTokens.containsKey(host)) {
                    runCatching { await(content.getCDNAuthToken(appId, depotId, host, scope)) }.getOrNull()
                        ?.takeIf { it.result == EResult.OK }
                        ?.let {
                            cdnTokens[host] = it.token
                            wait = false
                        }
                } else {
                    demote(server)
                }
            } catch (e: IOException) {
                last = e
                wait = true
                demote(server)
            }
            if (retries.incrementAndGet() <= RETRIES_LOGGED) {
                Log.w(TAG, "download: $what failed on $host (${last?.message}); trying again")
            }
        }
        throw IOException("the $what could not be downloaded: ${last?.message ?: "no answer"}", last)
    }

    /** Sleeps [ms], in slices, so a cancelled or failed download does not wait out the whole pause. */
    private fun pause(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) {
            check()
            Thread.sleep(minOf(250L, (until - System.nanoTime()) / 1_000_000 + 1))
        }
    }

    /**
     * The server for the next request: the [ACTIVE_SERVERS] least loaded ones in turn. Few servers
     * means the connections to them stay open and are reused; a request to a new server costs a
     * connection and a TLS handshake, which is most of the time a small chunk takes.
     */
    private fun nextServer(): Server = synchronized(servers) {
        servers[(nextServer.getAndIncrement() and Int.MAX_VALUE) % minOf(ACTIVE_SERVERS, servers.size)]
    }

    /** A server that failed a request goes to the back of the list, so the next one in line takes its turns. */
    private fun demote(server: Server) = synchronized(servers) {
        if (servers.size > 1 && servers.remove(server)) servers.add(server)
    }

    /** Waits for one of JavaSteam's Steam requests; a dropped connection must not hang the download. */
    private fun <T : Any> await(deferred: Deferred<T>): T =
        runBlocking { withTimeoutOrNull(TIMEOUT_SEC * 1000) { deferred.await() } }
            ?: throw IOException("no answer from Steam in ${TIMEOUT_SEC}s")

    private fun check() {
        if (cancelled || failure.get() != null) throw CancellationException("download cancelled")
    }

    companion object {
        private const val TAG = "AlabasterDawnBox"
        private const val TIMEOUT_SEC = 30L
        /** Chunk requests in flight at once; the download session's HTTP client allows as many per server. */
        const val CONNECTIONS = 16
        /** How many of the least loaded servers share the requests. */
        private const val ACTIVE_SERVERS = 3
        private const val ATTEMPTS = 8
        private const val BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 8_000L
        private const val RETRIES_LOGGED = 20
        private const val REPORT_NANOS = 250_000_000L
        /** Kept free beyond the download itself. */
        private const val SPARE_BYTES = 64L * 1024 * 1024
    }
}
