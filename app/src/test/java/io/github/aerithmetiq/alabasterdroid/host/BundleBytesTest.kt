package io.github.aerithmetiq.alabasterdroid.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The bundle's edits on its bytes must give exactly what the String-based rewrites gave on its decoded
 * text: the same ladder, the same `TEX_SLOT_COUNT`, the same version, and the bytes around them kept.
 */
class BundleBytesTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun sameAsString(text: String, from: String, to: String) {
        assertArrayEquals("'$from' -> '$to' in '$text'", bytes(text.replace(from, to)), BundleBytes.replace(bytes(text), from, to))
    }

    @Test
    fun `replace matches String replace`() {
        sameAsString("const RESOLUTION_MAP = [1, 2, 3, 4, 6];", "const RESOLUTION_MAP = [1, 2, 3, 4, 6];", "const RESOLUTION_MAP = [1, 1.5, 2, 3, 4];")
        sameAsString("a const RESOLUTION_MAP = [1, 2, 3, 4, 6]; b", "[1, 2, 3, 4, 6]", "[1, 1.5, 2, 3, 4]")
        sameAsString("xx", "x", "yyy")
        sameAsString("xxx", "xx", "y")
        sameAsString("abcabcab", "abc", "")
        sameAsString("abab", "ab", "ba")
        sameAsString("nothing here", "absent", "x")
        sameAsString("", "x", "y")
        sameAsString("x", "x", "x")
    }

    @Test
    fun `text around a match keeps its multi-byte characters`() {
        sameAsString("“Écran” … const TEX_SLOT_COUNT = 256; — 日本語 const TEX_SLOT_COUNT = 256;€",
            ShaderSlots.BUNDLE_CONSTANT, "const TEX_SLOT_COUNT = 192;")
    }

    @Test
    fun `no match gives back the same array`() {
        val source = bytes("const RESOLUTION_MAP = [1, 2];")
        assertSame(source, BundleBytes.replace(source, "const RESOLUTION_MAP = [1, 2, 3, 4, 6];", "x"))
        assertSame("an empty pattern changes nothing", source, BundleBytes.replace(source, "", "x"))
    }

    @Test
    fun `the slot table edit is ShaderSlots rewrite`() {
        val bundle = "…\nconst TEX_SLOT_COUNT = 256;\nshader(`#define TEX_SLOT_COUNT 256\nuniform vec2 u[TEX_SLOT_COUNT];`);\n" +
            "x = \"é\"; const TEX_SLOT_COUNT = 256;"
        for (slots in listOf(64, 192, 255)) {
            assertArrayEquals("slots $slots", bytes(ShaderSlots.rewrite(bundle, slots)), BundleBytes.slotTable(bytes(bundle), slots))
        }
        val source = bytes(bundle)
        assertSame("the game's own size changes nothing", source, BundleBytes.slotTable(source, ShaderSlots.GAME))
    }

    @Test
    fun `the version is read as GameVersion reads it from the whole bundle`() {
        val block = """class VersionManager {
            constructor() {
                this.major = 0;
                this.minor = 2;
                this.patch = 0;
                this.hotfix = 7;
                this.suffix = "Early Access";
            }
            getVersionString() { return ""; }"""
        val bundle = "/* “bundle” — 日本語 */\n" + "x".repeat(100_000) + block + "\n" + "y".repeat(10_000)
        assertEquals("0.2.0-7 Early Access", GameVersion.fromBundle(bundle))
        assertEquals(GameVersion.fromBundle(bundle), BundleBytes.version(bytes(bundle)))
        assertNull(BundleBytes.version(bytes("class SomethingElse { }")))
        val unfinished = "class VersionManager { this.major = 1; this.minor = 0; this.patch = 0;" + " ".repeat(8000) + "getVersionString"
        assertEquals("a block longer than GameVersion reads", GameVersion.fromBundle(unfinished), BundleBytes.version(bytes(unfinished)))
    }

    @Test
    fun `indexOf finds the first match from a position`() {
        val hay = bytes("abcabc")
        assertEquals(0, BundleBytes.indexOf(hay, bytes("abc"), 0))
        assertEquals(3, BundleBytes.indexOf(hay, bytes("abc"), 1))
        assertEquals(-1, BundleBytes.indexOf(hay, bytes("abc"), 4))
        assertEquals(-1, BundleBytes.indexOf(hay, bytes("abcd"), 0))
        assertEquals(-1, BundleBytes.indexOf(hay, ByteArray(0), 0))
        assertEquals(-1, BundleBytes.indexOf(bytes("ab"), bytes("abc"), 0))
        assertEquals("a match ending on the last byte", 4, BundleBytes.indexOf(bytes("xxxxabc"), bytes("abc"), 0))
        assertEquals("skips over a near miss", 7, BundleBytes.indexOf(bytes("abdabcxabca"), bytes("abca"), 0))
    }
}
