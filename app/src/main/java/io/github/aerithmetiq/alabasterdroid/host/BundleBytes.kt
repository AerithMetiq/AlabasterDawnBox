package io.github.aerithmetiq.alabasterdroid.host

/**
 * The host's edits to `terra/dist/bundle.js`, made on the file's bytes. Alabaster Droid's own, not
 * from alabaster-android, which decodes the bundle's 12.7 MB into a String, replaces in it and
 * encodes it back: on a Retroid Pocket Nova that request took 304 ms to answer, and the page cannot
 * run anything until it has the bundle. Here nothing is decoded but the small block the game's version
 * is read from, and no copy is made where nothing matches.
 *
 * Every text the host looks for in the bundle is ASCII, and in UTF-8 an ASCII byte never occurs inside
 * a multi-byte character, so a match on the bytes is exactly a match on the text and every other byte
 * is kept as it was. The results are the same as [String.replace], [ShaderSlots.rewrite] and
 * [GameVersion.fromBundle] on the decoded text (the tests compare them).
 */
object BundleBytes {

    /** Room for the version block after [GameVersion.VERSION_MANAGER]; [GameVersion] reads at most 4 000 characters of it. */
    private const val VERSION_BYTES = 64 * 1024

    /** The game's own build string, as [GameVersion.fromBundle] reads it from the decoded bundle. */
    fun version(bundle: ByteArray): String? =
        textFrom(bundle, GameVersion.VERSION_MANAGER, VERSION_BYTES)?.let(GameVersion::fromBundle)

    /** [ShaderSlots.rewrite] on the bytes: the engine's `TEX_SLOT_COUNT` set to [slots]. */
    fun slotTable(bundle: ByteArray, slots: Int): ByteArray {
        if (slots >= ShaderSlots.GAME) return bundle
        val defined = replace(bundle, ShaderSlots.DEFINE, "#define TEX_SLOT_COUNT $slots")
        return replace(defined, ShaderSlots.BUNDLE_CONSTANT, "const TEX_SLOT_COUNT = $slots;")
    }

    /**
     * [bytes] with every occurrence of [from] replaced by [to], left to right and not overlapping, as
     * [String.replace] does. The same array comes back when there is none, and for an empty [from].
     */
    fun replace(bytes: ByteArray, from: String, to: String): ByteArray {
        val pattern = from.toByteArray(Charsets.UTF_8)
        if (pattern.isEmpty()) return bytes
        val skip = skipTable(pattern)
        val matches = ArrayList<Int>(1)
        var at = search(bytes, pattern, skip, 0)
        while (at >= 0) {
            matches.add(at)
            at = search(bytes, pattern, skip, at + pattern.size)
        }
        if (matches.isEmpty()) return bytes
        val replacement = to.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size + matches.size * (replacement.size - pattern.size))
        var read = 0
        var written = 0
        for (match in matches) {
            System.arraycopy(bytes, read, out, written, match - read)
            written += match - read
            System.arraycopy(replacement, 0, out, written, replacement.size)
            written += replacement.size
            read = match + pattern.size
        }
        System.arraycopy(bytes, read, out, written, bytes.size - read)
        return out
    }

    /** Where [pattern] first occurs in [bytes] at or after [from], or -1. */
    fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int =
        if (pattern.isEmpty()) -1 else search(bytes, pattern, skipTable(pattern), from)

    /**
     * Horspool's search: compare from the window's last byte, and on a mismatch move the window by
     * how far that byte is from the pattern's end, which for these 25-40 byte patterns skips most of
     * the bundle without looking at it.
     */
    private fun search(bytes: ByteArray, pattern: ByteArray, skip: IntArray, from: Int): Int {
        val m = pattern.size
        val last = bytes.size - m
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            var j = m - 1
            while (j >= 0 && bytes[i + j] == pattern[j]) j--
            if (j < 0) return i
            i += skip[bytes[i + m - 1].toInt() and 0xFF]
        }
        return -1
    }

    private fun skipTable(pattern: ByteArray): IntArray {
        val skip = IntArray(256) { pattern.size }
        for (k in 0 until pattern.size - 1) skip[pattern[k].toInt() and 0xFF] = pattern.size - 1 - k
        return skip
    }

    /** The text that starts at the first [start] in [bytes], at most [maxBytes] of it, or null when there is no [start]. */
    fun textFrom(bytes: ByteArray, start: String, maxBytes: Int): String? {
        val at = indexOf(bytes, start.toByteArray(Charsets.UTF_8), 0)
        if (at < 0) return null
        return String(bytes, at, minOf(maxBytes, bytes.size - at), Charsets.UTF_8)
    }
}
