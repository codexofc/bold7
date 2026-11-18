package dev.bold7.launcher

import android.app.Application
import dev.bold7.launcher.home.FOLDER_ICONS
import dev.bold7.launcher.home.folderIcon
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.platform.stopOrphanKeyReleases
import dev.bold7.theme.ThemeImages
import dev.bold7.ui.MENU_IMAGES

class LauncherApp : Application() {
    val graph by lazy { LauncherGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        stopOrphanKeyReleases()
        super.onCreate()
        // Decoded off the main thread before a view asks for them: the home's (its io shares the
        // work) and those the switcher, the profiles and Manage Connections open on the main thread.
        ThemeImages.of(this).preload(PRELOAD + (0 until FOLDER_ICONS).map(::folderIcon) + MENU_IMAGES)
        graph.chargingClock.listen()
    }

    private companion object {
        val PRELOAD = listOf(
            "home_banner_background", "home_banner_background_focus", "home_banner_divider", "home_indicator_bar_background",
            "home_net_rim_indicator_batterylevel_icons50x25", "home_net_rim_ribbon_signallevel_icons49x19",
            "home_net_rim_ribbon_wlansignallevel_icons30x24", "home_net_rim_ribbon_profile_icons43x43",
            "home_net_rim_ribbon_unreadcount_icons33x29", "home_banner_search_button", "home_net_rim_alarm_indicator_icons21x21",
            "home_focus", "home_tabmanager_header_line_top", "home_tabmanager_header_line_bottom", "home_homescreen_mask_p",
            "home_icon_move_underlay", "home_new_overlay",
            "home_background_folder_1", "home_background_folder_2", "home_background_folder_3", "home_background_folder_4",
            "home_background_folder_5", "home_background_folder_bottom",
            "home_net_rim_bb_ribbon_app",
            "profile_normal", "profile_normal_focus", "profile_vibrate", "profile_vibrate_focus", "profile_silent", "profile_silent_focus",
            "profile_loud", "profile_loud_focus", "profile_phone_only", "profile_phone_only_focus", "profile_alerts_off",
            "profile_alerts_off_focus", "profile_medium", "profile_medium_focus",
            "connections_box", "connections_title", "connections_icons",
            "ui_popup", "ui_button", "ui_button_focus", "ui_check",
        )
    }
}

val android.content.Context.graph: LauncherGraph get() = (applicationContext as LauncherApp).graph
