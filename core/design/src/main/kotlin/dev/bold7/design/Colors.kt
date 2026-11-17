package dev.bold7.design

import kotlin.math.roundToInt

// Colors are ARGB Ints, what Android views take. BlackBerry OS 7.1 on the Bold 9900, sourced in
// docs/BBOS7-VISUEL.md: "A" = an image or theme.xml of the default theme
// net_rim_theme_BlackBerry7_640x480_b, "C" = a native capture of the 9900 simulator (sim-NN,
// RGB565, so #313031 for #303030: the theme's image wins when there is one), "gsm" = a real
// 9900 in OS 7.0. Legacy colors still read by code follow, marked.

/** [rgb] (any alpha ignored) with an alpha of [opacity], rounded half up like the firmware images. */
fun withOpacity(rgb: Int, opacity: Float): Int = ((opacity * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

private fun opaque(rgb: Int) = withOpacity(rgb, 1f)

/** The blue of focus: one family, a vertical gradient from top to bottom everywhere. */
object Focus {
    /** An icon's cell (home grid, search, folder): icon_underlay~focus.png, 90x95, stretched to the cell (A; sim-08, C). */
    val IconTop = opaque(0x088EEF)
    val IconBottom = opaque(0x0753B4)

    /** A row of a menu or of a list, full width, white text: sim-04, sim-44, sim-57 (C), a light sheen on the upper half. */
    val RowTop = opaque(0x088FF0)
    val RowBottom = opaque(0x1666DC)

    /** A text selected in a field on a dark box: Edit Folder's name (sim-481, C). */
    val TextSelection = opaque(0x2B8FFF)

    /** A dialog's button: border_button~focus.png (A; sim-19, C). */
    val Button = opaque(0x1766DB)
    val ButtonBorder = opaque(0x004099)
    val ButtonSheen = opaque(0x3B7FE1)

    /** The short menu's focused action: a rounded square behind its icon and label, top to bottom (sim-17, sim-52, C). */
    val ShortMenu = opaque(0x0C84EA)
    val ShortMenuBottom = opaque(0x1766DB)

    /** A profile in "Select a Profile" (sim-28, C). */
    val Profile = opaque(0x0F7AE6)
}

/** The home screen: banner, indicator row, tabs (BBOS7-VISUEL §1, §2). Their backgrounds are theme images (home_*.png). */
object HomeColors {
    /** The banner's time: fill=F7F7F7 in homescreen-banner.pme (A). */
    val BannerTime = opaque(0xF7F7F7)

    /** The banner's date and network, the tab's view name, the folder's title: white measured (sim-03, sim-16, C). */
    val Text = opaque(0xFFFFFF)

    /** The calendar icon's day (sim-06), and a hidden icon's opacity under Show All: by eye (Eyeballed). */
    val CalendarDate = opaque(Eyeballed.CALENDAR_DATE_GREY)
    const val HIDDEN_ICON_ALPHA = Eyeballed.HIDDEN_ICON_ALPHA
}

/** The list of notifications under the home's banner (BBOS7-VISUEL §5): measured on sim-26, sim-27 and their recaptures (C). */
object NoticeColors {
    /** Over the wallpaper, the tray gone: 0.17 of it left (four places of sim-26 against the closed home). */
    val Veil = withOpacity(0x000000, 0.83f)
    /** A header's title, and every text on the focus. */
    val Title = opaque(0xFFFFFF)
    /** A row's time, sender and summary, unfocused. */
    val Text = opaque(0xDFDFDF)
    /** 1 px at the foot of a header: white at 15 % over the veil. */
    val Divider = withOpacity(0xFFFFFF, 0.15f)
}

/** The full menu (Menu key) and the short one (trackpad held): BBOS7-VISUEL §4. */
object MenuColors {
    /** border_menu.png, 40x40 in nine slices (A); sim-04 (C). */
    val Background = opaque(0x212429)
    val Border = opaque(0x1E2125)
    /** 1 px between groups (sim-44, C). */
    val Divider = opaque(0x43454A)
    val Text = opaque(0xE1E3E5)
    /** The focused row's text, on [Focus.RowTop]. */
    val TextFocused = opaque(0xFFFFFF)

    /**
     * The short menu's box, opaque (the same values over the wallpaper of sim-17 and the grey of
     * sim-52, C): a frame of #434A58 then #616B7F, filled top to middle to bottom.
     */
    val ShortBackground = opaque(0x527590)
    val ShortTop = opaque(0x466782)
    val ShortBottom = opaque(0x4A6C86)
    val ShortFrame = opaque(0x434A58)
    val ShortFrameInner = opaque(0x616B7F)
    /** Between its rows, a dark then a light line, lighter towards the middle (sim-52, C). */
    val ShortLineDarkEdge = opaque(0x4D6B84)
    val ShortLineDark = opaque(0x2A3B49)
    val ShortLineLightEdge = opaque(0x567791)
    val ShortLineLight = opaque(0x7D96AA)
    val ShortText = opaque(0xFFFFFF)

    /** The full menu's scroll arrows, top to bottom (sim-44, sim-45, C). */
    val ArrowTop = opaque(0x189CF7)
    val ArrowBottom = opaque(0x0B7CE6)
}

/** Dialogs, buttons stacked and centred: BBOS7-VISUEL §12. */
object DialogColors {
    /** The box (sim-19, C; border_popup.png, A), whose 1 px edge is #1E2126. */
    val Background = opaque(0x212429)
    val Text = opaque(0xFFFFFF)
    /** A button without focus: border_buttongrey.png (A; sim-19, C). */
    val Button = opaque(0xAFB1B4)
    val ButtonSheen = opaque(0xEAECEE)
    val ButtonBorder = opaque(0xD7DADE)
    /** Its text, the darkest of "Close" on sim-19 (C). */
    val ButtonText = opaque(0x202121)
    /** On a focused button ([Focus.Button]). */
    val ButtonTextFocused = opaque(0xFFFFFF)
    /** The rules of a box with a title, Add Folder's (sim-472, C). */
    val Rule = opaque(0x878787)
}

/** Manage Connections under the home's banner (BBOS7-VISUEL §6); its boxes are theme images, its other colors [ListColors]'. */
object ConnectionColors {
    /** A box's status, "Simulated Network", "On" (sim-23, C). */
    val Status = opaque(0x008000)
    /** Options and Status' subtitles (sim-24, C): #808080, not Options' #7C7E80 (sim-57). */
    val Subtitle = opaque(0x808080)
}

/** Lists of the apps (Options, Text Messages, Messages): BBOS7-VISUEL §15 to §17. */
object ListColors {
    val Background = opaque(0xFFFFFF)
    /** A row's title (sim-57, C). */
    val Title = opaque(0x000000)
    /** Its subtitle: the cores of "Bluetooth connections, Wi-Fi, mode" on sim-57 (C). */
    val Subtitle = opaque(0x7C7E80)
    /** 1 px under each row: sim-57, Options (C); sim-53, Text Messages, measures #D8D8D8. */
    val Divider = opaque(0xD9DCDE)
    /** A date header, top to bottom (gsm 027, C, 7.0); theme.xml's light_gradient (#E0DDDA to #FBFBFF, A) is not what shows. */
    val HeaderTop = opaque(0xD3D5D4)
    val HeaderBottom = opaque(0xAEB2B1)
    /** The black band of a screen's title (Options, 35 px), its last line and its white text (sim-57, C). */
    val TitleBar = opaque(0x000000)
    val TitleBarLine = opaque(0x1F1F1F)
    val TitleBarText = opaque(0xFFFFFF)
    /** A date header's text (gsm 027, C); white on the focus. */
    val HeaderText = opaque(0x000000)
    /** The search field's hint, "Search" (sim-57, C). */
    val SearchHint = opaque(0xADB0B3)
    /** A check box row's label (sim-60, C). */
    val CheckText = opaque(0x000000)
    /** An options screen's group title, bold ("Trackpad" on Trackpad Sensitivity, C). */
    val GroupTitle = opaque(0x7C7E80)
    /** Around an options screen's group (Trackpad Sensitivity, C). */
    val GroupBackground = opaque(0xDDDDDD)
    /** A choice field's open list (C): border_inplace.png's #212429 (A), values #CCCCCC, the chosen one white. */
    val ChoiceListText = opaque(0xCCCCCC)
}

/** A text message's thread (BBOS7-VISUEL §16): sim-50 (C, 7.1) for the sent bubbles, gsm 023 (C, 7.0, JPEG) for the received. */
object ThreadColors {
    /** Behind the bubbles (sim-50). */
    val Background = opaque(0xB2B2B2)
    /** The contact's band: black, white name (sim-50). */
    val Band = opaque(0x000000)
    val BandText = opaque(0xFFFFFF)
    /** A sent bubble, from its top down to the 32nd row, flat below (sim-50). */
    val SentTop = opaque(0xF3F6FA)
    val SentBottom = opaque(0xE5EBF3)
    val SentBorder = opaque(0x888C91)
    /** A received bubble, the same way (gsm 023: JPEG values, its edge #7A8D94). */
    val ReceivedTop = opaque(0xE4F5FC)
    val ReceivedBottom = opaque(0xCAE6F2)
    val ReceivedBorder = opaque(0x7A8D94)
    /** One pixel around a bubble (sim-50: rows 68 and 146, columns 3 and 616). */
    val Shadow = opaque(0xA5A5A5)
    /** Under "Me" and the time (sim-50, row 106). */
    val Separator = opaque(0xC7CDD4)
    /** "Me" and the time (sim-50), the text (sim-50: black). */
    val Sender = opaque(0x2F3033)
    val Text = opaque(0x000000)
    /** The field's band at the bottom, top, middle and bottom (sim-50, column 575: rows 414, 444, 477). */
    val ComposerTop = opaque(0x313842)
    val ComposerMiddle = opaque(0x212429)
    val ComposerBottom = opaque(0x181818)
}

/**
 * What darkens the screen under the app switcher: black at 90 %, measured on sim-05 against the
 * same home on sim-02 (a channel of 248 becomes 24, 75 becomes 7: a factor of 0.09 to 0.10).
 */
object Veil {
    val Switcher = withOpacity(0x000000, 0.9f)
}

/**
 * The accent and error colors the system palette is built from (SystemUI's SystemPalette): the
 * theme's blue (theme.xml `blue_gradient` #1766DB to #088EEF, `blue_gradient_light` #74A4E8 to
 * #6BBBF5, `blue_gradient_inverted` #0B88ED to #04B9F7, A), the focus images' (`icon_underlay~focus`
 * #088EEF to #0753B4, `border_button~focus` #1766DB with its #004099 border, A) and the red of
 * `new_overlay.png` (the star on an icon: #BA2121 and #C25C5C, its rim #9F0000, A). One accent,
 * as OS 7 has one. What the theme has no value for is D: the pastels (the theme's light blue and
 * the red, mixed with white to read under the dark shade) and the tertiary family, which the 9900
 * does not have (the same blue shifted one step darker).
 */
object Palette {
    val PrimaryHighlight = opaque(0x088EEF)
    val PrimaryBase = opaque(0x1766DB)
    val PrimaryShade = opaque(0x0753B4)
    val PrimaryPastel = opaque(0xBAD1F3) // D: blue_gradient_light's #74A4E8, half white
    val TextOnPrimary = opaque(0xFFFFFF)
    val RedHighlight = opaque(0xC25C5C)
    val RedBase = opaque(0xBA2121)
    val RedShade = opaque(0x9F0000)
    val RedPastel = opaque(0xEFC8C8) // D
    val BlueHighlight = opaque(0x1766DB)
    val BlueBase = opaque(0x0753B4)
    val BlueShade = opaque(0x004099)
    val BluePastel = opaque(0xD3E1F7) // D
    val FocusOutline = opaque(0x04B9F7) // blue_gradient_inverted's light end, list:active (A)
    val AccentLight = opaque(0x6BBBF5) // blue_gradient_light's other end: the palette's shade 400
}

/**
 * The two schemes of the system palette and of the apps' light and dark surfaces, in OS 7's
 * values (BBOS7-VISUEL §19): dark is the menus and dialogs (#212429, border #1E2125, text #E1E3E5,
 * rule #43454A, A and C), light is the lists (white, title #000000, subtitle #7C7E80, rule #D9DCDE,
 * C). What no capture gives (the greys between, a pressed state, the secondary text on dark) is D.
 */
data class Theme(
    val background: Int,
    val surface: Int,
    val surfaceBase: Int,
    val overlay: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val divider: Int,
    val pressed: Int,
    val selected: Int,
) {
    companion object {
        val Dark = Theme(
            background = opaque(0x000000),
            surface = opaque(0x212429), // MenuColors.Background
            surfaceBase = opaque(0x1E2125), // MenuColors.Border
            overlay = opaque(0x212429),
            textPrimary = opaque(0xE1E3E5), // MenuColors.Text
            textSecondary = opaque(0xAFB1B4), // DialogColors.Button's grey (D as a text)
            textTertiary = opaque(0x8E9094), // D
            divider = opaque(0x43454A), // MenuColors.Divider
            pressed = withOpacity(0xFFFFFF, 0.15f), // D
            selected = withOpacity(0x1766DB, 0.7f), // D
        )

        val Bright = Theme(
            background = opaque(0xFFFFFF), // ListColors.Background
            surface = opaque(0xF4F5F6), // D
            surfaceBase = opaque(0xE8EAEB), // D
            overlay = opaque(0xF4F5F6), // D
            textPrimary = opaque(0x000000), // ListColors.Title
            textSecondary = opaque(0x7C7E80), // ListColors.Subtitle
            textTertiary = opaque(0xADB0B3), // ListColors.SearchHint
            divider = opaque(0xD9DCDE), // ListColors.Divider
            pressed = withOpacity(0x000000, 0.1f), // D
            selected = withOpacity(0xBAD1F3, 0.5f), // D
        )
    }
}

// Legacy value, still read by SystemUI's hook: a black status bar.
object ShellColors {
    val StatusBar = opaque(0x000000)
}
