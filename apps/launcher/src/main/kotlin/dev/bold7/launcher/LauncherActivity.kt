package dev.bold7.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.content.ActivityNotFoundException
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import dev.bold7.design.NoticeDimens
import dev.bold7.launcher.connections.ConnectionsPanel
import dev.bold7.launcher.connections.Page
import dev.bold7.launcher.home.BannerFocus
import dev.bold7.launcher.home.BannerSource
import dev.bold7.launcher.home.HomeEvent
import dev.bold7.launcher.home.HomeView
import dev.bold7.launcher.home.shortcutId
import dev.bold7.launcher.hub.HubWatch
import dev.bold7.launcher.hub.ItemOpener
import dev.bold7.launcher.notices.NoticesView
import dev.bold7.launcher.keys.HomeKeys
import dev.bold7.launcher.setup.Opening
import dev.bold7.launcher.setup.WizardActivity
import dev.bold7.launcher.setup.WizardDevice
import dev.bold7.launcher.setup.opening
import dev.bold7.launcher.options.EXTRA_MANAGE_CONNECTIONS
import dev.bold7.launcher.options.OptionsActivity
import dev.bold7.launcher.options.Screen
import dev.bold7.launcher.quickstep.WindowZoom
import dev.bold7.launcher.options.WifiActivity
import dev.bold7.launcher.profiles.ProfilePopup
import dev.bold7.launcher.search.SearchPanel
import dev.bold7.platform.hideStatusBar
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * HOME, and the recents component the module's RRO names (config_recentsComponentName): its
 * name is fixed. Renders the home model's state, nothing else.
 */
class LauncherActivity : Activity() {
    private lateinit var scope: CoroutineScope
    private lateinit var home: HomeView
    private lateinit var keys: HomeKeys
    private lateinit var bannerSource: BannerSource
    private lateinit var hubWatch: HubWatch
    private lateinit var notices: NoticesView

    /** "Select a Profile" while it shows, closed with the home. */
    private var profilePopup: ProfilePopup? = null

    /** Manage Connections while it shows, closed with the home. */
    private var connectionsPanel: ConnectionsPanel? = null

    private var setupDone = false

