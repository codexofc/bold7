package dev.bold7.design

/**
 * Every value that neither the firmware nor a capture gives, in one place, to be tuned by eye on
 * the Q25. Everything else in this module is sourced; a value that moves here from a source says so.
 */
internal object Eyeballed {
    // The banner's time drawn in BBAlpha Sans Condensed until BannerTime.cbtf is decoded: at 54 px
    // its "1" is 39 px high and its "0" 41 (FreeType), BannerTime's 40 and 42 (tools/cbtf.py).
    const val BANNER_TIME_SIZE = 54

    // How long an app's status message (sim-42) stays: never timed on the simulator.
    const val STATUS_MS = 2000L

    // "Call …?" chooses nothing until it has been shown this long: the second click of a quick
    // double click on a missed call never calls (D, a safety the 9900 did not have).
    const val CALL_PROMPT_ARM_MS = 500L

    // The corners of the short menu's box and of its focus (sim-17, sim-52): about 6 and 5 px.
    const val SHORT_MENU_RADIUS = 6f
    const val SHORT_FOCUS_RADIUS = 5f

    // The home, BBOS7-VISUEL §1 to §3. A hidden icon Show All shows: half its opacity.
    const val HIDDEN_ICON_ALPHA = 0.5f
    // The calendar icon's day (sim-06): digits 28 px high in BBAlpha Sans at 39 px, their grey,
    // their baseline under the icon's top.
    const val CALENDAR_DATE_SIZE = 39
    const val CALENDAR_DATE_BASELINE = 65
    const val CALENDAR_DATE_GREY = 0x3C3C3C
    // The blue square of the speaker and the magnifier, its side: measured since (sim-79, sim-81, C).
    const val BANNER_FOCUS_SQUARE = 45
    // Between the time and its AM/PM: no 12-hour capture.
    const val AM_PM_GAP = 4

    // Legacy value, still read by the trackpad (core/ui); not sourced from a device or firmware capture.
    const val TRACKPAD_STEP = 30f
}
