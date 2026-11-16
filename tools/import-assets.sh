#!/usr/bin/env bash
# Writes into this checkout's assets-gen/ (out of git, one per checkout or worktree) what the build
# reads, from the sources of the shared assets-private/ (default: the main checkout's, also from a
# worktree), which it only reads: firmware extracted by tools/extract-bbos7.py (bbos7-9900/) and the phone's stock fonts.xml
# (device/).
#
#   tools/import-assets.sh [block...]     the default blocks, in this order, without arguments:
#
#   polices    module/system/fonts/BBAlpha*.ttf: BBAlpha Sans, Condensed, Serif and Mono, the system
#              fonts (tools/fonts_xml.py), from bbos7-9900/fonts/net_rim_font_latin_truetype/; and
#              device/fonts.xml(.source), the phone's stock fonts.xml, copied from the shared capture
#              unless this checkout already has one (block telephone)
#   accueil    launcher/res/drawable-nodpi/home_*.png: banner, indicator row, view tabs and their
#              lines, icon focus and default wallpaper of net_rim_theme_BlackBerry7_640x480_b
#   icones     launcher/res/drawable-nodpi/bbos7_*.png: the app icons core/icons/.../bbos7_icons.tsv
#              names and the three images of the generated icon's template
#   profils    launcher/res/drawable-nodpi/profile_*.png: the seven profiles' icons of "Select a Profile"
#              (net_rim_bb_profiles_app.* and its ~focus)
#   connexions launcher/res/drawable-nodpi/connections_*: Manage Connections, the
#              strip of its 40x40 glyphs (the theme's net_rim_Manage_Connections-icons40x40.png: mobile network,
#              Wi-Fi, Bluetooth, alarm, NFC and a sixth unused, its second row for the focus) and its
#              group box and title bar (border_groupboxfull, border_groupboxtitlebar) as 9-patches
#   options    launcher/res/drawable-nodpi/opt_*.png: Options, the category icons of its
#              lists (cat_*.png of the theme, 60x60), the disabled button (border_button~disabled,
#              as a 9-patch) and Word Substitution's text field (border_form, a 9-patch)
#              and, for Wi-Fi Network and Bluetooth, the signal strip of _bs1, the
#              lock and the Bluetooth device icons
#   sons       sounds/<cod>/<path>: the sounds system/module/sounds.tsv names, from bbos7-9900/sounds/
#
#   ui         ui/res/drawable-nodpi/ui_*: what core/ui draws, the full and the short menu,
#              dialogs and their buttons, list headers, check boxes, search field, as Android
#              9-patches where the firmware stretches them; essai/res/drawable-nodpi/ui_sm_*.png:
#              the short menu's action images the demo of core/ui shows; the 27 px emoticons strip
#              (ui_smileys_27)
#   dialogues  android/res/drawable-nodpi/bold7_*.9.png: the dialog box, the full menu's box and the
#              dialog buttons, as 9-patches with their content band, for the framework overlay
#              (system/overlays/android); permissioncontroller/res/drawable-nodpi/
#              bold7_button*.9.png: the two button images again, for its overlay;
#              bold7_check_* and bold7_radio_*.png: the lists' check boxes and radio buttons
#
#   messages  messages/res/drawable-nodpi/msg_*.png: what apps/messages draws, the
#              42 px status icon strips of messages and calls, the list's scroll arrows (GIF made
#              PNG), the short menu's sm_* images; the thread's state icons, picture,
#              field (9-patch) and smiley button; the 45 px emoticons of Add Smiley; mail's
#              send button, form field and reply, forward and draft images; the three
#              icons of Messages Options' rows, the picker's folder and the player's controls
#   saisie     saisie/assets/substitution/en.tsv: OS 7's English word substitution (autotext_en.tsv);
#              saisie/assets/dictionary/fr.tsv, en.tsv: the merged word lists of AOSP and OS 7
#              (merged_fr.tsv; merged_en.tsv, British English, the simulator's, with the words only
#              American has), from assets-private/saisie/dictionnaires
#   phone      phone/res/drawable-nodpi/ph_*.png: what apps/phone draws, the call
#              screen's background, avatar, bottom bar, buttons and volume, the call log's 42 px
#              strip and the short menu's sm_* images, unchanged
#   calendar   calendar/res/drawable-nodpi/cal_*.png: what apps/calendar draws, the
#              reminder box's icon, the bell, the band's arrows and the form's Save mark
#   contacts   contacts/res/drawable-nodpi/cnt_*.png: what apps/contacts draws, New
#              Contact's picture (bbfeeds_new_contact of net_rim_bb_feeds_lib), a contact's default
#              picture (addressbook_contact), the group box and its title bar (border_groupboxfull,
#              border_groupboxtitlebar) and the form's field (border_form) as 9-patches, the card's
#              and the form's button images (edit, save), the short menu's sm_* images, unchanged
#   calculatrice calculator/res/drawable-nodpi/calc_*.png: what apps/calculator draws,
#              the display's three parts (calculator_display_left, _middle, _right, 91 px high) and the
#              key's four states (calculator_key_landscape, _numeric, ~focus, ~active, 62x85) as 9-patches
#   memopad    memopad/res/drawable-nodpi/mem_*.png: what apps/memopad draws, the text
#              field (border_form, a 9-patch) and the short menu's sm_* images
#   tasks      tasks/res/drawable-nodpi/tsk_*.png: what apps/tasks draws, the status strip
#              (net_rim_Task_Status-icons27x27: five 27 px cells), the text field and the short
#              menu's sm_* images; the priority strip (net_rim_Priority-icons9x27: three 9 px
#              cells), the reminder box's net_rim_task_reminder and the form's save
#   pictures   pictures/res/drawable-nodpi/pic_*.png: what apps/pictures draws, the
#              no-picture icon and thumbnail, the Camera row's icon and the short menus' sm_* images
#   bandeau    sysui/res/drawable-nodpi/banner_*.png: the indicators of an app's banner (battery,
#              signal, Wi-Fi, roaming, GPS, NFC, Bluetooth, alarm, profiles, counters), one frame
#              of the theme's strips each
#   indicateurs sysui/res/drawable-nodpi/status_*.png: the status icons of Android that the theme has
#              no image for (hotspot, VPN, TTY, data saver, cast, screen recording, camera,
#              microphone, sensors off, work profile, sync, rotation, headset, speakerphone, Wi-Fi
#              calling, Ethernet), drawn here in the style of the Ribbon strips, D
#   systeme    sysui/res/drawable-nodpi/lock_*.png: the lock screen's banner, indicator row,
#              speaker, padlock and Emergency handset; vol_*.png: the volume overlay's
#              background, bars and output icons; shutdown_busy.gif: the busy clock
#              of the screen while the phone powers off
#   fonds      wallpapers/*.png: the 9900's 14 shipped wallpapers (net_rim_bb_medialoader_backgrounds_640x480_02a_t
#              and _02b_t) and its default home image (background_homescreen.png), each cut to
#              720x720 (cover: scaled by 1.5, centre 720 px kept; docs/BBOS7-VISUEL.md, section 24);
#              tools/fonds.sh puts them on the phone
#   demarrage  bootanim/logo.png: the boot screen's logo, cut from sim-01-demarrage.png
#   bb10       nothing is copied any more: it only removes from this checkout what
#              it used to write of BB10, the Hub's hub_* and the search's bbia_* (launcher/res), the
#              test app's glyphs (essai/res), SystemUI's images
#
#   telephone  (only when asked) device/fonts.xml: adb pull of the phone's /system/etc/fonts.xml,
#              read-only, with device/fonts.xml.source (build fingerprint and sha256); after an OTA.
#              While a Bold7 or Cascade module is mounted, the pulled file is a derived one: kept out.
#
# Each block first removes what it wrote last time (its prefixes or its list; sons, the only writer
# of sounds/, that folder), never another block's files, then writes. Provenance in
# assets-private/MANIFEST.md. Nothing here writes into assets-private/.
set -euo pipefail
cd "$(dirname "$0")/.."

ASSETS=${ASSETS:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/assets-private}
OS7=$ASSETS/bbos7-9900
THEME=$OS7/theme/net_rim_theme_BlackBerry7_640x480_b
OUT=assets-gen

need() {
  for f; do
    [ -f "$f" ] || { echo "Missing $f: rerun the extraction tool that makes it (README.md, Build)" >&2; exit 1; }
  done
}

