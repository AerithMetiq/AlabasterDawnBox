package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeReportTest {

    /** A leaf: `name "value"`. */
    private fun leaf(name: String, value: String) = KeyValue(name, value)

    /** A node: `name { children }`. */
    private fun node(name: String, vararg children: KeyValue) =
        KeyValue(name).apply { this.children.addAll(children) }

    /* An app tree in the shape PICS returns, with both manifest spellings and the non-depot keys
     * (`branches`, `baselanguages`) that sit beside the depots. */
    private val app = node(
        "3110760",
        node(
            "depots",
            node(
                "3110762",
                node("config", leaf("oslist", "linux"), leaf("osarch", "64")),
                node("manifests", node("public", leaf("gid", "222"), leaf("size", "1048576"), leaf("download", "500000"))),
                leaf("maxsize", "9999999"),
            ),
            node(
                "3110761",
                node("config", leaf("oslist", "windows"), leaf("language", "")),
                node("manifests", leaf("public", "111")),
                leaf("maxsize", "2147483648"),
                leaf("sharedinstall", "1"),
            ),
            leaf("baselanguages", "english"),
            node(
                "branches",
                node("beta", leaf("buildid", "20"), leaf("pwdrequired", "1")),
                node("public", leaf("buildid", "19"), leaf("timeupdated", "1778112000")),
            ),
        ),
        node("ufs", leaf("quota", "1000000"), node("savefiles", node("0", leaf("root", "WinAppDataLocal")))),
    )

    @Test
    fun depotsAreNumericChildrenInIdOrder() {
        val depots = ProbeReport.depots(app)
        assertEquals(listOf(3110761, 3110762), depots.map { it.id })
    }

    @Test
    fun bothManifestShapesAreRead() {
        val (old, new) = ProbeReport.depots(app)
        assertEquals("111", old.publicManifest)
        assertNull(old.publicSize)
        assertEquals("222", new.publicManifest)
        assertEquals(1048576L, new.publicSize)
    }

    @Test
    fun depotFieldsAndLine() {
        val (win, linux) = ProbeReport.depots(app)
        assertEquals("windows", win.oslist)
        assertNull("an empty language is no language", win.language)
        assertTrue(win.sharedInstall)
        assertEquals(
            "depot 3110761 os=windows maxsize=2.00 GiB manifest=111 shared",
            ProbeReport.depotLine(win),
        )
        assertEquals(
            "depot 3110762 os=linux arch=64 size=1.0 MiB manifest=222",
            ProbeReport.depotLine(linux),
        )
    }

    @Test
    fun anAppWithoutDepotsHasNone() {
        assertEquals(emptyList<DepotSummary>(), ProbeReport.depots(node("1")))
    }

    @Test
    fun branchesPublicFirst() {
        assertEquals(
            listOf(
                "branch public build=19 updated=2026-05-07T00:00:00Z",
                "branch beta build=20 password",
            ),
            ProbeReport.branches(app),
        )
    }

    @Test
    fun treeIndentsAndCaps() {
        assertEquals(
            listOf("quota: 1000000", "savefiles:", "  0:", "    root: WinAppDataLocal"),
            ProbeReport.tree(app["ufs"]),
        )
        val capped = ProbeReport.tree(app["ufs"], maxLines = 2)
        assertEquals(3, capped.size)
        assertTrue(capped.last().startsWith("…"))
        assertEquals("a tree that fits exactly is not marked as cut", 4, ProbeReport.tree(app["ufs"], maxLines = 4).size)
    }

    @Test
    fun platformBits() {
        assertEquals("all", ProbeReport.platforms(-1))
        assertEquals("none", ProbeReport.platforms(0))
        assertEquals("Windows+Linux", ProbeReport.platforms(1 or 8))
        assertEquals("Android+0x80", ProbeReport.platforms(32 or 128))
    }

    @Test
    fun cloudLinesNewestFirst() {
        val lines = ProbeReport.cloudLines(
            listOf(
                CloudFile("%WinAppDataLocal%", "Saves/Default/Save_ID_0000.save", 2048, 1778112000, 1),
                CloudFile(null, "cc.save", 500, 1778198400, -1),
            )
        )
        assertEquals(
            listOf(
                "cc.save  500 B  2026-05-08T00:00:00Z  all",
                "%WinAppDataLocal%Saves/Default/Save_ID_0000.save  2.0 KiB  2026-05-07T00:00:00Z  Windows",
            ),
            lines,
        )
    }

    @Test
    fun sizes() {
        assertEquals("0 B", ProbeReport.size(0))
        assertEquals("1.5 KiB", ProbeReport.size(1536))
        assertEquals("1.50 GiB", ProbeReport.size(3L shl 29))
    }
}
