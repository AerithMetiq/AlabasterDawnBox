package io.github.aerithmetiq.alabasterdawnbox

import android.content.Context
import io.github.aerithmetiq.alabasterdawnbox.steam.GameDownload
import java.io.File

/**
 * Where the downloaded game lives: the app's own external files folder, so it can also be reached
 * over USB (Android/data/io.github.aerithmetiq.alabasterdawnbox/files/AlabasterDawn) without any storage
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

    /** The Steam build id of the installed game, from the marker; null when unknown or not installed. */
    fun installedBuild(context: Context): String? =
        runCatching { GameDownload.parseMarker(File(root(context), GameDownload.MARKER).readText())["build"] }.getOrNull()

    /** How much storage the game's files take, in bytes (walks the folder; call off the main thread). */
    fun gameBytes(context: Context): Long =
        File(root(context), "terra").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes the downloaded game. Saves, their backups and the settings are kept. */
    fun deleteGame(context: Context) {
        File(root(context), GameDownload.MARKER).delete()
        File(root(context), "terra").deleteRecursively()
    }

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

    /**
     * Whether Steam shows the account as playing Alabaster Dawn while the game runs. Off until the
     * user turns it on in Settings: it writes to Steam (friends see it, play time is counted).
     */
    fun playingStatus(context: Context): Boolean = prefs(context).getBoolean(KEY_PLAYING, false)

    fun setPlayingStatus(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_PLAYING, on).apply()

    /** The public build id Steam last reported, kept so an update is still offered when offline. */
    fun steamBuild(context: Context): String? = prefs(context).getString(KEY_STEAM_BUILD, null)

    fun setSteamBuild(context: Context, build: String) = prefs(context).edit().putString(KEY_STEAM_BUILD, build).apply()

    fun updateAvailable(context: Context): Boolean =
        installed(context) && GameDownload.updateAvailable(installedBuild(context), steamBuild(context))

    /** When the last save sync that reached Steam finished, in epoch milliseconds; 0 for never. */
    fun lastSync(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0)

    fun setLastSync(context: Context, at: Long) = prefs(context).edit().putLong(KEY_LAST_SYNC, at).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private const val KEY_UPLOADS = "cloudUploads"
    private const val KEY_PLAYING = "playingStatus"
    private const val KEY_STEAM_BUILD = "steamBuild"
    private const val KEY_LAST_SYNC = "lastSync"
}
