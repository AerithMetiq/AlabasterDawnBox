package io.github.aerithmetiq.alabasterdawnbox.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveBackupsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, rel: String, text: String) = File(root, rel).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun backupsAreListedNewestFirstWithTheirFiles() {
        val backups = tmp.newFolder("SaveBackups")
        write(backups, "20261012-191849/Default/System.save", "old")
        write(backups, "20261012-191849/Default/Save_ID_auto.save", "old")
        write(backups, "20261013-080000/Backups/System.save", "newer")
        File(backups, "20261014-000000").mkdirs()
        val list = SaveBackups.list(backups)
        assertEquals(listOf("20261013-080000", "20261012-191849"), list.map { it.name })
        assertEquals(listOf("Default/Save_ID_auto.save", "Default/System.save"), list[1].files)
        assertEquals("2026-10-12 19:18", list[1].label)
    }

    @Test
    fun restoringPutsTheFilesBackAndKeepsWhatTheyReplaced() {
        val saves = tmp.newFolder("Saves")
        val backups = tmp.newFolder("SaveBackups")
        write(saves, "Default/System.save", "current")
        write(saves, "Default/Save_ID_0002.save", "untouched")
        write(backups, "20261012-191849/Default/System.save", "old")
        write(backups, "20261012-191849/Default/Save_ID_auto.save", "old auto")

        assertEquals(2, SaveBackups.restore(saves, backups, "20261012-191849", stamp = "20261013-090000"))

        assertEquals("old", File(saves, "Default/System.save").readText())
        assertEquals("old auto", File(saves, "Default/Save_ID_auto.save").readText())
        assertEquals("a file the backup does not hold is left alone", "untouched", File(saves, "Default/Save_ID_0002.save").readText())
        assertEquals("the replaced file can be restored in turn", "current", File(backups, "20261013-090000/Default/System.save").readText())
        assertTrue("the backup itself is kept", File(backups, "20261012-191849/Default/System.save").isFile)
    }

    @Test
    fun steamCloudCopiesAreLabelledAndPrunedApart() {
        val backups = tmp.newFolder("SaveBackups")
        for (day in 10..19) {
            write(backups, "202610$day-120000/a.save", "here")
            write(backups, "202610$day-120000${SaveBackups.FROM_STEAM}/a.save", "steam")
        }
        SaveBackups.prune(backups)
        val list = SaveBackups.list(backups)
        assertEquals(SaveBackups.KEEP, list.count { it.fromSteam })
        assertEquals(SaveBackups.KEEP, list.count { !it.fromSteam })
        assertEquals("20261019-120000${SaveBackups.FROM_STEAM}", list.first().name)
        assertEquals("2026-10-19 12:00  ·  from Steam Cloud", list.first().label)
        assertEquals("2026-10-19 12:00", list[1].label)
        assertEquals("20261019-120000-steam", SaveBackups.steamStamp(java.time.LocalDateTime.of(2026, 10, 19, 12, 0)))
    }

    @Test
    fun aBackupThatIsNotThereRestoresNothing() {
        assertEquals(0, SaveBackups.restore(tmp.newFolder("Saves"), tmp.newFolder("SaveBackups"), "20260101-000000"))
    }
}
