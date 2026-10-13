package io.github.aerithmetiq.alabasterdroid.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSavesTest {

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    private fun f(name: String, sha: String, time: Long = 100) = SaveFile(name, sha, 1024, time)

    private fun action(local: String?, cloud: String?, base: String?): SaveAction =
        CloudSaves.plan(
            listOfNotNull(local?.let { f("Default/System.save", it) }),
            listOfNotNull(cloud?.let { f("Default/System.save", it) }),
            listOfNotNull(base?.let { "Default/System.save" to it }).toMap(),
        ).single().action

    /** Names as the device's diagnostics report listed them (2026-10-12). */
    @Test
    fun cloudNamesMapToTheSavesFolder() {
        assertEquals("Default/Save_ID_auto.save", CloudSaves.relative("%WinAppDataLocal%Default/Save_ID_auto.save"))
        assertEquals("%WinAppDataLocal%Backups2/System.save", CloudSaves.cloudName("Backups2/System.save"))
        assertNull(CloudSaves.relative("%GameInstall%Default/System.save"))
        assertNull(CloudSaves.relative("%WinAppDataLocal%../../etc/passwd"))
        assertNull(CloudSaves.relative("%WinAppDataLocal%Default//System.save"))
        assertNull(CloudSaves.relative("%WinAppDataLocal%"))
    }

    @Test
    fun theSideThatChangedSinceTheLastSyncWins() {
        assertEquals(SaveAction.SAME, action(a, a, null))
        assertEquals(SaveAction.DOWNLOAD, action(a, b, a))
        assertEquals(SaveAction.UPLOAD, action(b, a, a))
        assertEquals(SaveAction.CONFLICT, action(b, c, a))
    }

    @Test
    fun aFirstSyncWithDifferentSavesOnBothSidesIsAConflict() {
        assertEquals(SaveAction.CONFLICT, action(a, b, null))
    }

    @Test
    fun filesOnOneSideOnly() {
        assertEquals(SaveAction.DOWNLOAD, action(null, a, null))
        assertEquals(SaveAction.UPLOAD, action(a, null, null))
        /* Steam Cloud deleted a file this device left alone. */
        assertEquals(SaveAction.REMOVE_LOCAL, action(a, null, a))
        /* Changed here after Steam Cloud deleted it: the change is kept. */
        assertEquals(SaveAction.UPLOAD, action(b, null, a))
        /* Deleted here: left in Steam Cloud, and not fetched again until Steam Cloud changes it. */
        assertEquals(SaveAction.DELETED_HERE, action(null, a, a))
        assertEquals(SaveAction.DOWNLOAD, action(null, b, a))
    }

    @Test
    fun conflictsAreSettledOneWayForEveryFile() {
        val steps = CloudSaves.plan(
            listOf(f("Default/System.save", a), f("Default/Save_ID_auto.save", a), f("Default/Save_ID_0002.save", a)),
            listOf(f("Default/System.save", b), f("Default/Save_ID_auto.save", b), f("Backups/System.save", b)),
            emptyMap(),
        )
        assertEquals(2, CloudSaves.count(steps, SaveAction.CONFLICT))
        val cloud = CloudSaves.resolve(steps, cloudWins = true)
        assertEquals(3, CloudSaves.count(cloud, SaveAction.DOWNLOAD))
        assertEquals(1, CloudSaves.count(cloud, SaveAction.UPLOAD))
        val here = CloudSaves.resolve(steps, cloudWins = false)
        assertEquals(1, CloudSaves.count(here, SaveAction.DOWNLOAD))
        assertEquals(3, CloudSaves.count(here, SaveAction.UPLOAD))
    }

    @Test
    fun theBaseRecordsOnlyWhatWasCarriedOut() {
        val steps = CloudSaves.plan(
            listOf(f("same", a), f("down", a), f("up", b), f("gone", a)),
            listOf(f("same", a), f("down", b), f("up", a)),
            mapOf("down" to a, "up" to a, "gone" to a),
        )
        assertEquals(
            listOf(SaveAction.DOWNLOAD, SaveAction.REMOVE_LOCAL, SaveAction.SAME, SaveAction.UPLOAD),
            steps.map { it.action },
        )
        /* Uploads off: the download and the removal happened, the upload did not. */
        val base = CloudSaves.newBase(mapOf("down" to a, "up" to a, "gone" to a), steps, setOf("down", "gone"))
        assertEquals(mapOf("same" to a, "down" to b, "up" to a), base)
        /* So next time "up" is still changed here only: an upload, not a conflict. */
        val next = CloudSaves.plan(listOf(f("same", a), f("down", b), f("up", b)), listOf(f("same", a), f("down", b), f("up", a)), base)
        assertEquals(listOf(SaveAction.SAME, SaveAction.SAME, SaveAction.UPLOAD), next.map { it.action })
        assertEquals(mapOf("same" to a, "down" to b, "up" to b), CloudSaves.newBase(base, next, setOf("up")))
    }

    /** Keeping this device's saves with uploads off must not ask the same question on every PLAY. */
    @Test
    fun keepingThisDevicesSavesIsRememberedUntilSteamCloudChanges() {
        val local = listOf(f("Default/System.save", a))
        val steps = CloudSaves.resolve(CloudSaves.plan(local, listOf(f("Default/System.save", b)), emptyMap()), cloudWins = false)
        val base = CloudSaves.newBase(emptyMap(), steps, done = emptySet(), keptLocal = setOf("Default/System.save"))
        assertEquals(mapOf("Default/System.save" to b), base)
        assertEquals(SaveAction.UPLOAD, CloudSaves.plan(local, listOf(f("Default/System.save", b)), base).single().action)
        assertEquals(SaveAction.CONFLICT, CloudSaves.plan(local, listOf(f("Default/System.save", c)), base).single().action)
    }

    @Test
    fun theBaseFileRoundTrips() {
        val base = mapOf("Default/System.save" to a, "Default/My Save 1.save" to b)
        assertEquals(base, CloudSaves.parseBase(CloudSaves.formatBase(base)))
        assertEquals(emptyMap<String, String>(), CloudSaves.parseBase("garbage\n\nshort name\n"))
    }

    @Test
    fun conflictTimesAreTheNewestOnEachSide() {
        val steps = CloudSaves.plan(
            listOf(f("x", a, 500), f("y", a, 900), f("same", c, 5000)),
            listOf(f("x", b, 700), f("y", b, 300), f("same", c, 6000)),
            emptyMap(),
        )
        assertEquals(900L to 700L, CloudSaves.conflictTimes(steps))
    }

    @Test
    fun summaries() {
        assertEquals("Your saves match Steam Cloud.", CloudSaves.summary(0, 0, 0, true, null))
        assertEquals("Downloaded 9 save files from Steam Cloud.", CloudSaves.summary(9, 0, 0, false, null))
        assertEquals(
            "Downloaded 1 save file from Steam Cloud. Uploaded 2 save files to Steam Cloud.",
            CloudSaves.summary(1, 2, 0, true, null),
        )
        assertTrue(CloudSaves.summary(0, 0, 3, false, null).startsWith("3 save files changed on this device and not uploaded"))
        assertTrue(CloudSaves.summary(0, 1, 2, true, "HTTP 500 sending a block").contains("Couldn't upload 2 save files (HTTP 500"))
    }

    /** The report is meant to be shared: it must say plainly when nothing would be sent. */
    @Test
    fun theReportSaysWhatWouldHappen() {
        val steps = CloudSaves.plan(listOf(f("Default/System.save", b, 1791800000)), listOf(f("Default/System.save", a, 1791340031)), mapOf("Default/System.save" to a))
        val off = CloudSaves.report(steps, uploads = false)
        assertEquals("Default/System.save: changed here; NOT uploaded (uploads are off)", off[0])
        assertEquals("    cloud: 1.0 KiB  2026-10-07 02:27Z  sha aaaaaaaa", off[2])
        assertEquals("Default/System.save: would UPLOAD to Steam Cloud", CloudSaves.report(steps, uploads = true)[0])
    }

    @Test
    fun theStandingLineSaysWhenTheSavesWereLastSyncedAndWhatChangedSince() {
        val now = 1_000_000_000_000L
        assertEquals("Not synced with Steam Cloud yet", CloudSaves.syncStatus(now, 0, 0))
        assertEquals("Synced with Steam Cloud just now", CloudSaves.syncStatus(now, now - 20_000, 0))
        assertEquals("Synced with Steam Cloud 1 minute ago", CloudSaves.syncStatus(now, now - 61_000, 0))
        assertEquals("Synced with Steam Cloud 5 minutes ago · 3 save files changed here since", CloudSaves.syncStatus(now, now - 5 * 60_000, 3))
        assertEquals("Synced with Steam Cloud 3 hours ago · 1 save file changed here since", CloudSaves.syncStatus(now, now - 3 * 3_600_000L, 1))
        assertEquals("Synced with Steam Cloud 4 days ago", CloudSaves.syncStatus(now, now - 4 * 86_400_000L, 0))
    }

    @Test
    fun changedSinceCountsFilesThatDifferFromTheBaseOrAreNew() {
        val local = listOf(f("same", a), f("edited", b), f("new", c))
        assertEquals(2, CloudSaves.changedSince(local, mapOf("same" to a, "edited" to a, "gone" to a)))
        assertEquals(3, CloudSaves.changedSince(local, emptyMap()))
    }
}
