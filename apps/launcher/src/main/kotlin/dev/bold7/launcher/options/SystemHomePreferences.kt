package dev.bold7.launcher.options

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Log
import android.util.Size
import dev.bold7.launcher.home.HomeChoices
import dev.bold7.launcher.home.HomeEvent
import dev.bold7.launcher.home.HomeModel
import dev.bold7.launcher.home.HomeState
import dev.bold7.launcher.setup.WizardDevice
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home Screen Preferences on the phone: the home's choices through [home] (HomeModel, this
 * process, which saves them with the layout) and Android's wallpaper, the 9900's default being the
 * one the module lays under /product/media/bold7/wallpapers (WizardDevice). Blocking: io only.
 */
internal class SystemHomePreferences(private val context: Context, private val home: HomeModel) : HomePreferences {
    private val wallpapers = WizardDevice(context, dryRun = false)

    // Options opened in a process just started (the home not shown since): the home's first load, 2 s at most.
    private fun loaded(): HomeState? = home.state.value.takeIf { it.loaded }
        ?: runBlocking { withTimeoutOrNull(LOAD_WAIT_MS) { home.state.first { it.loaded } } }

    override fun read(): HomeChoices? = loaded()?.choices

    override fun write(choices: HomeChoices): Boolean {
        if (loaded() == null) return false
        home.dispatch(HomeEvent.Preferences(choices.downloads, choices.hiddenPanels))
        return true
    }

    override fun setWallpaper(change: WallpaperChange): Boolean = when (change) {
        WallpaperChange.Default -> defaultWallpaper()?.let(wallpapers::setWallpaper) ?: false.also { Log.w(TAG, "no default wallpaper") }
        is WallpaperChange.Picture -> try {
            context.contentResolver.openInputStream(Uri.parse(change.uri))?.use {
                WallpaperManager.getInstance(context).setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            } != null
        } catch (e: IOException) {
            Log.w(TAG, "wallpaper not set", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "wallpaper not set", e)
            false
        }
    }

    /** background_homescreen.png of the theme, cut to the square by tools/import-assets.sh (fonds). */
    private fun defaultWallpaper(): File? = wallpapers.wallpapers().firstOrNull { it.name == "Default.png" }

    /** [change]'s picture, or the wallpaper in use (null), [side] px square cropped at the centre; null if unreadable. */
    fun thumbnail(change: WallpaperChange?, side: Int): Bitmap? = try {
        when (change) {
            // The system's own copy: never recycled here.
            null -> (WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM) as? BitmapDrawable)?.bitmap
                ?.let { ThumbnailUtils.extractThumbnail(it, side, side) }
            WallpaperChange.Default -> defaultWallpaper()?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                ?.let { ThumbnailUtils.extractThumbnail(it, side, side, ThumbnailUtils.OPTIONS_RECYCLE_INPUT) }
            is WallpaperChange.Picture -> context.contentResolver.loadThumbnail(Uri.parse(change.uri), Size(2 * side, 2 * side), null)
                .let { ThumbnailUtils.extractThumbnail(it, side, side, ThumbnailUtils.OPTIONS_RECYCLE_INPUT) }
        }
    } catch (e: IOException) {
        Log.w(TAG, "wallpaper not read", e)
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "wallpaper not read", e)
        null
    }

    private companion object {
        const val TAG = "Bold7/Options"
        const val LOAD_WAIT_MS = 2_000L
    }
}
