package dev.bold7.sysui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import dev.bold7.icons.IconTemplate
import dev.bold7.icons.TEMPLATE_MASK
import dev.bold7.icons.TEMPLATE_OVERLAY
import dev.bold7.icons.TEMPLATE_SIZE
import dev.bold7.icons.TEMPLATE_UNDERLAY
import dev.bold7.icons.downscaleSteps
import dev.bold7.icons.drawBbos7Icon
import dev.bold7.icons.indicatorBitmap
import dev.bold7.icons.parseIconTableOrEmpty
import java.util.concurrent.ConcurrentHashMap

/**
 * Another app's indicator in the banners SystemUI draws, as the launcher draws it in the home's
 * (LauncherAppsSource.drawIndicator): the app's BBOS 7 icon, from the table (bbos7_icons.tsv, its
 * image found by name in the folder the module mounts, the launcher's block) or drawn on the
 * theme's template from the app's own icon, brought to 25 px in a counter's 33x29 frame
 * (indicatorBitmap of core/icons). Drawn on HubCounts' thread, read on the main one; an app
 * Android does not know, or whose icon fails, keeps the "?" bubble.
 */
internal class AppIndicators(private val context: Context, private val images: Images) {
    private val drawn = ConcurrentHashMap<String, Drawable>()

    private val table by lazy {
        val text = images.res.openRawResource(dev.bold7.icons.R.raw.bbos7_icons).use { it.reader().readText() }
        parseIconTableOrEmpty(text) { Log.e(TAG, "indicators: unreadable bbos7_icons.tsv, every icon drawn", it) }
    }
    private val template by lazy { IconTemplate(image(TEMPLATE_UNDERLAY)!!, image(TEMPLATE_MASK)!!, image(TEMPLATE_OVERLAY)!!) }

    /** [packageName]'s indicator once [draw] has made it, else null. Main thread. */
    operator fun get(packageName: String): Drawable? = drawn[packageName]

    /** Makes [packageName]'s indicator if it is not yet. HubCounts' thread only. */
    fun draw(packageName: String) {
        if (drawn.containsKey(packageName)) return
        val icon = try {
            table.iconForPackage(packageName)?.let(::image)?.let { png ->
                downscaleSteps(png.width, TEMPLATE_SIZE).fold(png) { b, size -> Bitmap.createScaledBitmap(b, size, size, true) }
            } ?: drawBbos7Icon(context.packageManager.getApplicationIcon(packageName), TEMPLATE_SIZE, template).bitmap
        } catch (e: PackageManager.NameNotFoundException) {
            return
        } catch (e: RuntimeException) {
            Log.w(TAG, "indicators: no icon for $packageName, the \"?\" bubble", e)
            return
        }
        drawn.putIfAbsent(packageName, BitmapDrawable(images.res, indicatorBitmap(icon)))
    }

    /** A theme image by name, null when the mounted folder and this APK lack it (the store's 1x1 empty image). */
    private fun image(name: String): Bitmap? = images.bitmap(name).takeIf { it.width > 1 }
}
