package dev.bold7.messages

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Environment
import android.util.Log
import dev.bold7.icons.IconSource
import dev.bold7.icons.IconTemplate
import dev.bold7.icons.TEMPLATE_MASK
import dev.bold7.icons.TEMPLATE_OVERLAY
import dev.bold7.icons.TEMPLATE_SIZE
import dev.bold7.icons.TEMPLATE_UNDERLAY
import dev.bold7.icons.USER_ICON_DIR
import dev.bold7.icons.downscaleSteps
import dev.bold7.icons.drawBbos7Icon
import dev.bold7.icons.iconSource
import dev.bold7.icons.marginless
import dev.bold7.icons.parseAppTableOrEmpty
import dev.bold7.icons.parseIconTableOrEmpty
import dev.bold7.icons.sampleSize
import dev.bold7.icons.shownAsIs
import dev.bold7.ui.themeBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Another app as Messages shows it: its name (the BBOS 7 name of core/icons' table, else
 * Android's label, else its package) and its row icon, its BBOS 7 icon as the home's grid draws
 * it (the table's, else the user's own `/sdcard/Bold7/icons/<package>.png`, else the theme's
 * template over the app's own icon, core/icons) without its margin at the row's 42 px, brought
 * down, never up. Worked out on the io thread ([load]), read on the main one; an app without an
 * icon (uninstalled, undrawable) keeps the system's cell of its row.
 */
class AppLooks(private val context: Context) {
    private val names = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, Bitmap>()

    /** The packages already tried, drawn or not: each once per process. */
    private val tried = ConcurrentHashMap.newKeySet<String>()

    private val iconTable by lazy {
        parseIconTableOrEmpty(context.resources.openRawResource(dev.bold7.icons.R.raw.bbos7_icons).use { it.reader().readText() }) {
            Log.e(TAG, "apps: unreadable bbos7_icons.tsv, every icon drawn", it)
        }
    }
    private val appTable by lazy {
        parseAppTableOrEmpty(context.resources.openRawResource(dev.bold7.icons.R.raw.bbos7_apps).use { it.reader().readText() }) {
            Log.e(TAG, "apps: unreadable bbos7_apps.tsv, Android's names", it)
        }
    }
    private val template by lazy { IconTemplate(image(TEMPLATE_UNDERLAY)!!, image(TEMPLATE_MASK)!!, image(TEMPLATE_OVERLAY)!!) }

    /** [app]'s row icon once [load] drew it, else null. */
    fun icon(app: String): Bitmap? = icons[app]

    /** [app]'s name; io only (the first read of the tables and of the package manager). */
    fun name(app: String): String = names.getOrPut(app) {
        appTable.nameForPackage(app) ?: try {
            context.packageManager.run { getApplicationLabel(getApplicationInfo(app, 0)).toString() }
        } catch (e: PackageManager.NameNotFoundException) {
            app
        }
    }

    fun installed(app: String): Boolean = try {
        context.packageManager.getApplicationInfo(app, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Draws the icons of [apps] not tried yet; io only. True when one was drawn. */
    fun load(apps: Collection<String>): Boolean {
        var drawn = false
        for (app in apps) {
            if (!tried.add(app)) continue
            val icon = try {
                grid(app)?.let { marginless(it, MessageRowView.ICON) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "apps: no icon for $app, the system's", e)
                null
            } ?: continue
            icons[app] = icon
            drawn = true
        }
        return drawn
    }

    /** [app]'s grid icon, [TEMPLATE_SIZE] px, as the launcher's LauncherAppsSource chooses it; null for an app Android does not know. */
    private fun grid(app: String): Bitmap? {
        val table = iconTable.iconForPackage(app)
        val user = File(Environment.getExternalStorageDirectory(), "$USER_ICON_DIR/$app.png")
        return when (iconSource(inTable = table != null, userIcon = user.isFile)) {
            IconSource.TABLE -> image(table!!)?.let { png -> downscaleSteps(png.width, TEMPLATE_SIZE).fold(png) { b, size -> Bitmap.createScaledBitmap(b, size, size, true) } }
            IconSource.USER -> userIcon(user)
            IconSource.GENERATED -> null
        } ?: generated(app)
    }

    private fun generated(app: String): Bitmap? = try {
        drawn(context.packageManager.getApplicationIcon(app))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** The user's file, 92x92 as it is, any other size through the generated icon's rendering. */
    private fun userIcon(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val png = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }) ?: return null
        return if (shownAsIs(png.width, png.height)) png else drawn(BitmapDrawable(context.resources, png))
    }

    private fun drawn(icon: Drawable) = drawBbos7Icon(icon, TEMPLATE_SIZE, template).bitmap

    /** A theme image by name, null when the mounted folder and this APK lack it (the store's 1x1 empty image). */
    private fun image(name: String): Bitmap? = themeBitmap(context, name).takeIf { it.width > 1 }
}
