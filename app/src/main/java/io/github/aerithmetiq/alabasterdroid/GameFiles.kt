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

    /** The game's own title art, used behind the launcher once the game is downloaded. Never bundled. */
    fun titleArt(context: Context): File? =
        File(root(context), "terra/media/gui/title/title-bg-01.png").takeIf { it.isFile }
}