    /** The search typed from the home, built by the first one. */
    private var search: SearchPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = graph
        // A failure of the work it starts (the search: the contacts provider, a picture) is logged;
        // uncaught, it would take the process down, the quickstep service with it.
        val logged = CoroutineExceptionHandler { _, e -> Log.e("Bold7/Home", "home work failed", e) }
        scope = CoroutineScope(SupervisorJob() + graph.main + logged)
        home = HomeView(this, graph.apps, scope, graph.ioDispatcher, graph.home::dispatch, graph.launches, graph.home::setHidden)
        setContentView(home)
        // After setContentView: before it the window has no decor view and its insets controller
        // throws on Android 14 (seen on the Q25 at the first launch).
        hideStatusBar()
        // The keyboard and the trackpad: the focus is drawn by the home.
        keys = HomeKeys(this, home, { graph.home.state.value }, graph.home::dispatch, ::openSearch, ::bannerClicked) { graph.typingMode.mode }
        home.addView(keys.view, 0)
        home.onBannerTap = ::bannerClicked
        home.onOptions = { zoomTo(OptionsActivity.intent(this, Screen.HOME)) }
        home.onManagePanels = { zoomTo(OptionsActivity.intent(this, Screen.PANELS)) }
        // The list of notifications, over the tray, under the menus.
        val opener = ItemOpener(this, graph.hubReader, graph.ioDispatcher, scope, home::start) { graph.launches.activityOptions(WindowZoom.open) }
        notices = NoticesView(this, home, opener, graph.hub::markRead, graph.apps::iconOf, graph.ioDispatcher, scope, keys::refocus)
        home.addView(notices, home.indexOfChild(home.banner) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = NoticeDimens.TOP })
        home.onBannerPull = notices::open
        home.banner.indicatorIcon = graph.apps::indicator
        keys.list = notices
        // Undispatched: the current state is drawn by the first frame, not one post later.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.home.state.collect {
                home.render(it)
                keys.stateChanged()
            }
        }
        // The banner: Android's state while the home shows, the Hub's unread counts;
        // the icons' stars: what is new.
        bannerSource = BannerSource(this, graph.io, graph.showcaseMask) { home.banner.state = it }
        // The wallpaper the size of the screen: the home does not scroll it. A binder call,
        // off the main thread; Android keeps the hint, so once per home is plenty.
        graph.io.post {
            val dm = resources.displayMetrics
            WallpaperManager.getInstance(this).suggestDesiredDimensions(dm.widthPixels, dm.heightPixels)
        }
        // The profiles: listened to from the first home on, shown in the banner's speaker.
        graph.profiles.onChange = { bannerSource.profile(it) }
        graph.profiles.attach()
        bannerSource.profile(graph.profiles.current)
        // Manage Connections: the phone's connections read once, off the main thread, before a first click.
        graph.connections
        hubWatch = HubWatch(this, graph.hub, graph.hubThread)
        // The LED: from the first home on, for the life of the process (posted, nothing here waits).
        graph.led.start()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.hub.state.collect {
                bannerSource.counts(it.badges.counts, it.badges.appCounts)
                home.showBadges(it.badges)
                notices.render(it.notices)
            }
        }
    }

    /**
     * A target of the banner clicked or tapped: the notifications open or close their list
     *; the magnifier opens the search; the banner opens Manage Connections
     * (sim-23, C); the speaker opens "Select a Profile".
     */
    private fun bannerClicked(target: BannerFocus) {
        if (target == BannerFocus.NOTIFICATIONS) return notices.toggle()
        notices.close()
        when (target) {
            BannerFocus.SEARCH -> openSearch("")
            BannerFocus.SPEAKER -> openProfiles()
            BannerFocus.BANNER -> connections()
            else -> {}
        }
    }

    // Nothing of the phone is changed while it is locked (the home under the keyguard).
    private fun openProfiles() {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val profiles = graph.profiles
        // The trackpad's focus stays on the speaker after a choice or Escape: nothing resets it.
        profilePopup?.dismiss()
        profilePopup = ProfilePopup(this, profiles.current, profiles::select, ::openSoundSettings, {}).also { it.show() }
    }

    // "Change Sounds and Alerts" opens Options' list of the same name (sim-30, C).
    private fun openSoundSettings() {
        zoomTo(OptionsActivity.intent(this, Screen.SOUNDS))
        keys.reset()
    }

    /**
     * Manage Connections under the banner, which keeps the trackpad's focus until
     * it goes down into the panel (sim-112). Nothing while locked. Android's quick settings stay
     * reachable by the swipe from an app's bar.
     */
    private fun connections() {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        connectionsPanel?.dismiss()
        connectionsPanel = ConnectionsPanel(this, graph.connections, ::openPage) { lit -> if (lit) keys.refocus() else home.showFocus(null) }
            .also { it.show() }
    }

    /** Options, or Android's page for what Manage Connections and Options cannot do themselves. */
    private fun openPage(page: Page) {
        if (page == Page.OPTIONS) return zoomTo(OptionsActivity.intent(this))
        // Alarm opens Bold7's Clock on Set Alarm; Android's alarms only without it.
        if (page == Page.ALARMS) try {
            return zoomTo(bold7Clock().putExtra("dev.bold7.clock.SET_ALARM", true))
        } catch (e: ActivityNotFoundException) {
            Log.w("Bold7/Connections", "Bold7's Clock is not installed: Android's alarms", e)
        }
        // Wi-Fi Network is Options' own screen, in Options' task.
        if (page == Page.WIFI_NETWORKS) return zoomTo(Intent(this, WifiActivity::class.java))
        val action = when (page) {
            Page.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            Page.MOBILE -> Settings.ACTION_NETWORK_OPERATOR_SETTINGS
            Page.WIFI, Page.WIFI_NETWORKS -> Settings.ACTION_WIFI_SETTINGS
            Page.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            Page.NFC -> Settings.ACTION_NFC_SETTINGS
            Page.ALARMS -> AlarmClock.ACTION_SHOW_ALARMS
            Page.NETWORKS -> Settings.ACTION_WIRELESS_SETTINGS
            Page.SERVICE_STATUS -> Settings.ACTION_DEVICE_INFO_SETTINGS
            Page.OPTIONS -> return
        }
        try {
            zoomTo(Intent(action))
        } catch (e: ActivityNotFoundException) {
            Log.w("Bold7/Connections", "no page for $page", e)
        }
    }

    /**
     * Leaves the home for [intent]'s screen with OS 7's zoom, as an app of the grid: the theme's
     * homescreen rule holds for any screen it leaves to (theme.xml, A), Options and the pages of
     * Manage Connections included. Throws ActivityNotFoundException as startActivity does.
     */
    private fun zoomTo(intent: Intent) = graph.launches.start(this, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), WindowZoom.open)

    private fun openSearch(typed: String) {
        // Nothing of the phone is searched while it is locked (the home under the keyguard).
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        // The hidden apps stay out (D): Google's Calculator or Calendar, hidden behind Bold7's, came
        // up twice under the same name and icon; Show All still lists them.
        val panel = search ?: SearchPanel(this, { graph.home.state.value.let { s -> s.apps.filter { it.shortcutId == null && !s.hides(it) } } }, graph.apps::icon, graph.apps::tableIcon, graph.apps::androidName, graph.search, graph.searchPictures, scope, graph.launches, ::closeSearch).also {
            search = it
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        home.setTrayHidden(true)
        panel.open(typed)
    }

    private fun closeSearch() {
        search?.close()
        home.setTrayHidden(false)
        keys.searchClosed()
    }

    // What the search's microphone heard: the search again with it (the recognizer stopped the
    // home, which closed it).
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // Activity's own, no AndroidX here
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != SearchPanel.REQUEST_VOICE || resultCode != RESULT_OK) return
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::openSearch)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The home key, and End from an app (ENDCALL with end_button_behavior 1, the home
        // intent of PhoneWindowManager.goHome): the home, its tray lowered.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) toHome()
        // Options > Networks and Connections > Manage Connections.
        if (intent.getBooleanExtra(EXTRA_MANAGE_CONNECTIONS, false)) {
            toHome()
            connections()
        }
    }

    private fun toHome() {
        profilePopup?.dismiss()
        connectionsPanel?.dismiss()
        closeSearch()
        notices.close()
        graph.home.dispatch(HomeEvent.HomeKey)
    }

    override fun onStart() {
        super.onStart()
        bannerSource.start()
        hubWatch.shown(true)
        graph.apps.refreshUserIcons() // the user's icons may have changed while away
        graph.chargingClock.homeShown = true
    }

    /**
     * On the home the banner replaces Android's status bar: the window covers the
     * whole screen and hides the bar. A swipe from the top edge reaches the banner, which opens the
     * list of notifications; the bar Android shows for it at once (transient bars) is hidden again
     * by SystemUI, and never drawn on the home (system/sysui). Without the
     * transient behaviour, Android would show the bar for good and hand the swipe to its shade.
     * The apps' bar belongs to SystemUI.
     */
    private fun hideStatusBar() = window.hideStatusBar() // at once, no slide

    // Every key goes to the home's keys first, but those of a text being edited (a folder's name, the search).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        currentFocus !is EditText && keys.onKey(event) || super.dispatchKeyEvent(event)

    // A touch hides the trackpad's focus.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) keys.clearFocus()
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.hide(WindowInsets.Type.statusBars())
        keys.windowFocus(hasFocus)
    }

    // Escape closes the search, then lowers the tray (D); the home stays.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // still called without enableOnBackInvokedCallback, which this app does not set
    override fun onBackPressed() {
        when {
            search?.isOpen == true -> search?.back()
            notices.isOpen -> notices.close()
            else -> graph.home.dispatch(HomeEvent.Back)
        }
    }

    override fun onStop() {
        profilePopup?.dismiss()
        connectionsPanel?.dismiss()
        closeSearch()
        notices.close()
        bannerSource.stop()
        hubWatch.shown(false)
        graph.chargingClock.homeShown = false
        super.onStop()
    }

    // The first unlock after an install: the Setup Wizard, once (Wizard.kt). Off the main thread, once a
    // process; a locked phone is tried again at the next resume.
    override fun onResume() {
        super.onResume()
        if (setupDone || !getSystemService(UserManager::class.java).isUserUnlocked) return
        setupDone = true
        graph.io.post {
            val device = WizardDevice(applicationContext, dryRun = false)
            when (opening(device.done(), device.textScreenAsked())) {
                Opening.SHOW -> runOnUiThread { if (!isDestroyed) startActivity(Intent(this, WizardActivity::class.java)) }
                Opening.MARK_DONE -> device.markDone()
                Opening.NOTHING -> {}
            }
        }
    }

    override fun onDestroy() {
        profilePopup?.dismiss()
        connectionsPanel?.dismiss()
        // The controller outlives the activity: it must not keep it.
        graph.profiles.onChange = null
        hubWatch.stop()
        scope.cancel()
        super.onDestroy()
    }
}

/** Bold7's Clock (apps/clock), explicitly: Android's Clock answers the AlarmClock intents. */
internal fun bold7Clock(): Intent = Intent().setClassName("dev.bold7.clock", "dev.bold7.clock.ClockActivity")
