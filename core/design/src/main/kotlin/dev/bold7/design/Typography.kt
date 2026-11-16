package dev.bold7.design

import kotlin.math.roundToInt

/** Points (1/72 inch) to pixels on a screen of [dpi]. */
fun pointsToPx(points: Float, dpi: Int): Int = (points * dpi / 72f).roundToInt()

/** A text style: font family, weight (100 to 900) and size in px. */
data class TextToken(val family: String, val weight: Int, val sizePx: Int)

/**
 * BBAlpha Sans, the font of BlackBerry OS 7.1 (firmware of the 9900, net_rim_font_latin_truetype),
 * is the system font: the module installs it as `sans-serif`, BBAlpha Sans Condensed as
 * `sans-serif-condensed`, BBAlpha Serif as `serif` and BBAlpha Sans Mono as `monospace`
 * (tools/fonts_xml.py), each in 400, 400 italic and 700, the only weights the firmware has.
 * Sizes in px of the 9900, the Q25's own (docs/BBOS7-VISUEL.md §19): the TTFs rendered at that
 * size match the captures' text widths within 2 px (C).
 */
object Typography {
    const val FAMILY = "sans-serif"
    const val FAMILY_CONDENSED = "sans-serif-condensed"

    const val REGULAR = 400
    const val BOLD = 700

    /** Text, menus, dialogs, list titles: "Maximise Panel" 206 px measured, 207 rendered (sim-04, C). */
    const val TEXT = 27

    /** List subtitles (sim-57, C). */
    const val SUBTITLE = 23

    /** The banner's time: BannerTime 52, its AM/PM 20 (homescreen-banner.pme, A); BannerTime is a .cbtf (tools/cbtf.py). */
    const val BANNER_TIME = 52
    const val BANNER_AM_PM = 20

    /**
     * An app's status bar time and its counters' numbers: BBAlpha Sans Condensed 27 px, "00:30"
     * 66 px wide on sim-43 as the TTF renders it at 27 (C). banner.pme says font-size=32 (A), not
     * in pixels: rendered at 32, the same text is 79 px wide.
     */
    const val APP_BAR_TIME = 27

    /** The lock screen's owner name and information: BBAlpha Sans Condensed bold 35 and 29 (lockscreen.pme, A). */
    const val OWNER_NAME = 35
    const val OWNER_INFO = 29

    val Text = TextToken(FAMILY, REGULAR, TEXT)
    val Subtitle = TextToken(FAMILY, REGULAR, SUBTITLE)

    /** The banner's date and network, the tab's view name (sim-03, sim-02, C). */
    val Banner = TextToken(FAMILY_CONDENSED, REGULAR, TEXT)
    val AppBarTime = TextToken(FAMILY_CONDENSED, REGULAR, APP_BAR_TIME)

    /**
     * The banner's time until BannerTime is decoded (tools/cbtf.py): BBAlpha Sans Condensed at
     * the size that gives BannerTime's 42 px digits, by eye; BannerTime's own advances (29 px a
     * digit, 9 the colon) are the ones to keep.
     */
    val BannerTime = TextToken(FAMILY_CONDENSED, REGULAR, Eyeballed.BANNER_TIME_SIZE)
}
