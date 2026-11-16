package dev.bold7.launcher.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.DisplayMetrics
import android.util.Log
import dev.bold7.design.Dimens
import dev.bold7.icons.AppTable
import dev.bold7.icons.CALENDAR_ICON
import dev.bold7.icons.IconTemplate
import dev.bold7.icons.TEMPLATE_MASK
import dev.bold7.icons.TEMPLATE_OVERLAY
import dev.bold7.icons.TEMPLATE_UNDERLAY
import dev.bold7.icons.downscaleSteps
import dev.bold7.icons.drawBbos7Icon
import dev.bold7.icons.neutralTile
import dev.bold7.icons.parseAppTableOrEmpty
import dev.bold7.icons.parseIconTableOrEmpty
import java.util.concurrent.ConcurrentHashMap

// No work profile on the Q25, the apps of the main user only.
private val user: UserHandle = Process.myUserHandle()

/** Icon density: about 290 dpi, finer than the 108 dp layers drawn at 93 px on the BBOS 7 mask need (about 140). */
private const val ICON_DPI = Dimens.ICON_ANDROID * DisplayMetrics.DENSITY_DEFAULT / 48

private const val TAG = "Bold7/Home"

private val MEDIA_CATEGORIES = setOf(ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE)

/** The app whose shortcuts are icons of the home: Messages, one per mail account (gsm 020). */
private const val MESSAGES = "dev.bold7.messages"

/** OS 7's icon of a mail account (bbos7_icons.tsv, Messages.service, A; gsm 020, C). */
private const val EMAIL_ACCOUNT_ICON = "bbos7_email_account"

/** PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME, hidden from the SDK (class present in the Q25's framework.jar). */
private const val APP_DETAILS_ACTIVITY = "android.app.AppDetailsActivity"

/**
 * Everything the home asks of LauncherApps and PackageManager. Every method blocks on binder
 * and draws bitmaps: io only, never the main thread (CONTRIBUTING.md), except [icon]. The apps come
 * back with their icons already drawn, so the grid shows them all in its first frame.
 * Package visibility comes from the manifest's <queries>, not from QUERY_ALL_PACKAGES.
 */
class LauncherAppsSource(private val context: Context, private val callbacks: Handler) : AppSource {
    private val launcherApps by lazy { context.getSystemService(LauncherApps::class.java) }

    // Every app's icon stays in memory (about 30 KB each), dropped on update or removal.
    private val icons = ConcurrentHashMap<App, Bitmap>()

    /** Which app has a table icon (res/raw/bbos7_icons.tsv), read on first use, on io. */
    private val tableLoad = lazy {
        val text = context.resources.openRawResource(dev.bold7.icons.R.raw.bbos7_icons).use { it.reader().readText() }
        parseIconTableOrEmpty(text) { Log.e(TAG, "unreadable bbos7_icons.tsv: no table icon", it) }
    }
    private val table by tableLoad

    /** How each app appears (res/raw/bbos7_apps.tsv): its BBOS 7 name, hidden or not; read on first use, on io. */
    private val names by lazy {
        val text = context.resources.openRawResource(dev.bold7.icons.R.raw.bbos7_apps).use { it.reader().readText() }
        parseAppTableOrEmpty(text) { Log.e(TAG, "unreadable bbos7_apps.tsv: every app shown under its own name", it) }
    }

    override fun appTable(): AppTable = names

    /** Each table icon decoded once, kept for good: an update of its app draws it again from here. */
    private val decoded = ConcurrentHashMap<String, Bitmap>()

    private val calendars: MutableSet<App> = ConcurrentHashMap.newKeySet()

    /** Android's name of each app the table renamed, for the search (a few, kept as the icons). */
    private val androidNames = ConcurrentHashMap<App, String>()

    override fun load(): List<App> = launcherApps.getActivityList(null, user).mapNotNull { it.toApp() } + accountIcons()

    override fun activitiesOf(packageName: String): List<App> =
        launcherApps.getActivityList(packageName, user).mapNotNull { it.toApp() } + if (packageName == MESSAGES) accountIcons() else emptyList()

    /**
     * Messages' dynamic shortcuts, one per mail account (AccountShortcuts there), as icons of the
     * home under the account's address, OS 7's mail account icon drawn once for all. One binder
     * call at the listing and at each change Messages makes: nothing on the home's frames
     * (tools/perf.sh to pass). None while this launcher does not hold HOME (no shortcut host).
     */
    private fun accountIcons(): List<App> = try {
        val query = LauncherApps.ShortcutQuery().setPackage(MESSAGES).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        launcherApps.getShortcuts(query, user).orEmpty().filter { it.isEnabled }.map { s ->
            App(MESSAGES, SHORTCUT + s.id, s.shortLabel?.toString().orEmpty()).also { app ->
                icons.computeIfAbsent(app) {
                    decoded[EMAIL_ACCOUNT_ICON] ?: decode(EMAIL_ACCOUNT_ICON)?.also { decoded[EMAIL_ACCOUNT_ICON] = it } ?: neutralTile(Dimens.ICON, template)
                }
            }
        }
    } catch (e: SecurityException) {
        emptyList() // not the default home: no shortcut host
    } catch (e: IllegalStateException) {
        emptyList() // the user is locked
    }

    /** Opens the account's view of Messages its shortcut names; false if it is gone or refused. Main thread, as startActivity. */
    fun startShortcut(app: App, options: android.os.Bundle?): Boolean {
        val id = app.shortcutId ?: return false
        return try {
            launcherApps.startShortcut(app.packageName, id, null, options, user)
            true
        } catch (e: RuntimeException) {
            // ActivityNotFoundException, IllegalStateException (gone, disabled), SecurityException.
            Log.w(TAG, "shortcut not started: ${e.javaClass.name}")
            false
        }
    }

