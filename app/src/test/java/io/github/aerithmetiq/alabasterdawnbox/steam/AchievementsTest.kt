package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    private fun kv(name: String, value: String? = null, vararg children: KeyValue) =
        KeyValue(name, value).also { it.children.addAll(children) }

    private fun bit(index: Int, name: String, bit: Int) =
        kv(index.toString(), null, kv("name", name), kv("bit", bit.toString()), kv("display"))

    /** The shape Steam sends: stats/<id>/bits/<n> with name and bit; a plain stat has no bits. */
    private val schema = kv(
        "368340", null,
        kv(
            "stats", null,
            kv("1", null, kv("type", "4"), kv("bits", null, bit(0, "TROPHY_A", 0), bit(1, "TROPHY_B", 1), bit(2, "TROPHY_C", 5))),
            kv("2", null, kv("type", "4"), kv("bits", null, bit(0, "TROPHY_D", 0))),
            kv("7", null, kv("type", "1"), kv("name", "kills")),
        ),
    )

    @Test
    fun `the schema names each achievement's stat and bit`() {
        val bits = Achievements.bits(schema)
        assertEquals(
            mapOf(
                "TROPHY_A" to AchievementBit(1, 0), "TROPHY_B" to AchievementBit(1, 1),
                "TROPHY_C" to AchievementBit(1, 5), "TROPHY_D" to AchievementBit(2, 0),
            ),
            bits,
        )
    }

    /** CrossCode's own layout: the entry's key is the bit, and there is no `bit` field. */
    @Test
    fun `the bit is the entry's key when it has no bit field`() {
        val crosscode = kv(
            "368340", null,
            kv("stats", null, kv("2", null, kv("type", "ACHIEVEMENTS"), kv("bits", null,
                kv("0", null, kv("name", "enviro-kills_1"), kv("display")),
                kv("7", null, kv("name", "enviro-kills_2"), kv("display")),
            ))),
        )
        assertEquals(
            mapOf("enviro-kills_1" to AchievementBit(2, 0), "enviro-kills_2" to AchievementBit(2, 7)),
            Achievements.bits(crosscode),
        )
    }

    @Test
    fun `the stats section is found under the app's own key too`() {
        val wrapped = kv("", null, schema)
        assertEquals(Achievements.bits(schema), Achievements.bits(wrapped))
    }

    @Test
    fun `unlocking sets only the missing bits, a stat at a time`() {
        val bits = Achievements.bits(schema)
        val values = mapOf(1 to 0b1, 7 to 99)
        assertTrue(Achievements.unlocked("TROPHY_A", bits, values))
        assertFalse(Achievements.unlocked("TROPHY_B", bits, values))
        assertEquals(mapOf(1 to 0b100011), Achievements.toStore(listOf("TROPHY_B", "TROPHY_C", "TROPHY_A"), bits, values))
        assertEquals(mapOf(1 to 0b11, 2 to 0b1), Achievements.toStore(listOf("TROPHY_B", "TROPHY_D"), bits, values))
    }

    @Test
    fun `nothing is stored when every achievement is already unlocked, or unknown`() {
        val bits = Achievements.bits(schema)
        val values = mapOf(1 to 0b100011, 2 to 1)
        assertEquals(emptyMap<Int, Int>(), Achievements.toStore(listOf("TROPHY_A", "TROPHY_C", "TROPHY_D", "NOT_A_TROPHY"), bits, values))
        assertEquals(listOf("NOT_A_TROPHY"), Achievements.unknown(listOf("TROPHY_A", "NOT_A_TROPHY"), bits))
    }

    @Test
    fun `a stat with the top bit set stays a plain int`() {
        val bits = mapOf("TOP" to AchievementBit(3, 31))
        assertEquals(mapOf(3 to (Int.MIN_VALUE or 1)), Achievements.toStore(listOf("TOP"), bits, mapOf(3 to 1)))
    }

    @Test
    fun `the queue file is one name per line without repeats`() {
        assertEquals(listOf("A", "B"), Achievements.parseQueue("A\n\n B \nA\n"))
        assertEquals("A\nB\n", Achievements.formatQueue(listOf("A", "B", "A")))
        assertEquals(emptyList<String>(), Achievements.parseQueue(""))
    }
}
