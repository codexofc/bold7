package dev.bold7.launcher.setup

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.Settings
import android.util.Log
import dev.bold7.theme.ThemeImages
import java.io.File
import java.io.IOException

/** The global setting that says the wizard was shown; the module's removal clears every bold7_* setting. */
const val SETUP_DONE = "bold7_setup_done"
private const val TAG = "Bold7Setup"

/**
 * The device side of the Setup Wizard: blocking, off the main thread, unless said. Writes only on
 * the user's gesture; with [dryRun] (tests, captures) it says in the log what it would write and
 * writes nothing. Nothing runs as root: the launcher's own permissions (WRITE_SECURE_SETTINGS for
 * the setting, SET_WALLPAPER), and Android's own pages for the roles.
 */
class WizardDevice(private val context: Context, private val dryRun: Boolean) {
    private val texts = TextSetupDevice(context)

    fun done(): Boolean = Settings.Global.getInt(context.contentResolver, SETUP_DONE, 0) == 1

    fun markDone() {
        if (dryRun) {
            Log.i(TAG, "dry run: $SETUP_DONE not written")
            return
        }
        Settings.Global.putInt(context.contentResolver, SETUP_DONE, 1)
    }

    /** The phone already went through the earlier text screen (an update over a set-up phone). */
    fun textScreenAsked(): Boolean = texts.asked()

    fun assetsMissing(): Boolean {
        // The lookup by name is the point: a public APK has no compiled image of the firmware.
        @Suppress("DiscouragedApi")
        val apk = context.resources.getIdentifier("ui_popup", "drawable", context.packageName) != 0
        return assetsMissing(ThemeImages.ROOT.isDirectory, apk)
    }

    fun messagesInstalled() = texts.messagesInstalled()

    fun consent() = texts.consent()

    /** Google's page of the switch (TextSetupDevice); in a dry run, nothing opens: false. Main thread. */
    fun openTexts(from: Activity, opened: (Boolean) -> Unit) {
        if (dryRun) {
            Log.i(TAG, "dry run: Google Messages' page not opened")
            return opened(false)
        }
        texts.openPage(from, opened)
    }

    /**
     * The 9900's wallpapers: the extracted ones, else those tools/fonds.sh copies in development.
     * Ponytail: two fixed folders; a third place is added here when the module puts them elsewhere.
     */
    fun wallpapers(): List<File> = listOf(
        File(ThemeImages.ROOT, "wallpapers"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Bold7"),
    ).firstNotNullOfOrNull { dir ->
        dir.listFiles { f -> f.isFile && f.name.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") } }
            ?.sortedBy { it.name }?.takeIf { it.isNotEmpty() }
    }.orEmpty()

    /** A wallpaper about [size] px square, decoded a quarter of its size. */
    fun thumbnail(file: File, size: Int): Bitmap? {
        val full = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
        return Bitmap.createScaledBitmap(full, size, size, true).also { if (it !== full) full.recycle() }
    }

    /** [file] as the home's and the lock screen's wallpaper; false when it could not be. */
    fun setWallpaper(file: File): Boolean {
        if (dryRun) {
            Log.i(TAG, "dry run: wallpaper ${file.name} not set")
            return true
        }
        return try {
            file.inputStream().use { WallpaperManager.getInstance(context).setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
            true
        } catch (e: IOException) {
            Log.w(TAG, "wallpaper not set", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "wallpaper not set", e)
            false
        }
    }

    /** Android's Default apps, where the user picks another home screen or phone app. Main thread. */
    fun openDefaultApps(from: Activity) {
        if (dryRun) {
            Log.i(TAG, "dry run: Default apps not opened")
            return
        }
        try {
            from.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no Default apps page", e)
        }
    }
}