    override fun watch(onChange: (String, Boolean) -> Unit) {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName, user)
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName, user)
            // Called for an uninstall only: an update goes through onPackageChanged (PackageMonitor
            // skips the removal half of a replacement).
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName, user, removed = true)
            override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
                packageNames.forEach { changed(it, user) }
            override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
                packageNames.forEach { changed(it, user) }

            // A mail account added, renamed or removed in Messages: its icons again.
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<android.content.pm.ShortcutInfo>, user: UserHandle) {
                if (packageName == MESSAGES) changed(packageName, user)
            }

            // The old icon goes before the home hears of the change, so it never shows again.
            private fun changed(packageName: String, from: UserHandle, removed: Boolean = false) {
                if (from != user) return
                icons.keys.removeIf { it.packageName == packageName }
                calendars.removeIf { it.packageName == packageName }
                androidNames.keys.removeIf { it.packageName == packageName }
                onChange(packageName, removed)
            }
        }, callbacks)
    }

    /** [app]'s icon, from the table or generated, drawn when it was listed; the only call for the main thread. */
    fun icon(app: App): Bitmap? = icons[app]

    /** True if [app]'s icon is the table calendar, over which the home writes the day; main thread too. */
    fun showsDay(app: App): Boolean = app in calendars

    /** Android's name of [app] when bbos7_apps.tsv gave it a BBOS 7 one, else null; main thread too. */
    fun androidName(app: App): String? = androidNames[app]

    /** The table icon [app] wears (bbos7_icons.tsv), null for its own; the table is read by the first listing, never here (main thread: search, stars). */
    fun tableIcon(app: App): String? = if (tableLoad.isInitialized()) table.iconFor(app.packageName, app.className) else null

    /**
     * The table icon of every activity of [packageName] (a "*" line of the table), null otherwise,
     * and null until the first listing has read the table: never read here, the main thread asks.
     */
    fun tableIcon(packageName: String): String? = if (tableLoad.isInitialized()) table.iconForPackage(packageName) else null

    /**
     * The app under its BBOS 7 name (bbos7_apps.tsv) if it has one, its icon drawn on the way. Null
     * for the "app details" entry the system lists for an app without a launcher icon
     * (SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED, on by default).
     */
    private fun LauncherActivityInfo.toApp(): App? {
        if (componentName.className == APP_DETAILS_ACTIVITY) return null
        // A system app, updated or not, cannot be uninstalled: no delete badge in edit mode.
        val removable = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
        val name = names.nameFor(componentName.packageName, componentName.className) ?: label.toString()
        // Media: what Android files under audio, video or images (BBOS 7's Media view, D).
        val media = applicationInfo.category in MEDIA_CATEGORIES
        val app = App(componentName.packageName, componentName.className, name, removable, media)
        if (name != label.toString()) androidNames[app] = label.toString()
        icons.computeIfAbsent(app) { iconOf(app.packageName, app.className) { getIcon(ICON_DPI) } }
        if (table.iconFor(app.packageName, app.className) == CALENDAR_ICON) calendars += app
        return app
    }

    /**
     * [Dimens.ICON] px: the activity's table icon, else its own [original] made BBOS 7 by the chain
     * of core/icons (a white glyph, the adaptive icon recomposed, a legacy icon in the template).
     */
    private fun iconOf(packageName: String, className: String, original: () -> Drawable): Bitmap =
        table.iconFor(packageName, className)?.let { name -> decoded[name] ?: decode(name)?.also { decoded[name] = it } } ?: generate(packageName, original)

    /**
     * Once per app and process, on io; logged so the chain's choice per app can be read on the
     * phone. An app whose icon fails to load or draw gets a plain neutral tile: one faulty
     * drawable must not empty the whole grid, which is listed at once.
     */
    private fun generate(packageName: String, original: () -> Drawable): Bitmap {
        val start = SystemClock.uptimeMillis()
        val drawn = try {
            drawBbos7Icon(original(), Dimens.ICON, template)
        } catch (e: RuntimeException) {
            Log.e(TAG, "icon $packageName: unreadable or undrawable, bare template", e)
            return neutralTile(Dimens.ICON, template)
        }
        Log.i(TAG, "icon $packageName: ${drawn.stage}, ${SystemClock.uptimeMillis() - start} ms")
        return drawn.bitmap
    }

    /** The theme's shortcut template (Tile.kt), decoded once; the build checks its three drawables are there. */
    private val template by lazy { IconTemplate(raw(TEMPLATE_UNDERLAY), raw(TEMPLATE_MASK), raw(TEMPLATE_OVERLAY)) }

    @Suppress("DiscouragedApi")
    private fun raw(name: String): Bitmap =
        BitmapFactory.decodeResource(context.resources, context.resources.getIdentifier(name, "drawable", context.packageName))

    /** Null sends the app to the generated icon, never to its own; never seen, the build checks every name of the table. */
    private fun decode(name: String): Bitmap? {
        val res = context.resources
        // Resources named by the table, from assets-private (build.gradle.kts): no R field for them.
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", context.packageName)
        val png = if (id != 0) BitmapFactory.decodeResource(res, id) else null
        if (png == null) {
            Log.w(TAG, "no drawable $name: the app gets the generated icon")
            return null
        }
        // Sources of 92 px (App World's 100), square: halved while above twice the target, then filtered to it.
        return downscaleSteps(png.width, Dimens.ICON).fold(png) { bitmap, size -> Bitmap.createScaledBitmap(bitmap, size, size, true) }
    }
}
