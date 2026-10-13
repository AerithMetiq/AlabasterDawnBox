package io.github.aerithmetiq.alabasterdroid.steam

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
        assertEquals("", GameDownload.terraPrefix(listOf("package.json", "terra/index.html", "terra/dist/bundle.js")))
        assertEquals("package.nw/", GameDownload.terraPrefix(listOf("package.nw\\terra\\index.html", "nw.exe")))
        assertNull(GameDownload.terraPrefix(listOf("nw.exe", "locales/en.pak", "subterra/index.html")))
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
}
