package io.github.aerithmetiq.alabasterdroid.steam

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The parts of the Steam Cloud transfer that need no Steam: names, addresses, archives, upload blocks and checksums. */
class SteamCloudFilesTest {

    private fun zip(name: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(name))
            z.write(content)
            z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a Cloud name is its prefix and its file name, which CloudSaves maps to the saves folder`() {
        val name = SteamCloudFiles.fullName(listOf("%WinAppDataLocal%"), 0, "Default/System.save")
        assertEquals("%WinAppDataLocal%Default/System.save", name)
        assertEquals("Default/System.save", CloudSaves.relative(name))
        assertEquals("no prefix listed", "cc.save", SteamCloudFiles.fullName(emptyList(), 0, "cc.save"))
    }

    @Test
    fun `addresses join host and path`() {
        assertEquals("https://cloud.example/ugc/1", SteamCloudFiles.url(true, "cloud.example", "/ugc/1"))
        assertEquals("http://cloud.example/ugc/1", SteamCloudFiles.url(false, "cloud.example", "ugc/1"))
    }

    @Test
    fun `a body of the stored size is the file, a smaller one is unzipped`() {
        val save = "{\"save\":true}".repeat(200).toByteArray()
        assertArrayEquals(save, SteamCloudFiles.unpack(save, save.size))
        val packed = zip("System.save", save)
        assertArrayEquals(save, SteamCloudFiles.unpack(packed, save.size))
    }

    @Test
    fun `a body of the wrong size that is not an archive, or an archive of the wrong size, is refused`() {
        assertThrows(IOException::class.java) { SteamCloudFiles.unpack(ByteArray(10), 20) }
        val packed = zip("System.save", ByteArray(100))
        assertThrows(IOException::class.java) { SteamCloudFiles.unpack(packed, 99) }
    }

    @Test
    fun `upload blocks send their slice of the file, or the bytes Steam spelled out`() {
        val file = ByteArray(10) { it.toByte() }
        assertArrayEquals(byteArrayOf(3, 4, 5), SteamCloudFiles.blockBody(file, 3, 3, ByteArray(0)))
        assertArrayEquals(file, SteamCloudFiles.blockBody(file, 0, 10, ByteArray(0)))
        assertArrayEquals(byteArrayOf(9, 9), SteamCloudFiles.blockBody(file, 0, 10, byteArrayOf(9, 9)))
        assertThrows(IOException::class.java) { SteamCloudFiles.blockBody(file, 8, 3, ByteArray(0)) }
    }

    @Test
    fun `HTTP methods are Steamworks' numbers`() {
        assertEquals("GET", SteamCloudFiles.method(1))
        assertEquals("POST", SteamCloudFiles.method(3))
        assertEquals("PUT", SteamCloudFiles.method(4))
        assertThrows(IOException::class.java) { SteamCloudFiles.method(5) }
    }

    @Test
    fun `checksums are lowercase hex, the form the sync base is stored in`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SteamCloudFiles.sha1("abc".toByteArray()))
        assertEquals("00ff10", SteamCloudFiles.hex(byteArrayOf(0, -1, 16)))
        assertEquals("", SteamCloudFiles.hex(null))
    }
}
