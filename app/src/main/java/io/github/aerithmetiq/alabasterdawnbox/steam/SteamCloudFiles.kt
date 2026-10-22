package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.protobufs.steamclient.Enums.ECloudStoragePersistState
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * One app's Steam Cloud files: list them, fetch one, send one. Steam's Cloud service says where a
 * file is (or where its pieces go) and the bytes move over plain HTTP; JavaSteam makes the service
 * calls. What to move is decided by [CloudSaves] and carried out by [CloudSync].
 *
 * [list] and [download] only read. [upload] **writes to Steam Cloud**, and [CloudSync] calls it only
 * while the user has uploads on (hard rule 5). Nothing here deletes a Cloud file.
 *
 * Every call blocks, so run them on a worker thread. A failure is an [IOException] whose message can
 * go straight into the status line.
 */
class SteamCloudFiles(private val session: SteamSession, private val appId: Int) {

    private val cloud: SteamCloud = session.client.getHandler(SteamCloud::class.java)
        ?: error("JavaSteam has no SteamCloud handler")

    /**
     * The files Steam Cloud keeps for the app, each by its full Cloud name (the root token and the
     * path under it, as [CloudSaves.relative] reads it). Entries Steam marks deleted or forgotten are
     * not files any more and are left out.
     */
    fun list(): List<SaveFile> {
        val changes = cloud.getAppFileListChange(appId).get(TIMEOUT_SEC, TimeUnit.SECONDS)
        return changes.files
            .filter { it.persistState == ECloudStoragePersistState.k_ECloudStoragePersistStatePersisted }
            .map {
                SaveFile(
                    name = fullName(changes.pathPrefixes, it.pathPrefixIndex, it.filename),
                    sha = hex(it.shaFile),
                    size = it.rawFileSize.toLong(),
                    modifiedSec = it.timestamp.time / 1000,
                )
            }
    }

    /** [file]'s bytes, checked against the SHA-1 Steam Cloud keeps for it. [file]'s name is its full Cloud name. */
    fun download(file: SaveFile): ByteArray {
        val info = cloud.clientFileDownload(appId, file.name).get(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (info.isExplicitDelete) throw IOException("${file.name} was deleted in Steam Cloud")
        if (info.encrypted) throw IOException("${file.name} is stored encrypted in Steam Cloud, which this app cannot read")
        if (info.urlHost.isEmpty()) throw IOException("Steam gave no address for ${file.name}")
        val body = request("GET", url(info.useHttps, info.urlHost, info.urlPath), info.requestHeaders, null)
        val bytes = unpack(body, info.rawFileSize)
        val expected = hex(info.shaFile).ifEmpty { file.sha }
        if (expected.isNotEmpty() && sha1(bytes) != expected) {
            throw IOException("${file.name} did not match Steam Cloud's checksum")
        }
        return bytes
    }

    /**
     * **Writes to Steam Cloud**: stores [bytes] as [cloudName] with [modifiedSec] as its time.
     *
     * Steam is asked to start the upload and answers with the HTTP requests that carry the bytes; once
     * they are sent the upload is committed (or, after a failure, closed as failed, so nothing is left
     * half open). Then the Cloud listing is read back, and the upload only counts when it lists
     * [cloudName] with this content's SHA-1.
     *
     * The file is sent uncompressed and unencrypted, and outside an upload batch (`uploadBatchId` 0):
     * a batch needs the client id of the sign-in, which the app does not keep.
     */
    fun upload(cloudName: String, bytes: ByteArray, modifiedSec: Long) {
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes)
        val plan = cloud.beginFileUpload(
            appId = appId,
            fileSize = bytes.size,
            rawFileSize = bytes.size,
            fileSha = sha,
            timestamp = Date(modifiedSec * 1000),
            filename = cloudName,
            cellId = session.client.cellID ?: 0,
            canEncrypt = false,
            uploadBatchId = 0,
        ).get(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (plan.encryptFile) throw IOException("Steam Cloud wants $cloudName encrypted, which this app cannot do")

        val sent = runCatching {
            for (block in plan.blockRequests) {
                request(
                    method(block.httpMethod),
                    url(block.useHttps, block.urlHost, block.urlPath),
                    block.requestHeaders,
                    blockBody(bytes, block.blockOffset, block.blockLength, block.explicitBodyData),
                )
            }
        }
        val committed = runCatching {
            cloud.commitFileUpload(sent.isSuccess, appId, sha, cloudName).get(TIMEOUT_SEC, TimeUnit.SECONDS)
        }
        sent.exceptionOrNull()?.let { throw IOException("$cloudName could not be sent: ${SteamProbe.describe(it)}", it) }
        if (committed.getOrNull() != true) {
            throw IOException("Steam Cloud did not keep $cloudName" + (committed.exceptionOrNull()?.let { ": ${SteamProbe.describe(it)}" } ?: ""))
        }
        val listed = list().firstOrNull { it.name == cloudName }
        if (listed?.sha != hex(sha)) throw IOException("Steam Cloud does not list $cloudName with the content that was sent")
    }

    /** One HTTP exchange with the headers Steam supplied; the response body, or an [IOException] for any status but 2xx. */
    private fun request(method: String, url: String, headers: List<HttpHeaders>, body: ByteArray?): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            for (header in headers) connection.setRequestProperty(header.name, header.value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Steam Cloud's server answered HTTP $status")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 30L
        private const val HTTP_TIMEOUT_MS = 30_000

        /** A file's full Cloud name: its path prefix (the root token, usually) followed by its name. */
        internal fun fullName(prefixes: List<String>, prefixIndex: Int, filename: String): String =
            prefixes.getOrNull(prefixIndex).orEmpty() + filename

        internal fun url(https: Boolean, host: String, path: String): String =
            (if (https) "https://" else "http://") + host + (if (path.startsWith("/")) path else "/$path")

        /**
         * The file as it was stored: Steam Cloud may hand a file back compressed, as a zip archive
         * holding that one file. A body of the stored size is the file itself.
         */
        internal fun unpack(body: ByteArray, rawSize: Int): ByteArray {
            if (body.size == rawSize) return body
            if (!isZip(body)) throw IOException("Steam Cloud sent ${body.size} bytes for a file of $rawSize")
            val inner = ZipInputStream(ByteArrayInputStream(body)).use { zip ->
                zip.nextEntry ?: throw IOException("Steam Cloud sent an empty archive")
                zip.readBytes()
            }
            if (inner.size != rawSize) throw IOException("Steam Cloud's archive held ${inner.size} bytes for a file of $rawSize")
            return inner
        }

        private fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

        /** Steamworks' `EHTTPMethod` numbers, for the ones an upload block can ask for. */
        internal fun method(code: Int): String = when (code) {
            1 -> "GET"
            3 -> "POST"
            4 -> "PUT"
            else -> throw IOException("Steam Cloud asked for HTTP method $code, which this app does not send")
        }

        /** What one upload block carries: the bytes Steam spelled out for it, or its slice of the file. */
        internal fun blockBody(bytes: ByteArray, offset: Long, length: Int, explicit: ByteArray): ByteArray {
            if (explicit.isNotEmpty()) return explicit
            if (offset < 0 || length < 0 || offset + length > bytes.size) {
                throw IOException("Steam Cloud asked for bytes $offset+$length of a ${bytes.size}-byte file")
            }
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

        fun sha1(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))

        fun hex(bytes: ByteArray?): String {
            if (bytes == null) return ""
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
