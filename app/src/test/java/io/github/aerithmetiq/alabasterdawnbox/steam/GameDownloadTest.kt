package io.github.aerithmetiq.alabasterdawnbox.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameDownloadTest {

    private fun depot(
        id: Int,
        os: String?,
        manifest: String? = "1",
        fromApp: Int? = null,
        dlc: Int? = null,
    ) = DepotSummary(
        id = id, name = null, oslist = os, osarch = null, language = null, maxSize = null,
        publicManifest = manifest, publicSize = null, fromApp = fromApp, sharedInstall = false, dlcAppId = dlc,
    )

    /** Alabaster Dawn's depots as the device's diagnostics report listed them (2026-10-12). */
    @Test
    fun candidatesPutTheDepotSharedByEveryPlatformFirst() {
        val depots = listOf(
            depot(3110762, "windows"),
            depot(3110761, "windows,linux", manifest = "8847532658958186681"),
            depot(3110764, "linux"),
        )
        assertEquals(listOf(3110761, 3110762, 3110764), GameDownload.candidates(depots, "windows,linux").map { it.id })
    }

    /** CrossCode's depots (CrossCodeBox, 2026-10-21): the same rule serves both apps. */
    @Test
    fun extrasAreTheOtherDepotsForEveryPlatformDlcIncluded() {
        val depots = listOf(
            depot(228987, "windows", manifest = null, fromApp = 228980),
            depot(368341, "windows"),
            depot(368342, "linux"),
            depot(916190, null),
            depot(916191, "macos", dlc = 960310),
            depot(960310, null, dlc = 960310),
        )
        assertEquals(listOf(916190, 960310), GameDownload.extras(depots, main = 368341).map { it.id })
    }

    /** Alabaster Dawn's depots are all tied to a platform: nothing is added. */
    @Test
    fun alabasterDawnHasNoExtras() {
        val depots = listOf(depot(3110761, "windows,linux"), depot(3110762, "windows"), depot(3110764, "linux"))
        assertEquals(emptyList<Int>(), GameDownload.extras(depots, main = 3110761).map { it.id })
    }

    @Test
    fun theModsFolderIsKeptThroughADownload() {
        val root = GameDownload.WEB_ROOT
        assertEquals(true, GameDownload.kept("$root/mods/my-mod/ccmod.json"))
        assertEquals(false, GameDownload.kept("$root/modsx/a"))
        assertEquals(false, GameDownload.kept("$root/data/a.json"))
    }

    @Test
    fun aLaterDepotsFileReplacesAnEarlierOnesAtTheSamePath() {
        val (merged, replaced) = GameDownload.merge(
            listOf(mapOf("terra/a.json" to "main", "terra/b.png" to "main"), mapOf("terra/a.json" to "dlc"))
        )
        assertEquals(mapOf("terra/a.json" to "dlc", "terra/b.png" to "main"), merged)
        assertEquals(1, replaced)
    }

    @Test
    fun candidatesLeaveOutDepotsThatCannotBeDownloadedForThisApp() {
        val depots = listOf(
            depot(228987, "windows", manifest = null, fromApp = 228980),
            depot(368346, "linux", manifest = null),
            depot(960310, null, dlc = 960310),
            depot(916190, null),
            depot(368341, "windows"),
        )
        assertEquals(listOf(916190, 368341), GameDownload.candidates(depots, "windows,macos,linux").map { it.id })
    }

    @Test
    fun terraPrefixFindsTheEntryPageWhereverTheDepotKeepsIt() {
        assertEquals("", GameDownload.webPrefix(listOf("package.json", "terra/index.html", "terra/dist/bundle.js")))
        assertEquals("package.nw/", GameDownload.webPrefix(listOf("package.nw\\terra\\index.html", "nw.exe")))
        assertNull(GameDownload.webPrefix(listOf("nw.exe", "locales/en.pak", "subterra/index.html")))
    }

    @Test
    fun targetKeepsOnlyPlainPathsUnderTerra() {
        assertEquals("terra/dist/bundle.js", GameDownload.target("terra\\dist\\bundle.js", ""))
        assertEquals("terra/index.html", GameDownload.target("package.nw/terra/index.html", "package.nw/"))
        assertNull(GameDownload.target("package.json", ""))
        assertNull(GameDownload.target("terra/index.html", "package.nw/"))
        assertNull(GameDownload.target("terra/../../escape.txt", ""))
        assertNull(GameDownload.target("terra//double.txt", ""))
    }

    @Test
    fun progressIsReportedInMebibytesAndWholePercent() {
        assertEquals(0, GameDownload.percent(0, 0))
        assertEquals(50, GameDownload.percent(512, 1024))
        assertEquals(100, GameDownload.percent(2048, 1024))
        assertEquals(
            "Downloading Alabaster Dawn: 1.5 of 3.0 MiB (50%)",
            GameDownload.progressLine(1572864, 3145728),
        )
    }

    @Test
    fun theMarkerIsReadBackAsKeysAndValues() {
        val marker = GameDownload.parseMarker("app=3110760\ndepot=3110761\nmanifest=8847532658958186681\nbuild=25724722\n")
        assertEquals("25724722", marker["build"])
        assertEquals("3110761", marker["depot"])
        assertEquals(emptyMap<String, String>(), GameDownload.parseMarker("\nnot a marker\n"))
    }

    @Test
    fun anUpdateIsOfferedOnlyWhenBothBuildsAreKnownAndDiffer() {
        assertEquals(true, GameDownload.updateAvailable("25724722", "25800000"))
        assertEquals(false, GameDownload.updateAvailable("25724722", "25724722"))
        assertEquals("Steam was not asked yet", false, GameDownload.updateAvailable("25724722", null))
        assertEquals("an old marker without a build", false, GameDownload.updateAvailable("unknown", "25800000"))
        assertEquals(false, GameDownload.updateAvailable(null, "25800000"))
    }

    @Test
    fun `files are taken from the big end and the small end in turn`() {
        val sizes = listOf(5L, 100L, 1L, 50L, 7L, 3L)
        assertEquals(listOf(100L, 1L, 50L, 3L, 7L, 5L), GameDownload.interleaveBySize(sizes) { it })
        assertEquals(listOf(9L), GameDownload.interleaveBySize(listOf(9L)) { it })
        assertEquals(emptyList<Long>(), GameDownload.interleaveBySize(emptyList<Long>()) { it })
        val many = (1L..101L).toList().shuffled()
        assertEquals("nothing lost or repeated", many.sorted(), GameDownload.interleaveBySize(many) { it }.sorted())
    }
}
