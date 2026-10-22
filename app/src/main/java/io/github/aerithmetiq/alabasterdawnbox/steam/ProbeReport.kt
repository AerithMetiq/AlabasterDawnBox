package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.types.KeyValue
import java.time.Instant
import java.util.Locale

/** The games the Steam probe asks about, by their Steam app id. */
enum class SteamGame(val appId: Int, val displayName: String) {
    ALABASTER_DAWN(3110760, "Alabaster Dawn"),
    CROSSCODE(368340, "CrossCode"),
}

/** One depot of an app, as PICS describes it. Every field but [id] is optional in PICS. */
data class DepotSummary(
    val id: Int,
    val name: String?,
    val oslist: String?,
    val osarch: String?,
    val language: String?,
    val maxSize: Long?,
    val publicManifest: String?,
    val publicSize: Long?,
    val fromApp: Int?,
    val sharedInstall: Boolean,
    val dlcAppId: Int?,
)

/**
 * One Steam Cloud file. [prefix] is the root the file is filed under (a token such as
 * `%WinAppDataLocal%`), when Steam sends one; [platforms] is the ERemoteStoragePlatform bit set.
 */
data class CloudFile(
    val prefix: String?,
    val name: String,
    val size: Long,
    val modifiedEpochSec: Long,
    val platforms: Int,
)

/**
 * Turns what Steam returns into the probe's report lines. Pure: no Android, no network, so every
 * shape PICS has been seen to use can be unit-tested.
 */
object ProbeReport {

    /** The depots of an app's PICS tree, in id order. Non-numeric children (branches, …) are skipped. */
    fun depots(app: KeyValue): List<DepotSummary> =
        app["depots"].children.mapNotNull { d ->
            val id = d.name?.toIntOrNull() ?: return@mapNotNull null
            val config = d["config"]
            /* Two shapes are in use: `manifests { public { gid size download } }` and the older
             * `manifests { public "<gid>" }`. */
            val public = d["manifests"]["public"]
            val manifest = if (public.children.isEmpty()) public.value else public["gid"].value
            val manifestSize = if (public.children.isEmpty()) null else public["size"].value?.toLongOrNull()
            DepotSummary(
                id = id,
                name = d["name"].value,
                oslist = config["oslist"].value,
                osarch = config["osarch"].value,
                language = config["language"].value?.takeIf { it.isNotEmpty() },
                maxSize = d["maxsize"].value?.toLongOrNull(),
                publicManifest = manifest,
                publicSize = manifestSize,
                fromApp = d["depotfromapp"].value?.toIntOrNull(),
                sharedInstall = d["sharedinstall"].value == "1",
                dlcAppId = d["dlcappid"].value?.toIntOrNull(),
            )
        }.sortedBy { it.id }

    fun depotLine(d: DepotSummary): String = buildString {
        append("depot ").append(d.id)
        d.name?.let { append(" \"").append(it).append('"') }
        append(" os=").append(d.oslist ?: "any")
        d.osarch?.let { append(" arch=").append(it) }
        d.language?.let { append(" lang=").append(it) }
        d.publicSize?.let { append(" size=").append(size(it)) }
        if (d.publicSize == null) d.maxSize?.let { append(" maxsize=").append(size(it)) }
        append(" manifest=").append(d.publicManifest ?: "none")
        d.fromApp?.let { append(" from-app=").append(it) }
        if (d.sharedInstall) append(" shared")
        d.dlcAppId?.let { append(" dlc=").append(it) }
    }

    /** The app's branches with their build id and update time, `public` first. */
    fun branches(app: KeyValue): List<String> =
        app["depots"]["branches"].children
            .sortedWith(compareBy({ it.name != "public" }, { it.name }))
            .map { b ->
                buildString {
                    append("branch ").append(b.name)
                    b["buildid"].value?.let { append(" build=").append(it) }
                    b["timeupdated"].value?.toLongOrNull()?.let { append(" updated=").append(utc(it)) }
                    if (b["pwdrequired"].value == "1") append(" password")
                    b["description"].value?.takeIf { it.isNotEmpty() }?.let { append(" \"").append(it).append('"') }
                }
            }

    /**
     * A KeyValue subtree as indented `key: value` lines (the app's Steam Cloud rules live in `ufs`).
     * Capped, because a report is read on a phone.
     */
    fun tree(kv: KeyValue, maxLines: Int = 120): List<String> {
        val out = ArrayList<String>()
        var cut = false
        fun walk(node: KeyValue, depth: Int) {
            if (out.size >= maxLines) {
                cut = true
                return
            }
            val indent = "  ".repeat(depth)
            if (node.children.isEmpty()) {
                out.add("$indent${node.name}: ${node.value ?: ""}")
            } else {
                out.add("$indent${node.name}:")
                for (c in node.children) walk(c, depth + 1)
            }
        }
        for (c in kv.children) walk(c, 0)
        if (cut) out.add("… (cut at $maxLines lines)")
        return out
    }

    /** Names the ERemoteStoragePlatform bits (Windows 1, OSX 2, PS3 4, Linux 8, Switch 16, Android 32, iOS 64). */
    fun platforms(bits: Int): String {
        if (bits == -1) return "all"
        if (bits == 0) return "none"
        val names = listOf(1 to "Windows", 2 to "macOS", 4 to "PS3", 8 to "Linux", 16 to "Switch", 32 to "Android", 64 to "iOS")
        val known = names.filter { bits and it.first != 0 }.map { it.second }
        val rest = bits and names.sumOf { it.first }.inv()
        return (known + if (rest != 0) listOf("0x" + Integer.toHexString(rest)) else emptyList()).joinToString("+")
    }

    /** Cloud files, newest first: `<prefix><name>  <size>  <modified UTC>  <platforms>`. */
    fun cloudLines(files: List<CloudFile>): List<String> =
        files.sortedByDescending { it.modifiedEpochSec }.map { f ->
            "${f.prefix ?: ""}${f.name}  ${size(f.size)}  ${utc(f.modifiedEpochSec)}  ${platforms(f.platforms)}"
        }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KiB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun utc(epochSec: Long): String = Instant.ofEpochSecond(epochSec).toString()
}
