package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.types.KeyValue

/** Where one Steam achievement lives: bit [bit] of the 32-bit stat [statId]. */
data class AchievementBit(val statId: Int, val bit: Int)

/**
 * The decisions of unlocking Steam achievements, kept apart from Steam so they can be unit-tested.
 *
 * Steam keeps a game's achievements as bits of integer stats ("achievement blocks"). The game's stats
 * schema, which Steam sends with the user's stats, names each bit: `stats/<stat id>/bits/<n>` holds the
 * achievement's API `name`, and `<n>` is the bit (CrossCode's schema, read on 2026-10-21: three stats
 * of type ACHIEVEMENTS with 31, 32 and 23 bits; some schemas also carry an explicit `bit`). Unlocking an achievement is storing its stat with that bit
 * set; nothing here ever clears a bit.
 */
object Achievements {

    /**
     * The schema's `stats` section: under the root, or one level down, under the app's own key, which
     * is where the binary schema Steam sends keeps it.
     */
    fun statsOf(schema: KeyValue): KeyValue {
        schema["stats"].takeIf { it.children.isNotEmpty() }?.let { return it }
        for (child in schema.children) child["stats"].takeIf { it.children.isNotEmpty() }?.let { return it }
        return schema["stats"]
    }

    /** Every achievement in [schema] (the root of the stats schema), by its API name. */
    fun bits(schema: KeyValue): Map<String, AchievementBit> {
        val out = LinkedHashMap<String, AchievementBit>()
        for (stat in statsOf(schema).children) {
            val statId = stat.name?.toIntOrNull() ?: continue
            for (entry in stat["bits"].children) {
                val name = entry["name"].value?.takeIf { it.isNotBlank() } ?: continue
                val bit = entry["bit"].value?.toIntOrNull() ?: entry.name?.toIntOrNull() ?: continue
                if (bit in 0..31) out[name] = AchievementBit(statId, bit)
            }
        }
        return out
    }

    fun unlocked(name: String, bits: Map<String, AchievementBit>, values: Map<Int, Int>): Boolean {
        val at = bits[name] ?: return false
        return ((values[at.statId] ?: 0) and (1 shl at.bit)) != 0
    }

    /**
     * The stats to store so that [names] are all unlocked: stat id to its new value, holding only the
     * stats that change. Names the schema does not know are left out (see [unknown]).
     */
    fun toStore(names: Collection<String>, bits: Map<String, AchievementBit>, values: Map<Int, Int>): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for (name in names) {
            val at = bits[name] ?: continue
            val before = out[at.statId] ?: values[at.statId] ?: 0
            val after = before or (1 shl at.bit)
            if (after != (values[at.statId] ?: 0)) out[at.statId] = after
        }
        return out
    }

    /** The names in [names] that are not achievements of this game. */
    fun unknown(names: Collection<String>, bits: Map<String, AchievementBit>): List<String> =
        names.filter { it !in bits }

    /** The achievement names in [text], one per line; blank lines are skipped and repeats dropped. */
    fun parseQueue(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun formatQueue(names: Collection<String>): String = names.distinct().joinToString("") { "$it\n" }
}