polices() {
  local src=$OS7/fonts/net_rim_font_latin_truetype dir=$OUT/module/system/fonts
  need "$src/BBAlphaSans.ttf"
  mkdir -p "$dir" "$OUT/device"
  rm -f "$dir"/BBAlpha*.ttf
  cp "$src"/BBAlpha*.ttf "$dir/"
  echo "polices: $(ls "$dir"/BBAlpha*.ttf | wc -l | tr -d ' ') BBAlpha TTF in $dir"
  # The 9900's fallbacks for the scripts BBAlpha lacks (tools/fonts_xml.py FALLBACKS), apart from the
  # BBAlpha ones: the build copies them as they are, without the line-metrics fix.
  local fb=$OUT/module/system/fonts-os7 fw
  mkdir -p "$fb"
  rm -f "$fb"/*.ttf
  for fw in latin_truetype/BBGlobalSans.ttf latin_truetype/BBGlobalSans-Bold.ttf latin_truetype/BBSemitic.ttf \
           thai/BBThai.ttf indic/UniversOTSDevanagari.ttf indic/UniversOTSBengali.ttf indic/UniversOTSGujarati.ttf \
           indic/UniversOTSTamil.ttf japanese_droid/DroidSansJapanese.ttf \
           monotype_hkscs/MYuenC-SemiBold-Big5HKSCS.ttf global_type/BBGlobalSansFallback.ttf; do
    need "$OS7/fonts/net_rim_font_$fw"
    cp "$OS7/fonts/net_rim_font_$fw" "$fb/"
  done
  echo "polices: $(ls "$fb" | wc -l | tr -d ' ') firmware fallback TTF in $fb"
  if [ ! -s "$OUT/device/fonts.xml" ]; then
    need "$ASSETS/device/fonts.xml"
    cp "$ASSETS/device/fonts.xml" "$OUT/device/fonts.xml"
    [ ! -f "$ASSETS/device/fonts.xml.source" ] || cp "$ASSETS/device/fonts.xml.source" "$OUT/device/"
    echo "polices: stock fonts.xml copied from $ASSETS/device"
  fi
}

# Theme image to resource name: resource names allow [a-z0-9_] only (tab~focus.png -> home_tab_focus.png).
HOME_IMAGES=(
  banner_background        # 640x93, the banner (uniform columns: widens to 720)
  banner_divider           # 640x1, its bottom line
  indicator_bar_background # 640x47, the indicator row under it (uniform columns)
  tab                      # 120x43, the view tabs' strip
  tabmanager_header_line_top tabmanager_header_line_bottom # 640x2, the strip's lines
  background_homescreen    # 640x480, the default wallpaper (sim-02)
  # The home screen. The banner's strips (frames side by side, rows of states), lowercased with "-" as "_":
  net_rim_Indicator_BatteryLevel-icons50x25 net_rim_Ribbon_SignalLevel-icons49x19
  net_rim_Ribbon_WlanSignalLevel-icons30x24 net_rim_Ribbon_Profile-icons43x43
  net_rim_Ribbon_UnreadCount-icons33x29
  net_rim_Alarm_Indicator-icons21x21 # 21x21, the alarm set, right of the battery
  banner_search_button     # 43x43, the indicator row's magnifier
  banner_voice_button      # 43x43, the universal search's microphone, at the right of its field (sim-54)
  homescreen_filter_background # 640x47, black at 40 %: under the tab and the collapsed row (C)
  homescreen_mask_p        # 640x47, the tab's dark edges over the neighbouring views' names
  folder                   # 92x92, a folder's icon in the grid
  net_rim_bb_ribbon_app    # 92x92, the Home Screen icon of the app switcher (sim-05)
  net_rim_Folder-icons32x32 # the open folder's header icon (4 frames: closed, closed, ..., open)
  background_folderview    # 480x39, the open folder's header band
  icon_move_underlay       # 90x95, the icon being moved (arrows)
  new_overlay              # 92x92, the red star over an icon with something new (sim-15)
  # The short menu's glyphs (82x55, core/ui draws them).
  sm_launch sm_delete sm_add_folder sm_edit_folder sm_delete_folder
  bs1/banner_background~focus # 640x92, the banner with the trackpad's focus (net_rim_theme_..._bs1)
  bs1/sm_mark_favourite bs1/sm_unmark_favourite bs1/sm_move bs1/sm_move_to_folder bs1/sm_hide
  bs1/sm_unhide bs1/sm_full_menu bs1/sm_folder_open bs1/sm_rename_folder
)

accueil() {
  local dir=$OUT/launcher/res/drawable-nodpi f src name
  for f in "${HOME_IMAGES[@]}"; do need "$THEME$( [ "${f#bs1/}" = "$f" ] || echo s1)/${f#bs1/}.png"; done
  mkdir -p "$dir"
  rm -f "$dir"/home_*.png
  for f in "${HOME_IMAGES[@]}"; do
    src=$THEME/$f.png
    [ "${f#bs1/}" = "$f" ] || src=${THEME}s1/${f#bs1/}.png
    name=$(printf '%s' "${f#bs1/}" | tr 'A-Z~-' 'a-z__')
    cp "$src" "$dir/home_$name.png"
  done
  # The home's trackpad focus but the banner's (speaker, notifications, magnifier, tab, icon):
  # icon_underlay~focus.png, 90x95, its rounded corners kept, the rest stretched (sim-79 to
  # sim-81: the speaker's 45x45 square has the image's corner pixels, C).
  need "$THEME/icon_underlay~focus.png"
  ninepatch "$THEME/icon_underlay~focus.png" "$dir/home_focus.9.png" 6 83 6 88
  echo "accueil: $(ls "$dir"/home_*.png | wc -l | tr -d ' ') theme images in $dir"
}

profils() {
  local dir=$OUT/launcher/res/drawable-nodpi pair src name
  mkdir -p "$dir"
  rm -f "$dir"/profile_*.png
  # theme name:resource name; the firmware's default is Normal, discreet Vibrate Only, quiet Silent, off All Alerts Off.
  for pair in default:normal discreet:vibrate quiet:silent loud:loud medium:medium phoneonly:phone_only off:alerts_off; do
    src=${pair%%:*}; name=${pair#*:}
    need "$THEME/net_rim_bb_profiles_app.$src.png" "$THEME/net_rim_bb_profiles_app.$src~focus.png"
    cp "$THEME/net_rim_bb_profiles_app.$src.png" "$dir/profile_$name.png"
    cp "$THEME/net_rim_bb_profiles_app.$src~focus.png" "$dir/profile_${name}_focus.png"
  done
  echo "profils: $(ls "$dir"/profile_*.png | wc -l | tr -d ' ') images in $dir"
}

connexions() {
  local dir=$OUT/launcher/res/drawable-nodpi strip=$THEME/net_rim_Manage_Connections-icons40x40.png
  need "$strip" "$THEME/border_groupboxfull.png" "$THEME/border_groupboxtitlebar.png"
  mkdir -p "$dir"
  rm -f "$dir"/connections_*
  cp "$strip" "$dir/connections_icons.png"
  # 20x64 each: the grey margin, the box's edge and corners kept, its white middle (6 to 13 across,
  # 6 to 59 down) stretched; the title bar keeps its last row, the #D4D4D4 line under the title.
  ninepatch "$THEME/border_groupboxfull.png" "$dir/connections_box.9.png" 6 13 6 59
  ninepatch "$THEME/border_groupboxtitlebar.png" "$dir/connections_title.9.png" 6 13 6 59
  echo "connexions: $(ls "$dir"/connections_* | wc -l | tr -d ' ') theme images in $dir"
}

options() {
  local dir=$OUT/launcher/res/drawable-nodpi pair
  mkdir -p "$dir"
  rm -f "$dir"/opt_*
  # resource name:theme image, the rows of Options' lists (sim-57, sim-58, sim-30 for the first ones;
  # the others matched by name to the guide's rows, D).
  for pair in sound:cat_sound_ringtones connectivity:cat_connectivity display:cat_display typing:cat_typing_input \
    device:cat_device calls:cat_callmanagement security:cat_security accessibility:cat_accessibility \
    phone_ringtone:cat_sound_phone_ringtone profile_sounds:cat_sound_current_profile contact_sounds:cat_sound_contact \
    profiles:cat_sound_profile_management screen_display:cat_display_screen_components date_time:cat_display_date_time \
    lock_message:cat_device_owner language:cat_typing_input_language typing_style:cat_text_input \
    trackpad:cat_typing_input_trackpad_sensitivity touchscreen:cat_typing_input_touchscreen_sensitivity \
    keyboard:cat_typing_input_keyboard dictionary:cat_typing_input_custom_dictionary \
    substitution:cat_typing_input_autotext spell_check:cat_typing_input_spellcheck \
    messages:cat_app_messages reminders:cat_help_calendar mobile:cat_mobile_network wifi:cat_connectivity_wifi \
    bluetooth:cat_connectivity_bluetooth nfc:cat_app_nfc about:cat_device_about_device memory:cat_device_memory \
    status:cat_device_status_info password:cat_security_password encryption:cat_security_encryption; do
    need "$THEME/${pair#*:}.png"
    cp "$THEME/${pair#*:}.png" "$dir/opt_${pair%%:*}.png"
  done
  # Advanced Style Options greyed (sim-91a): the button's 9-patch, as ui_button's.
  need "$THEME/border_button~disabled.png"
  ninepatch "$THEME/border_button~disabled.png" "$dir/opt_button_disabled.9.png" 20 39 26 96
  # Word Substitution's Replace and With: the form's field, as Contacts' (sim-184).
  need "$THEME/border_form.png"
  ninepatch "$THEME/border_form.png" "$dir/opt_field.9.png" 4 4 4 4
  # Wi-Fi Network: the signal strip of _bs1 (five 58x27 frames: off, none, one to
  # three bars) and the lock of a secured network; Bluetooth's device icons (six 42x42 frames).
  local bs1=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1
  need "$bs1/net_rim_WlanSignalLevel-icons58x27.png"
  cp "$bs1/net_rim_WlanSignalLevel-icons58x27.png" "$dir/opt_wifi_signal.png"
  need "$THEME/wi-fi_lock_icon.png"
  cp "$THEME/wi-fi_lock_icon.png" "$dir/opt_wifi_lock.png"
  need "$THEME/net_rim_bluetooth_device-icons42x42.png"
  cp "$THEME/net_rim_bluetooth_device-icons42x42.png" "$dir/opt_bt_devices.png"
  echo "options: $(ls "$dir"/opt_* | wc -l | tr -d ' ') theme images in $dir"
}

icones() {
  # Each source of the table (under assets-private/) copied unchanged under the icon's resource
  # name, and the template of the generated icon (core/icons Tile.kt). A source missing stops here.
  local table=core/icons/src/main/res/raw/bbos7_icons.tsv dir=$OUT/launcher/res/drawable-nodpi
  local icon source name
  mkdir -p "$dir"
  rm -f "$dir"/bbos7_*.png
  while IFS=$'\t' read -r _ _ icon source _; do
    source=${source%$'\r'}
    [ -f "$ASSETS/$source" ] || { echo "bbos7_icons.tsv: $icon, source $ASSETS/$source absente" >&2; exit 1; }
    cp "$ASSETS/$source" "$dir/$icon.png"
  done < <(grep -v -e '^#' -e '^[[:space:]]*$' "$table")
  for name in underlay mask overlay; do
    need "$THEME/shortcut_$name.png"
    cp "$THEME/shortcut_$name.png" "$dir/bbos7_shortcut_$name.png"
  done
  echo "icones: $(ls "$dir"/bbos7_*.png | wc -l | tr -d ' ') app icons and template images in $dir"
}

sons() {
  # The sources system/module/sounds.tsv names (second column, a <cod>/<path> of the extraction),
  # copied unchanged under the same paths, converted by the module's build.
  local src=$OS7/sounds source
  rm -rf "$OUT/sounds"
  while IFS=$'\t' read -r _ source _; do
    need "$src/$source"
    mkdir -p "$OUT/sounds/$(dirname "$source")"
    cp "$src/$source" "$OUT/sounds/$source"
  done < <(grep -v -e '^#' -e '^[[:space:]]*$' system/module/sounds.tsv)
  echo "sons: $(find "$OUT/sounds" -type f | wc -l | tr -d ' ') files in $OUT/sounds"
}

# One frame of a theme strip (name-iconsWxH.png, frames side by side, states in rows) as its own
# resource: frame <src> <w> <h> <column> <row> <name> [<crop of the frame>].
frame() {
  local src=$THEME/$1.png dir=$OUT/sysui/res/drawable-nodpi
  need "$src"
  magick "$src" -crop "$2x$3+$(($4 * $2))+$(($5 * $3))" +repage ${7:+-crop "$7" +repage} "PNG32:$dir/banner_$6.png"
}

bandeau() {
  # The indicators of an app's banner (banner.pme), one drawable per state, at the
  # theme's own size (docs/BBOS7-VISUEL.md §18).
  local dir=$OUT/sysui/res/drawable-nodpi i
  mkdir -p "$dir"
  rm -f "$dir"/banner_*.png
  # Battery, 12 columns by 3 rows (the first two alike, the third with the plug): 11 levels, then
  # "?" for an unknown level.
  for i in 0 1 2 3 4 5 6 7 8 9 10 11; do
    frame net_rim_Indicator_BatteryLevel-icons50x25 50 25 "$i" 0 "battery_$i"
    frame net_rim_Indicator_BatteryLevel-icons50x25 50 25 "$i" 2 "battery_charging_$i"
  done
  # Signal: OFF, X, 0 to 5 bars, SOS.
  frame net_rim_Ribbon_SignalLevel-icons49x19 49 19 0 0 signal_off
  frame net_rim_Ribbon_SignalLevel-icons49x19 49 19 1 0 signal_x
  for i in 0 1 2 3 4 5; do frame net_rim_Ribbon_SignalLevel-icons49x19 49 19 $((i + 2)) 0 "signal_$i"; done
  frame net_rim_Ribbon_SignalLevel-icons49x19 49 19 8 0 signal_sos
  # Wi-Fi: frame 0 is empty, 1 to 5 one arc more each.
  for i in 1 2 3 4 5; do frame net_rim_Ribbon_WlanSignalLevel-icons30x24 30 24 "$i" 0 "wifi_$i"; done
  frame net_rim_Ribbon_Roaming-icons28x28 28 28 1 0 roaming
  frame net_rim_Ribbon_GPS-icons43x24 43 24 0 0 gps_on
  frame net_rim_Ribbon_GPS-icons43x24 43 24 1 0 gps_active
  frame net_rim_Ribbon_NFC-icons12x24 12 24 1 0 nfc
  frame net_rim_bluetooth_banner-icons15x25 15 25 0 0 bluetooth_on
  frame net_rim_bluetooth_banner-icons15x25 15 25 1 0 bluetooth_connected
  frame net_rim_Alarm_Indicator-icons21x21 21 21 0 0 alarm
  # The call in progress and the muted call of the Phone's one-line banner,
  # frames 11 and 12 of the second cod's phone strip (found at (459, 2) on sim-109 and sim-136).
  frame ../net_rim_theme_BlackBerry7_640x480_bs1/net_rim_Phone-icons19x19 19 19 11 0 call
  frame ../net_rim_theme_BlackBerry7_640x480_bs1/net_rim_Phone-icons19x19 19 19 12 0 call_muted
  # Profiles (Normal, Vibrate Only, Silent, Loud, Phone Calls Only, All Alerts Off, custom,
  # Medium), the speaker's 33 rows only: an image of 43 would not fit a line of the banner.
  frame net_rim_Ribbon_Profile-icons43x43 43 43 1 0 profile_vibrate 43x33+0+5
  frame net_rim_Ribbon_Profile-icons43x43 43 43 2 0 profile_silent 43x33+0+5
  frame net_rim_Ribbon_Profile-icons43x43 43 43 4 0 profile_phone_only 43x33+0+5
  # Counters of an app's banner (GridIndicators font-size=32): mail, calendar, text message,
  # missed call.
  frame net_rim_Ribbon_UnreadCount-icons33x29 33 29 0 0 count_email
  frame net_rim_Ribbon_UnreadCount-icons33x29 33 29 4 0 count_calendar
  frame net_rim_Ribbon_UnreadCount-icons33x29 33 29 6 0 count_sms
  frame net_rim_Ribbon_UnreadCount-icons33x29 33 29 14 0 count_missed_call
  echo "bandeau: $(ls "$dir"/banner_*.png | wc -l | tr -d ' ') banner images in $dir"
}

# A theme image as an Android 9-patch: a 1 px border whose black lines mark the band that
# stretches, x0 to x1 and y0 to y1 of the image, inclusive (both edges of the image stay as drawn).
ninepatch() {
  local src=$1 out=$2 x0=$3 x1=$4 y0=$5 y1=$6 w h
  w=$(magick identify -format %w "$src")
  h=$(magick identify -format %h "$src")
  magick "$src" -background none -gravity center -extent "$((w + 2))x$((h + 2))" +antialias -fill black \
    -draw "line $((x0 + 1)),0 $((x1 + 1)),0" -draw "line 0,$((y0 + 1)) 0,$((y1 + 1))" "PNG32:$out"
}

ui() {
  local dir=$OUT/ui/res/drawable-nodpi essai=$OUT/essai/res/drawable-nodpi
  local more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 f
  need "$THEME/border_menu.png" "$THEME/border_popup.png" "$THEME/border_button.png" "$THEME/border_button~focus.png" \
    "$THEME/border_find.png" "$THEME/background_find_field.png" "$THEME/background_list_header.png" \
    "$THEME/net_rim_system_Check-icons27x27.png" "$more/sm_full_menu.png" "$THEME/arrow_choice.png" \
    "$THEME/arrow_choice~focus.png" "$THEME/border_inplace.png" "$THEME/border_spinboxbutton~focus.png" \
    "$OS7/images/net_rim_bb_sms/smileys_27x27.png"
  mkdir -p "$dir" "$essai"
  rm -f "$dir"/ui_* "$essai"/ui_sm_*.png
  # Full menu and dialog boxes, 40x40, uniform from 18 to 21 both ways (BBOS7-VISUEL §4, §12).
  ninepatch "$THEME/border_menu.png" "$dir/ui_menu.9.png" 18 21 18 21
  ninepatch "$THEME/border_popup.png" "$dir/ui_popup.9.png" 18 21 18 21
  # Buttons, 60x102: the 26 top rows (border, sheen, gradient) and the 5 bottom ones keep their
  # pixels, the flat middle shrinks (sim-19: 26 flat rows in a 51 px button, C).
  ninepatch "$THEME/border_button.png" "$dir/ui_button.9.png" 20 39 26 96
  ninepatch "$THEME/border_button~focus.png" "$dir/ui_button_focus.9.png" 20 39 26 96
  # The search field of Options, 31x25, white from 13 to 17 and 13 to 16 (sim-57).
  ninepatch "$THEME/border_find.png" "$dir/ui_find.9.png" 13 17 13 16
  # The band behind it and a date header: only their top rows show (60 and 56 px).
  magick "$THEME/background_find_field.png" -crop 640x60+0+0 +repage "PNG32:$dir/ui_find_background.png"
  magick "$THEME/background_list_header.png" -crop 640x56+0+0 +repage "PNG32:$dir/ui_list_header.png"
  cp "$THEME/net_rim_system_Check-icons27x27.png" "$dir/ui_check.png"
  # A choice field (Trackpad Sensitivity): its arrow, 20x8, black and white on the
  # focus; its open list, 25x25, uniform from 10 to 14; the chosen value, 60x72, uniform from 20
  # to 39 and 22 to 49.
  cp "$THEME/arrow_choice.png" "$dir/ui_choice_arrow.png"
  cp "$THEME/arrow_choice~focus.png" "$dir/ui_choice_arrow_focus.png"
  ninepatch "$THEME/border_inplace.png" "$dir/ui_choice_list.9.png" 10 14 10 14
  ninepatch "$THEME/border_spinboxbutton~focus.png" "$dir/ui_choice_chosen.9.png" 20 39 22 49
  for f in question exclamation information error; do cp "$THEME/dialog_$f.png" "$dir/ui_dialog_$f.png"; done
  cp "$more/sm_full_menu.png" "$dir/ui_sm_full_menu.png"
  # The 45 emoticons at 27 px (net_rim_bb_sms): in the rows of every list that shows a message's
  # text and in the thread's bubbles.
  cp "$OS7/images/net_rim_bb_sms/smileys_27x27.png" "$dir/ui_smileys_27.png"
  # A full menu's row that opens a submenu (Attach of the mail's composer, sim-292, C).
  need "$THEME/arrow_menu.png"
  cp "$THEME/arrow_menu.png" "$dir/ui_menu_arrow.png"
  # The demo's actions (tools/essai DemoActivity).
  for f in open mark_opened search compose switch_apps; do cp "$more/sm_$f.png" "$essai/ui_sm_$f.png"; done
  for f in delete to_top to_bottom; do cp "$THEME/sm_$f.png" "$essai/ui_sm_$f.png"; done
  echo "ui: $(ls "$dir"/ui_* | wc -l | tr -d ' ') theme images in $dir, $(ls "$essai"/ui_sm_*.png | wc -l | tr -d ' ') in $essai"
}

# The same, with its content (padding) band too, px0 to px1 and py0 to py1 of the image, inclusive:
# black on the bottom and right edges, which Android takes as the drawable's padding; what lies
# outside that band in red, its layout bounds, which Android gives as its optical insets (SystemUI
# sizes its dialogs with them, SystemUIDialog.getHorizontalInsets).
ninepatch_pad() {
  local src=$1 out=$2 w h
  ninepatch "$src" "$out" "$3" "$4" "$5" "$6"
  w=$(magick identify -format %w "$src")
  h=$(magick identify -format %h "$src")
  magick "$out" +antialias -fill black -draw "line $(($7 + 1)),$((h + 1)) $(($8 + 1)),$((h + 1))" \
    -draw "line $((w + 1)),$(($9 + 1)) $((w + 1)),$((${10} + 1))" \
    -fill red -draw "line 1,$((h + 1)) $7,$((h + 1))" -draw "line $(($8 + 2)),$((h + 1)) $w,$((h + 1))" \
    -draw "line $((w + 1)),1 $((w + 1)),$9" -draw "line $((w + 1)),$((${10} + 2)) $((w + 1)),$h" "PNG32:$out"
}

dialogues() {
  # What the framework overlay (system/overlays/android) draws the framework's
  # dialogs, menus and buttons with, the theme images core/ui draws its own with.
  local dir=$OUT/android/res/drawable-nodpi
  need "$THEME/border_popup.png" "$THEME/border_menu.png" "$THEME/border_button.png" "$THEME/border_button~focus.png" \
    "$THEME/border_inplace.png" "$THEME/border_spinboxbutton~focus.png"
  mkdir -p "$dir"
  rm -f "$dir"/bold7_*
  # The dialog box, 40x40: border pixels at x 4 and 35, y 2 and 33, its shadow around them (1 px
  # at the sides, 2 below). The content starts inside the border: x 5 to 34, y 3 to 32.
  ninepatch_pad "$THEME/border_popup.png" "$dir/bold7_box.9.png" 18 21 18 21 5 34 3 32
  # The full menu, 40x40: border at x 3 and 36, y 2 and 35; rows from 17 px under the top border
  # to 5 px over the bottom one (BBOS7-VISUEL §4), inside the side borders.
  ninepatch_pad "$THEME/border_menu.png" "$dir/bold7_menu.9.png" 18 21 18 21 4 35 19 29
  # Buttons, 60x102, stretched as core/ui's (ui block); their padding is the overlay's styles'.
  ninepatch "$THEME/border_button.png" "$dir/bold7_button.9.png" 20 39 26 96
  ninepatch "$THEME/border_button~focus.png" "$dir/bold7_button_focus.9.png" 20 39 26 96
  # The open list of a choice field, 25x25 (a 1 px border, rows inside it: content
  # 1 to 23 both ways), and its chosen value's band, 60x72, as core/ui's (ui block).
  ninepatch "$THEME/border_inplace.png" "$dir/bold7_inplace.9.png" 10 14 10 14
  magick "$dir/bold7_inplace.9.png" +antialias -fill black -draw "line 2,26 24,26" -draw "line 26,2 26,24" "PNG32:$dir/bold7_inplace.9.png"
  ninepatch "$THEME/border_spinboxbutton~focus.png" "$dir/bold7_chosen.9.png" 20 39 22 49
  # Without padding lines aapt2 takes the stretch region (here 20 px each side)
  # as the content area, and NumberPicker's background (this band) then padded its text field by
  # 20 px each side: "Dec" lost its "D". The whole image is the content: padding of 0.
  local cw ch
  cw=$(magick identify -format %w "$THEME/border_spinboxbutton~focus.png")
  ch=$(magick identify -format %h "$THEME/border_spinboxbutton~focus.png")
  magick "$dir/bold7_chosen.9.png" +antialias -fill black -draw "line 1,$((ch + 1)) $cw,$((ch + 1))" \
    -draw "line $((cw + 1)),1 $((cw + 1)),$ch" "PNG32:$dir/bold7_chosen.9.png"
  # The check box and radio button of the lists in a dialog, the 27x27 cells of
  # net_rim_system_Check-icons27x27.png (columns normal and focus; rows empty, checked, radio
  # empty, radio selected), as core/ui's check boxes (ui block).
  need "$THEME/net_rim_system_Check-icons27x27.png"
  local cell name x y
  for cell in check_off:0:0 check_off_focus:27:0 check_on:0:27 check_on_focus:27:27 \
    radio_off:0:108 radio_off_focus:27:108 radio_on:0:135 radio_on_focus:27:135; do
    IFS=: read -r name x y <<< "$cell"
    magick "$THEME/net_rim_system_Check-icons27x27.png" -crop "27x27+$x+$y" +repage "PNG32:$dir/bold7_$name.png"
  done
  echo "dialogues: $(ls "$dir"/bold7_* | wc -l | tr -d ' ') theme images in $dir"
  # The permission request's buttons (system/overlays/permissioncontroller) are
  # another package's overlay, which cannot name the framework overlay's drawables: its own copy.
  local pc=$OUT/permissioncontroller/res/drawable-nodpi
  mkdir -p "$pc"
  rm -f "$pc"/bold7_*
  cp "$dir"/bold7_button.9.png "$dir"/bold7_button_focus.9.png "$pc"/
}

messages() {
  # The inbox's status icons (the 42 px strips, whole: the app draws one cell, sim-53,
  # gsm 027, 028), its scroll arrows (gsm 027) and its short menu's images (82x55).
  local dir=$OUT/messages/res/drawable-nodpi more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 img
  need "$THEME/net_rim_Message-icons42x42.png" "$more/net_rim_Phone-icons42x42.png" \
    "$THEME/navigation_up_arrow.gif" "$THEME/navigation_down_arrow.gif"
  mkdir -p "$dir"
  rm -f "$dir"/msg_*.png
  cp "$THEME/net_rim_Message-icons42x42.png" "$dir/msg_message_icons.png"
  cp "$more/net_rim_Phone-icons42x42.png" "$dir/msg_phone_icons.png"
  magick "$THEME/navigation_up_arrow.gif" "PNG32:$dir/msg_scroll_up.png"
  magick "$THEME/navigation_down_arrow.gif" "PNG32:$dir/msg_scroll_down.png"
  # need() loops on f: another name here.
  # The thread's short menu (sm_chat_send, sm_clear_input, sm_cancel_generic found on
  # sim-52, RMSE 0.03 and 0.009), the bubbles' state icons (the 32 px strip, sim-50), the
  # contact's picture (sim-50), the field (border_reply_field.png, 31x25, white from 13 to 17 and
  # 13 to 16, sim-50) and the smiley button (40x40, sim-50).
  # Choose Contact's image, the address book's (sm_address_book_app, D: no capture).
  # The reading screen's short menu (sm_reply, sm_forward, sm_reply_all, D: no capture
  # of it) and Compose's Send and Save Draft.
  # Attach and Open Attachment (sm_add_attachment, sm_open_attachment), the picture
  # viewer's Save Image and Zoom (sm_save_image_browser, sm_zoom_in); D: no capture of these menus.
  # The list short menu's Options cell (sm_options_generic, the wrench of _bs1).
  for img in sm_open sm_mark_read sm_mark_unread sm_mark_prior_opened sm_compose sm_search sm_delete sm_select_folder \
    sm_chat_send sm_clear_input sm_cancel_generic sm_address_book_app sm_reply sm_forward sm_reply_all sm_save_draft \
    sm_add_attachment sm_open_attachment sm_save_image_browser sm_zoom_in sm_options_generic; do
    if [ -f "$more/$img.png" ]; then cp "$more/$img.png" "$dir/msg_$img.png"; else need "$THEME/$img.png"; cp "$THEME/$img.png" "$dir/msg_$img.png"; fi
  done
  need "$THEME/net_rim_Message-icons32x32.png" "$more/default_avatar.png" "$THEME/border_reply_field.png" "$THEME/smiley_button.png"
  cp "$THEME/net_rim_Message-icons32x32.png" "$dir/msg_thread_icons.png"
  cp "$more/default_avatar.png" "$dir/msg_avatar.png"
  cp "$THEME/smiley_button.png" "$dir/msg_smiley.png"
  ninepatch "$THEME/border_reply_field.png" "$dir/msg_field.9.png" 13 17 13 16
  # The icons of Messages Options' rows (sim-294, sim-295, C): Message Display
  # and Actions (cat_app_options_sub), Inbox Management, Email Account Management.
  need "$THEME/cat_app_options_sub.png" "$THEME/cat_app_messages_inbox_settings.png" "$THEME/cat_app_messages_email_acc_management.png"
  cp "$THEME/cat_app_options_sub.png" "$dir/msg_cat_display.png"
  cp "$THEME/cat_app_messages_inbox_settings.png" "$dir/msg_cat_inbox.png"
  cp "$THEME/cat_app_messages_email_acc_management.png" "$dir/msg_cat_accounts.png"
  # The 45 emoticons of the SMS app (net_rim_bb_sms), 45 px in the list Add Smiley
  # opens (gsm 024 and sim-145; the 27 px strip is core/ui's, block ui), the smiley button's focus
  # (sim-146) and Add Smiley's short menu image.
  local sms=$OS7/images/net_rim_bb_sms
  need "$sms/smileys_45x45.png" "$THEME/smiley_button_focus_indicator.png" "$more/sm_add_emoticon.png"
  cp "$sms/smileys_45x45.png" "$dir/msg_smileys_45.png"
  cp "$THEME/smiley_button_focus_indicator.png" "$dir/msg_smiley_focus.png"
  cp "$more/sm_add_emoticon.png" "$dir/msg_sm_add_emoticon.png"
  # Compose Email's send button (send_email.png in border_button, sim-46, C) and the
  # setup's fields (border_form, as Contacts' form, sim-184, C).
  need "$THEME/send_email.png" "$THEME/border_form.png"
  cp "$THEME/send_email.png" "$dir/msg_send_email.png"
  ninepatch "$THEME/border_form.png" "$dir/msg_form.9.png" 4 4 4 4
  # The attachment in the field, the theme's attachment_*.png (52x52; D: no capture
  # of an attachment in a text's field).
  for img in attachment_generic_picture attachment_generic_video attachment_music attachment_contact attachment_calendar attachment_unknown; do
    need "$THEME/$img.png"
    cp "$THEME/$img.png" "$dir/msg_$img.png"
  done
  # The picker's folder (folder.png of the theme, 92 px, drawn at the 52 px of the
  # file icons; D: no capture of OS 7's file explorer in a list).
  need "$THEME/folder.png"
  magick "$THEME/folder.png" -resize 52x52 "$dir/msg_folder.png"
  # The media player's controls (guide 7.1 p. 139, B: play, pause, stop, next,
  # previous), the theme's glyphs of bgcontrols_playpause_landscape and their focused ones.
  for img in play pause stop previous play_focus pause_focus stop_focus previous_focus next_disabled; do
    need "$THEME/$img.png"
    cp "$THEME/$img.png" "$dir/msg_player_$img.png"
  done
  echo "messages: $(ls "$dir"/msg_*.png | wc -l | tr -d ' ') theme images in $dir"
}

saisie() {
  # What apps/saisie reads from its assets. No French substitution list exists in the
  # firmware: ours is in git (apps/saisie/src/main/assets/substitution/fr.tsv).
  local src=$ASSETS/saisie/dictionnaires dir=$OUT/saisie/assets
  need "$src/autotext_en.tsv" "$src/merged_fr.tsv" "$src/merged_en.tsv"
  rm -rf "$dir"
  mkdir -p "$dir/substitution" "$dir/dictionary"
  cp "$src/autotext_en.tsv" "$dir/substitution/en.tsv"
  cp "$src/merged_fr.tsv" "$dir/dictionary/fr.tsv"
  cp "$src/merged_en.tsv" "$dir/dictionary/en.tsv"
  echo "saisie: $(grep -c . "$dir/substitution/en.tsv") substitutions, $(wc -l < "$dir/dictionary/fr.tsv" | tr -d ' ') and $(wc -l < "$dir/dictionary/en.tsv" | tr -d ' ') words in $dir"
}

phone() {
  # Phone (docs/BBOS7-VISUEL.md, section 26): the call screen of sim-18 and sim-109 (its
  # background, avatar frame, bottom bar and its focus, the buttons' 50x40 images, the volume's
  # icon and 11-frame strip), the Phone screen's call log icons (the 42 px strip, whole) and the
  # short menu's sm_* images; the contacts button's card at the right of the Phone screen's field
  # (sim-141). Theme _bs1 first, as the messages block, then _b.
  local dir=$OUT/phone/res/drawable-nodpi more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 src out img
  mkdir -p "$dir"
  rm -f "$dir"/ph_*.png
  # source name in the theme, then the name the app reads (ph_ prefix).
  while read -r src out; do
    if [ -f "$more/$src.png" ]; then img=$more/$src.png; else img=$THEME/$src.png; need "$img"; fi
    cp "$img" "$dir/ph_$out.png"
  done <<'LIST'
background_calldisplay background
activecall_avatar avatar
incomingcall_button_base_l bar
incomingcall_button_l~focus bar_focus
phone_speakerphone speaker
phone_handset handset
phone_mute mute
phone_unmute unmute
phone_hold hold
phone_resume resume
phone_conference add
phone_silence_ringtones ringer_off
activecall_button_mute_p mute_on
net_rim_Phone_Receiver-icons58x33 receiver
volume_icon volume_icon
net_rim_Phone_Volume-icons110x6 volume
net_rim_Phone-icons42x42 log_icons
sm_call sm_call
sm_drop_call sm_drop_call
sm_mute sm_mute
sm_unmute sm_unmute
sm_speaker_on sm_speaker_on
sm_speaker_off sm_speaker_off
sm_new_call sm_new_call
address_book_shortcut contacts
LIST
  echo "phone: $(ls "$dir"/ph_*.png | wc -l | tr -d ' ') theme images in $dir"
}

clock() {
  # Clock (docs/BBOS7-VISUEL.md, section 27): the Clock's analog face (face_370, the hands'
  # eight images each, hour_370_* and minute_370_*), the stopwatch's (face_316, minute_316_* and
  # second_316_*), net_rim_bb_clock_faces_480 and _lib; the alarm bar and the buttons
  # (border_clock_button and ~focus, 13x24: their dark rows 17 to 20 stretch, sim-153), the bar's
  # indicators (Alarm_Indicator and Snooze_Indicator, 73x48), the ring dialog's icon
  # (net_rim_alarm_reminder, 80x80), the buttons' icons (timer_start, timer_stop, timer_lap,
  # timer_reset, 60x35); and the white "add an alarm" indicator, absent from the extraction, cut
  # from sim-153 (x 287, y 417, 64x39) over the bar's #090909, its luminance made its alpha.
  local dir=$OUT/clock/res/drawable-nodpi more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1
  local faces=$OS7/images/net_rim_bb_clock_faces_480/net/rim/device/apps/internal/clock/face/analog/images/size370
  local lib=$OS7/images/net_rim_bb_clock_lib/net/rim/device/apps/internal/clock/images/size316
  local sim=$ASSETS/reference-bbos7/simulateur/sim-153-horloge.png i a
  need "$faces/face_370.png" "$lib/face_316.png" "$THEME/border_clock_button.png" "$THEME/border_clock_button~focus.png" \
    "$more/Alarm_Indicator.png" "$THEME/Snooze_Indicator.png" "$THEME/net_rim_alarm_reminder.png" \
    "$THEME/timer_start.png" "$THEME/timer_stop.png" "$more/timer_lap.png" "$more/timer_reset.png" "$sim"
  mkdir -p "$dir"
  rm -f "$dir"/clk_*.png
  cp "$faces/face_370.png" "$dir/clk_face.png"
  cp "$lib/face_316.png" "$dir/clk_watch_face.png"
  for i in 0 1 2 3 4 5 6 7; do
    a=$((i * 6))
    cp "$faces/hour_370_$a.png" "$dir/clk_hour_$i.png"
    cp "$faces/minute_370_$a.png" "$dir/clk_minute_$i.png"
    cp "$lib/minute_316_$a.png" "$dir/clk_watch_minute_$i.png"
    cp "$lib/second_316_$a.png" "$dir/clk_watch_second_$i.png"
  done
  ninepatch "$THEME/border_clock_button.png" "$dir/clk_bar.9.png" 6 6 17 20
  ninepatch "$THEME/border_clock_button~focus.png" "$dir/clk_bar_focus.9.png" 6 6 17 20
  cp "$more/Alarm_Indicator.png" "$dir/clk_alarm_on.png"
  cp "$THEME/Snooze_Indicator.png" "$dir/clk_snoozed.png"
  cp "$THEME/net_rim_alarm_reminder.png" "$dir/clk_reminder.png"
  cp "$THEME/timer_start.png" "$dir/clk_start.png"
  cp "$THEME/timer_stop.png" "$dir/clk_stop.png"
  cp "$more/timer_lap.png" "$dir/clk_lap.png"
  cp "$more/timer_reset.png" "$dir/clk_reset.png"
  # The LCD digital face's four sizes (t, none, j, m: 72, 100, 129 and 160 px wide
  # digits), clk_lcd_<t|s|j|m>_<0-9|colon>.png; the Clock draws the largest that fits.
  local lcd=$OS7/images/net_rim_bb_clock_faces_480/net/rim/device/apps/internal/clock/face/lcddigital/images set name
  need "$lcd/0.png" "$lcd/colon.png" "$lcd/t0.png" "$lcd/j0.png" "$lcd/m0.png" "$lcd/mcolon.png"
  for set in t "" j m; do
    name=${set:-s}
    for i in 0 1 2 3 4 5 6 7 8 9 colon; do cp "$lcd/$set$i.png" "$dir/clk_lcd_${name}_$i.png"; done
  done
  magick "$sim" -crop 64x39+287+417 +repage -colorspace gray -fx '(u-9/255)/(246/255)' \
    \( +clone -fill white -colorize 100 \) +swap -compose CopyOpacity -composite "PNG32:$dir/clk_alarm_add.png"
  echo "clock: $(ls "$dir"/clk_*.png | wc -l | tr -d ' ') images in $dir"
}

calendar() {
  # Calendar (docs/BBOS7-VISUEL.md, section 29): the reminder box's icon
  # (net_rim_calendar_reminder, 80x80, sim-245), the bell of an appointment with a reminder (first
  # frame of net_rim_Reminder_Status-icons19x19, sim-237), the band's arrows (arrow_more_rl and
  # arrow_more, 4x8, sim-220) and the form's Save mark (save, 52x31, sim-228).
  local dir=$OUT/calendar/res/drawable-nodpi
  need "$THEME/net_rim_calendar_reminder.png" "$THEME/net_rim_Reminder_Status-icons19x19.png" "$OS7/images/net_rim_bb_otaupgrade/arrow_more.png" \
    "$THEME/save.png"
  mkdir -p "$dir"
  rm -f "$dir"/cal_*.png
  cp "$THEME/net_rim_calendar_reminder.png" "$dir/cal_reminder.png"
  magick "$THEME/net_rim_Reminder_Status-icons19x19.png" -crop 19x19+0+0 +repage "PNG32:$dir/cal_bell.png"
  # The theme's arrow_more and arrow_more_rl are fully transparent (a one-entry palette with alpha 0),
  # so nothing was drawn; the 5x8 triangle of net_rim_bb_otaupgrade is the same
  # shape, drawn light on sim-220 and sim-225 (C): tinted light grey, mirrored for the left arrow.
  magick "$OS7/images/net_rim_bb_otaupgrade/arrow_more.png" -fill '#E6E6E6' -colorize 100 "PNG32:$dir/cal_arrow_right.png"
  magick "$dir/cal_arrow_right.png" -flop "PNG32:$dir/cal_arrow_left.png"
  cp "$THEME/save.png" "$dir/cal_save.png"
  # The Week view's all-day row marks its hours' column with a small green clock (sim-224, C: no
  # file of the firmware matches it), cut from the capture on its flat #BBBDBF.
  magick "$ASSETS/reference-bbos7/simulateur/sim-224-calendrier-semaine.png" -crop 29x29+19+164 +repage "PNG32:$dir/cal_allday.png"
  echo "calendar: $(ls "$dir"/cal_*.png | wc -l | tr -d ' ') theme images in $dir"
}

contacts() {
  # Contacts (docs/BBOS7-VISUEL.md, section 30): New Contact's picture, the green plus of sim-178
  # (C), found in the feeds library at 102x102 (A); the boxes, as Options' (sim-182, sim-194, C);
  # the form's fields (border_form, 9x9, its middle stretched; sim-184, C); the short menu's images.
  # Theme _bs1 first, as the phone block, then _b.
  local dir=$OUT/contacts/res/drawable-nodpi more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 src out img
  local plus=$OS7/images/net_rim_bb_feeds_lib/res/bbfeeds_new_contact.png
  need "$plus" "$THEME/border_groupboxfull.png" "$THEME/border_groupboxtitlebar.png" "$THEME/border_form.png"
  mkdir -p "$dir"
  rm -f "$dir"/cnt_*.png
  cp "$plus" "$dir/cnt_new.png"
  ninepatch "$THEME/border_groupboxfull.png" "$dir/cnt_box.9.png" 6 13 6 59
  ninepatch "$THEME/border_groupboxtitlebar.png" "$dir/cnt_title.9.png" 6 13 6 59
  ninepatch "$THEME/border_form.png" "$dir/cnt_field.9.png" 4 4 4 4
  while read -r src out; do
    if [ -f "$more/$src.png" ]; then img=$more/$src.png; else img=$THEME/$src.png; need "$img"; fi
    cp "$img" "$dir/cnt_$out.png"
  done <<'LIST'
sm_call sm_call
sm_sms sm_sms
sm_compose sm_compose
sm_view_contact sm_view_contact
sm_add_contact sm_add_contact
sm_edit sm_edit
sm_delete sm_delete
sm_copy sm_copy
sm_select sm_select
addressbook_contact picture
edit edit
save save
LIST
  echo "contacts: $(ls "$dir"/cnt_*.png | wc -l | tr -d ' ') theme images in $dir"
}

calculatrice() {
  # Calculator (docs/BBOS7-VISUEL.md, section 31): the display's green pill in three parts, the
  # keys in their four states; the middle band of a key stretches (A: the images; no capture, D).
  local dir=$OUT/calculator/res/drawable-nodpi
  need "$THEME/calculator_display_left.png" "$THEME/calculator_display_middle.png" "$THEME/calculator_display_right.png" \
    "$THEME/calculator_key_landscape.png" "$THEME/calculator_key_landscape_numeric.png" \
    "$THEME/calculator_key_landscape~focus.png" "$THEME/calculator_key_landscape~active.png"
  mkdir -p "$dir"
  rm -f "$dir"/calc_*.png
  cp "$THEME/calculator_display_left.png" "$dir/calc_display_left.png"
  cp "$THEME/calculator_display_middle.png" "$dir/calc_display_middle.png"
  cp "$THEME/calculator_display_right.png" "$dir/calc_display_right.png"
  ninepatch "$THEME/calculator_key_landscape.png" "$dir/calc_key.9.png" 14 47 24 60
  ninepatch "$THEME/calculator_key_landscape_numeric.png" "$dir/calc_key_digit.9.png" 14 47 24 60
  ninepatch "$THEME/calculator_key_landscape~focus.png" "$dir/calc_key_focus.9.png" 14 47 24 60
  ninepatch "$THEME/calculator_key_landscape~active.png" "$dir/calc_key_active.9.png" 14 47 24 60
  echo "calculatrice: $(ls "$dir"/calc_*.png | wc -l | tr -d ' ') theme images in $dir"
}

# The short menu's images and the form's field, shared by MemoPad and Tasks.
pim_images() {
  local dir=$1 prefix=$2 list=$3 more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 src img
  need "$THEME/border_form.png"
  mkdir -p "$dir"
  rm -f "$dir/$prefix"_*.png "$dir/$prefix"_*.9.png
  ninepatch "$THEME/border_form.png" "$dir/${prefix}_field.9.png" 4 4 4 4
  for src in $list; do
    if [ -f "$more/$src.png" ]; then img=$more/$src.png; else img=$THEME/$src.png; need "$img"; fi
    cp "$img" "$dir/${prefix}_$src.png"
  done
}

memopad() {
  # MemoPad (docs/BBOS7-VISUEL.md, section 32): the short menu's images (D for which; sm_edit,
  # the pencil of sim-265, C) and the form's Save mark (sim-255, C).
  local dir=$OUT/memopad/res/drawable-nodpi
  need "$THEME/save.png"
  pim_images "$dir" mem "sm_new sm_delete sm_open sm_edit"
  cp "$THEME/save.png" "$dir/mem_save.png"
  echo "memopad: $(ls "$dir"/mem_*.png | wc -l | tr -d ' ') theme images in $dir"
}

tasks() {
  # Tasks (docs/BBOS7-VISUEL.md, section 33): the status strip of the task list; then the
  # priority strip (9x27 cells, sim-277), the reminder box's image and the form's Save mark (sim-268).
  local dir=$OUT/tasks/res/drawable-nodpi
  need "$THEME/net_rim_Task_Status-icons27x27.png" "$THEME/net_rim_Priority-icons9x27.png" \
    "$THEME/net_rim_task_reminder.png" "$THEME/save.png"
  pim_images "$dir" tsk "sm_new_task sm_delete sm_open sm_mark_completed"
  cp "$THEME/net_rim_Task_Status-icons27x27.png" "$dir/tsk_status.png"
  cp "$THEME/net_rim_Priority-icons9x27.png" "$dir/tsk_priority.png"
  cp "$THEME/net_rim_task_reminder.png" "$dir/tsk_reminder.png"
  cp "$THEME/save.png" "$dir/tsk_save.png"
  echo "tasks: $(ls "$dir"/tsk_*.png | wc -l | tr -d ' ') theme images in $dir"
}

pictures() {
  # Pictures (docs/BBOS7-VISUEL.md, section 37): what apps/pictures draws, unchanged: a category's
  # no-picture icon (noart_picture_icon, sim-344, C), a thumbnail that cannot be read
  # (noart_picture_icon_thumbnail, 156 px, the grid's cell of sim-345), the Camera row's icon (the
  # camera app's, net_rim_bb_camera.Camera) and the short menus' sm_* images (sim-348, sim-352, sim-356, sim-373, C;
  # ps 33262, C, for the picture's own actions; sm_rotate, A, D for the match).
  local dir=$OUT/pictures/res/drawable-nodpi more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 src img
  mkdir -p "$dir"
  rm -f "$dir"/pic_*.png
  for src in noart_picture_icon noart_picture_icon_thumbnail net_rim_bb_camera.Camera sm_play sm_set_as_wallpaper \
    sm_set_as_caller_id sm_send_or_share_image sm_delete sm_zoom_in sm_rotate; do
    if [ -f "$more/$src.png" ]; then img=$more/$src.png; else img=$THEME/$src.png; need "$img"; fi
    cp "$img" "$dir/pic_$(echo "$src" | tr 'A-Z.' 'a-z_').png"
  done
  echo "pictures: $(ls "$dir"/pic_*.png | wc -l | tr -d ' ') theme images in $dir"
}

systeme() {
  # The 9900's lock screen, dialogs and boot screen
  # images, which then leave the bb10 block.
  local sysui=$OUT/sysui/res/drawable-nodpi i name
  need "$THEME/banner_background.png" "$THEME/banner_divider.png" "$THEME/indicator_bar_background.png" \
    "$THEME/net_rim_Ribbon_Profile-icons43x43.png" "$THEME/net_rim_KeyLockSystem.png" "$THEME/phone_emergency-icons32x32.png"
  mkdir -p "$sysui"
  # Lock screen (docs/BBOS7-VISUEL.md §10): lock_*.png, also what BB10's lock screen left under that prefix.
  rm -f "$sysui"/lock_*.png
  # The lock screen's banner and indicator row, the home's (sim-64, lockscreen.pme, A).
  cp "$THEME/banner_background.png" "$sysui/lock_banner_background.png"
  cp "$THEME/banner_divider.png" "$sysui/lock_banner_divider.png"
  cp "$THEME/indicator_bar_background.png" "$sysui/lock_indicator_bar.png"
  # The row's speaker, whole (43x43), one per profile in the strip's order: Normal, Vibrate Only,
  # Silent, Loud, Phone Calls Only, All Alerts Off, the custom profile (not made), Medium.
  i=0
  for name in normal vibrate silent loud phone_only alerts_off - medium; do
    [ "$name" = - ] || magick "$THEME/net_rim_Ribbon_Profile-icons43x43.png" -crop "43x43+$((i * 43))+0" +repage "PNG32:$sysui/lock_profile_$name.png"
    i=$((i + 1))
  done
  # The padlock at the bottom right (sim-64) and the handset of the Emergency button (sim-65).
  cp "$THEME/net_rim_KeyLockSystem.png" "$sysui/lock_padlock.png"
  cp "$THEME/phone_emergency-icons32x32.png" "$sysui/lock_emergency.png"

  # Volume overlay (docs/BBOS7-VISUEL.md §14): the volume overlay of volume_overlay.pme (sim-108):
  # vol-bg, the 16 bars of absoluteVolume15 (vol-0, then vol-001 to vol-015), the four outputs and
  # their -mute, which the theme's second cod (bs1) holds. The power key's dialog and the status
  # messages are core/ui's (block ui); the apps open without a splash: no image.
  local more=$OS7/theme/net_rim_theme_BlackBerry7_640x480_bs1 f
  need "$THEME/vol-bg.png" "$THEME/vol-0.png" "$THEME/vol-015.png" "$more/vol-loudspeaker-mute.png"
  rm -f "$sysui"/vol_*.png
  for f in bg 0 001 002 003 004 005 006 007 008 009 010 011 012 013 014 015 loudspeaker headset handset; do
    cp "$THEME/vol-$f.png" "$sysui/vol_$f.png"
  done
  cp "$more/vol-bluetooth.png" "$sysui/vol_bluetooth.png"
  for f in loudspeaker headset handset bluetooth; do cp "$more/vol-$f-mute.png" "$sysui/vol_${f}_mute.png"; done

  # Power-off screen (docs/BBOS7-VISUEL.md §23): the screen while the phone powers off or restarts,
  # black with OS 7's busy clock, the theme's osicon_hourglass.gif (35x35, 12 frames of 100 ms),
  # copied unchanged: aapt2 copies a GIF as it is, and Resources gives it as an AnimatedImageDrawable.
  need "$THEME/osicon_hourglass.gif"
  rm -f "$sysui"/shutdown_*.gif
  cp "$THEME/osicon_hourglass.gif" "$sysui/shutdown_busy.gif"
  echo "systeme: $(ls "$sysui"/lock_*.png | wc -l | tr -d ' ') lock screen and $(ls "$sysui"/vol_*.png | wc -l | tr -d ' ') volume images in $sysui"
}

demarrage() {
  # The logo of the 9900's boot screen, cut out of the simulator's native snapshot
  # (x 154, y 190, 332x58, docs/BBOS7-VISUEL.md section 11). The bar is drawn by tools/bootanim.py.
  local src=$ASSETS/reference-bbos7/simulateur/sim-01-demarrage.png dir=$OUT/bootanim
  need "$src"
  mkdir -p "$dir"
  rm -f "$dir"/logo.png
  magick "$src" -crop 332x58+154+190 +repage -strip "$dir/logo.png"
  echo "demarrage: logo in $dir"
}

bb10() {
  local sysui=$OUT/sysui/res/drawable-nodpi launcher=$OUT/launcher/res/drawable-nodpi
  local essai=$OUT/essai/res/drawable-nodpi
  # What this block wrote before, all BB10: nothing reads it. lock_*.png are the systeme block's
  # now; slate_clock.ttf, BB10's lock clock, is removed too.
  rm -f "$sysui"/shutdown_button_*.png "$sysui"/volume_*.png "$sysui"/emergency_call.png "$sysui"/core_toast_bg.9.png \
    "$sysui"/default_psplash.jpg "$OUT/sysui/res/font/slate_clock.ttf" \
    "$launcher"/nav_*.png "$launcher"/hub_*.png "$launcher"/bbia_*.png "$essai"/hub_*.png "$essai"/phone_*.png
  rm -f "$OUT"/bootanim/bb10_boot "$OUT"/bootanim/bb10_boot.conf "$OUT"/bootanim/BB10_splash.png
  rm -f "$sysui"/indicator_*.png "$sysui"/statusbar_bg_gradient.png
  [ ! -d "$OUT/sysui/res/font" ] || rmdir "$OUT/sysui/res/font"
  echo "bb10: nothing of BB10 is copied any more; old files removed"
}

telephone() {
  mkdir -p "$OUT/device"
  adb get-state >/dev/null
  local tmp
  tmp=$(mktemp)
  # shellcheck disable=SC2064
  trap "rm -f '$tmp'" EXIT
  adb pull /system/etc/fonts.xml "$tmp" >/dev/null
  if grep -q -e BBAlpha -e SlatePro "$tmp"; then
    echo "telephone: the phone's fonts.xml is a module's, kept $OUT/device/fonts.xml" >&2
    return
  fi
  mv "$tmp" "$OUT/device/fonts.xml"
  { echo "adb pull /system/etc/fonts.xml"
    echo "fingerprint $(adb shell getprop ro.build.fingerprint | tr -d '\r')"
    echo "sha256 $(shasum -a 256 "$OUT/device/fonts.xml" | cut -d' ' -f1)"
  } > "$OUT/device/fonts.xml.source"
  cat "$OUT/device/fonts.xml.source"
}

fonds() {
  local out=$OUT/wallpapers f
  need "$THEME/background_homescreen.png"
  mkdir -p "$out"
  rm -f "$out"/*.png
  # Same rule for all: no distortion, scale to cover (480 to 720 px high, so 1.5), keep the centre
  # 720 px of the 960. Lanczos; the few near-opaque PNGs (alpha >= 0.96) are flattened on black.
  for f in "$THEME/background_homescreen.png" "$OS7"/images/net_rim_bb_medialoader_backgrounds_640x480_02[ab]_t/pictures/*.png; do
    need "$f"
    name=$(basename "$f" .png)
    [ "$name" != background_homescreen ] || name=Default
    magick "$f" -background black -alpha remove -alpha off -filter Lanczos -resize 720x720^ \
      -gravity center -extent 720x720 -strip "$out/$name.png"
  done
  [ "$(ls "$out" | wc -l | tr -d ' ')" = 15 ] || { echo "fonds: expected 15 wallpapers" >&2; exit 1; }
  echo "fonds: 15 wallpapers 720x720 in $out"
}

# One drawn glyph of the indicators block: $1 name, $2 width, $3 the ImageMagick -draw commands at
# 1x (a 24 px high canvas, 2 px white strokes, no fill unless a command sets it), drawn at 8x and
# scaled down for the antialiasing of the theme's own strips.
glyph() {
  local dir=$OUT/sysui/res/drawable-nodpi
  magick -size "$(($2 * 8))x192" xc:none -stroke white -strokewidth 2 -fill none -draw "scale 8,8 $3" \
    -filter Lanczos -resize "${2}x24!" "PNG32:$dir/status_$1.png"
}

indicateurs() {
  # The status icons of Android the 9900's theme has no image for, drawn in the style
  # of the net_rim_Ribbon_* strips (white line art, 2 px strokes, about 24 px high). Not from the
  # firmware: all D (docs/BBOS7-VISUEL.md §18, "Status indicators with no image in the theme").
  local dir=$OUT/sysui/res/drawable-nodpi
  mkdir -p "$dir"
  rm -f "$dir"/status_*.png
  # Access point: a dot and two arcs each side.
  glyph hotspot 24 "circle 12,12 12,13.5 arc 5,5 19,19 130,230 arc 5,5 19,19 310,50 arc 1,1 23,23 140,220 arc 1,1 23,23 320,40"
  # VPN: a key.
  glyph vpn 22 "circle 7,12 7,8.5 line 10.5,12 20,12 line 16,12 16,17 line 20,12 20,16"
  # TTY: a teletypewriter, its paper strip over the body and a row of keys.
  glyph tty 26 "roundrectangle 2,10 24,21 2,2 rectangle 6,3 20,8 line 6,15 8,15 line 11,15 13,15 line 16,15 18,15 line 7,18 19,18"
  # Data saver: a downward arrow in a circle.
  glyph data_saver 24 "circle 12,12 12,2.5 line 12,6.5 12,16 polyline 8,12.5 12,16.5 16,12.5 line 8,18 16,18"
  # Cast: a screen, its lower left corner open on three arcs.
  glyph cast 26 "polyline 2,16 2,4 24,4 24,20 12,20 arc -6,8 14,28 270,360 arc -10,4 18,32 270,360 circle 2.5,21.5 2.5,22"
  # Screen recording: a ring and its dot.
  glyph screen_record 24 "circle 12,12 12,3 fill white circle 12,12 12,8.5"
  # Camera in use.
  glyph camera 26 "roundrectangle 2,7 24,21 2,2 polyline 8,7 10,4 16,4 18,7 circle 13,14 13,10.5"
  # Microphone in use.
  glyph microphone 16 "roundrectangle 5,2 11,13 3,3 arc 2,5 14,19 0,180 line 8,19 8,22 line 4,22 12,22"
  # Sensors turned off: a ring struck through.
  glyph sensors_off 24 "circle 12,12 12,3 line 4,20 20,4"
  # Work profile: a briefcase.
  glyph managed_profile 22 "roundrectangle 2,8 20,21 2,2 polyline 7,8 7,4 15,4 15,8 line 2,14 20,14"
  # Synchronisation: two arcs turning, an arrowhead on each; failing, with an exclamation mark.
  local sync="arc 3,3 21,21 200,330 arc 3,3 21,21 20,150 fill white stroke-width 1 polygon 16,7 23,6 19.5,12.5 polygon 8,17 1,18 4.5,11.5 fill none stroke-width 2"
  glyph sync_active 24 "$sync"
  glyph sync_failing 24 "$sync line 12,8 12,13 line 12,15.5 12,16"
  # Rotation: a phone and the arc turning around it.
  glyph rotate 24 "roundrectangle 7,5 17,19 1,1 arc 1,1 23,23 300,60 polyline 20,2 22,6.5 17,8"
  # Headset: the band over two ear cups.
  glyph headset 24 "arc 3,3 21,21 180,360 line 3,12 3,14 line 21,12 21,14 roundrectangle 2,13 6,21 1,1 roundrectangle 18,13 22,21 1,1"
  # Speakerphone: the profile speaker's shape, smaller, with its waves.
  glyph speakerphone 26 "polygon 2,9 7,9 13,4 13,20 7,15 2,15 arc 9,6 21,18 -50,50 arc 5,2 25,22 -50,50"
  # Wi-Fi calling: a Wi-Fi fan and a handset; not registered: the same, struck through.
  local fan="circle 8,19 8,20 arc 2,12 14,24 225,315 arc -2,8 18,28 225,315"
  glyph call_strength 26 "$fan roundrectangle 19,5 25,19 2,2"
  glyph no_calling 26 "$fan roundrectangle 19,5 25,19 2,2 line 2,3 24,23"
  # Ethernet: a plug and its contacts.
  glyph ethernet 24 "rectangle 4,10 20,20 rectangle 9,4 15,10 line 8,14 8,17 line 12,14 12,17 line 16,14 16,17"
  echo "indicateurs: $(ls "$dir"/status_*.png | wc -l | tr -d ' ') status images in $dir"
}

BLOCKS=(polices accueil profils connexions options icones sons ui dialogues messages phone clock calendar contacts calculatrice memopad tasks pictures saisie bandeau indicateurs systeme demarrage bb10 fonds)
[ $# -eq 0 ] || BLOCKS=("$@")
for b in "${BLOCKS[@]}"; do
  case $b in
    polices|accueil|profils|connexions|options|icones|sons|ui|dialogues|messages|phone|clock|calendar|contacts|calculatrice|memopad|tasks|pictures|saisie|bandeau|indicateurs|systeme|demarrage|bb10|fonds|telephone) "$b" ;;
    *) echo "Unknown block $b (polices accueil profils connexions options icones sons ui dialogues messages phone clock calendar contacts calculatrice memopad tasks pictures saisie bandeau indicateurs systeme demarrage bb10 fonds telephone)" >&2; exit 2 ;;
  esac
done
