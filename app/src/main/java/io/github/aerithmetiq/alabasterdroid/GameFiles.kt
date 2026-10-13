package io.github.aerithmetiq.alabasterdroid

import android.content.Context
import io.github.aerithmetiq.alabasterdroid.steam.GameDownload
import java.io.File

/**
 * Where the downloaded game lives: the app's own external files folder, so it can also be reached
 * over USB (Android/data/io.github.aerithmetiq.alabasterdroid/files/AlabasterDawn) without any storage
 * permission. Only the web game (`terra/`) is kept; the desktop runtime around it is not needed.
 */
object GameFiles {

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "AlabasterDawn")

    /**
     * Installed means a download ran to the end (it leaves [GameDownload.MARKER]) and the engine's entry
     * page is there. A download that was interrupted is not installed; PLAY carries it on.
     */
    fun installed(context: Context): Boolean =
        File(root(context), GameDownload.MARKER).isFile && File(root(context), GameDownload.ENTRY).isFile

    /*
     * The game's own title-screen art, drawn on the launcher once the game is downloaded. It is read
     * from the user's copy at runtime and never bundled; a build of the game that moves a file simply
     * gets the launcher's own look for that part.
     */

    /** The backdrop, behind the whole launcher. */
    fun titleBackground(context: Context): File? = titleFile(context, "title-color-01.png")

    /** The game's name as the title screen draws it, shown in place of the "ALABASTER / DROID" wordmark. */
    fun titleName(context: Context): File? = titleFile(context, "title-name-01.png")

    private fun titleFile(context: Context, name: String): File? =
        File(root(context), "terra/media/gui/title/$name").takeIf { it.isFile }

    /*
     * Saves. The host gives the engine `userdata/` as its save root, so the game's `Saves/` folder
     * (the folder Steam Cloud mirrors) is `userdata/Saves/`. Everything the sync keeps for itself is
     * outside it, and outside `terra/`, which the downloader cleans.
     */

    fun saves(context: Context): File = File(root(context), "userdata/Saves")

    /** The checksums of the last sync (see steam/CloudSaves). */
    fun syncBase(context: Context): File = File(root(context), "cloud-sync.txt")

    /** Local saves a sync replaced or removed, a folder per sync, newest few kept. */
    fun saveBackups(context: Context): File = File(root(context), "SaveBackups")

    /**
     * Whether this device's saves are uploaded to Steam Cloud. Off until the user turns it on in
     * Settings: writing to Steam is deliberate. Downloads do not depend on it.
     */
    fun cloudUploads(context: Context): Boolean = prefs(context).getBoolean(KEY_UPLOADS, false)

    fun setCloudUploads(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_UPLOADS, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private const val KEY_UPLOADS = "cloudUploads"
}
