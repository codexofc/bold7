package dev.bold7.launcher.search

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.bold7.launcher.R
import dev.bold7.launcher.options.Screen

/** An Android Settings page the search finds by its name or one of its [keywords]. */
internal class Setting(val label: String, val action: String, val keywords: List<String>, val screen: Screen? = null)

/**
 * The screens of Bold7's Options the search opens in place of Android's page for the same thing
 *: the OS 7 name (the guide 7.1's, B) and the words that lead to it.
 */
internal fun optionsScreens(): List<Setting> = listOf(
    Triple("Sounds for Selected Profile", Screen.PROFILE_SOUNDS, "sons sounds sonnerie ringtone notifier alertes alerts profil profile"),
    Triple("Phone Ringtone", Screen.PHONE_TONES, "sonnerie ringtone volume vibreur vibrate vibration téléphone phone"),
    Triple("Profile Management", Screen.PROFILES, "profils profiles silencieux silent normal loud medium"),
    Triple("Screen Display", Screen.SCREEN_DISPLAY, "écran screen luminosité brightness backlight veille timeout rétroéclairage"),
    Triple("Date and Time", Screen.DATE_TIME, "heure time date fuseau timezone zone 24 12 horloge"),
    Triple("Language", Screen.LANGUAGE, "langue language région region"),
    Triple("Typing", Screen.TYPING, "saisie typing clavier keyboard prédictive predictive style"),
    Triple("Trackpad Sensitivity", Screen.TRACKPAD, "trackpad sensibilité sensitivity pavé tactile"),
    Triple("Touchscreen Sensitivity", Screen.TOUCHSCREEN, "écran tactile touchscreen sensibilité sensitivity appui long touch hold hover"),
    Triple("Keyboard", Screen.KEYBOARD, "clavier keyboard tonalité key tone devise currency monnaie"),
    Triple("Accessibility", Screen.ACCESSIBILITY, "accessibilité accessibility contraste contrast gris grayscale greyscale inversion couleurs"),
    Triple("About", Screen.ABOUT, "à propos about version modèle model logiciel software build"),
    Triple("Storage", Screen.STORAGE, "mémoire memory espace space stockage storage libre free"),
    Triple("Battery", Screen.BATTERY, "batterie battery autonomie niveau level"),
    Triple("Encryption", Screen.ENCRYPTION, "chiffrement encryption sécurité security"),
).map { (label, screen, words) -> Setting(label, "", words.split(' '), screen) }

/**
 * The pages of Android's Settings the search reaches, with words that lead to them, English and
 * French. The Settings' own search index is behind READ_SEARCH_INDEXABLES and one
 * provider per app: a table instead. Grow it when a page is missed.
 */
internal fun settingsPages(context: Context): List<Setting> = listOf(
    Triple(R.string.setting_wifi, Settings.ACTION_WIFI_SETTINGS, "wlan wireless sans-fil réseau network hotspot"),
    Triple(R.string.setting_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS, "appairage pairing casque headset écouteurs"),
    Triple(R.string.setting_airplane, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "avion airplane flight hors-ligne offline"),
    Triple(R.string.setting_network, Settings.ACTION_WIRELESS_SETTINGS, "réseau network mobile internet"),
    Triple(R.string.setting_data_usage, Settings.ACTION_DATA_USAGE_SETTINGS, "données data forfait consommation"),
    Triple(R.string.setting_roaming, Settings.ACTION_DATA_ROAMING_SETTINGS, "itinérance roaming opérateur carrier 4g 5g sim apn"),
    Triple(R.string.setting_vpn, Settings.ACTION_VPN_SETTINGS, "vpn tunnel"),
    Triple(R.string.setting_nfc, Settings.ACTION_NFC_SETTINGS, "nfc sans-contact contactless paiement payment"),
    Triple(R.string.setting_display, Settings.ACTION_DISPLAY_SETTINGS, "écran screen luminosité brightness veille sleep police font rotation"),
    Triple(R.string.setting_wallpaper, Intent.ACTION_SET_WALLPAPER, "fond wallpaper background"),
    Triple(R.string.setting_sound, Settings.ACTION_SOUND_SETTINGS, "sonnerie ringtone volume vibreur vibrate vibration audio"),
    Triple(R.string.setting_dnd, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "ne-pas-déranger dnd silence disturb"),
    Triple(R.string.setting_notifications, Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, "alertes alerts"),
    Triple(R.string.setting_battery, Intent.ACTION_POWER_USAGE_SUMMARY, "batterie battery autonomie power énergie"),
    Triple(R.string.setting_battery_saver, Settings.ACTION_BATTERY_SAVER_SETTINGS, "économiseur saver économie"),
    Triple(R.string.setting_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "mémoire memory espace space stockage storage"),
    Triple(R.string.setting_apps, Settings.ACTION_APPLICATION_SETTINGS, "applis apps désinstaller uninstall"),
    Triple(R.string.setting_default_apps, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "défaut default navigateur browser"),
    Triple(R.string.setting_location, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "gps position localisation location"),
    Triple(R.string.setting_security, Settings.ACTION_SECURITY_SETTINGS, "verrouillage lock mot-de-passe password code pin empreinte fingerprint"),
    Triple(R.string.setting_privacy, Settings.ACTION_PRIVACY_SETTINGS, "confidentialité privacy autorisations permissions"),
    Triple(R.string.setting_accounts, Settings.ACTION_SYNC_SETTINGS, "comptes accounts synchronisation sync google"),
    Triple(R.string.setting_languages, Settings.ACTION_LOCALE_SETTINGS, "langue language région region"),
    Triple(R.string.setting_keyboard, Settings.ACTION_INPUT_METHOD_SETTINGS, "clavier keyboard saisie input gboard"),
    Triple(R.string.setting_date, Settings.ACTION_DATE_SETTINGS, "heure time fuseau timezone"),
    Triple(R.string.setting_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibilité accessibility"),
    Triple(R.string.setting_developer, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "développeur developer usb débogage debug adb"),
    Triple(R.string.setting_about, Settings.ACTION_DEVICE_INFO_SETTINGS, "à-propos about version android téléphone phone"),
    Triple(R.string.setting_cast, Settings.ACTION_CAST_SETTINGS, "diffuser cast chromecast"),
    Triple(R.string.setting_print, Settings.ACTION_PRINT_SETTINGS, "impression imprimante printer print"),
    Triple(R.string.setting_all, Settings.ACTION_SETTINGS, "réglages settings options paramètres"),
).map { (label, action, words) -> Setting(context.getString(label), action, words.split(' ').map { it.replace('-', ' ') }) }.let { optionsScreens() + it }
