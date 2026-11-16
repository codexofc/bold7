package dev.bold7.calendar

// The Calendar's measures and colours, read on the simulator's native captures (C, 640x480 pixels,
// the Q25's own; colours quantised RGB565, docs/BBOS7-VISUEL.md §29), what the square adds noted D.
object Look {
    // The band under the app bar of the Day and Month views (sim-220, sim-225): 52 px, a grey line
    // then a dark gradient, the title white 27 centred, arrow_more(_rl).png at x 27 and 611.
    const val BAND = 52
    const val BAND_LINE = 0xFF656A6F.toInt()
    const val BAND_TOP = 0xFF484E53.toInt()
    const val BAND_BOTTOM = 0xFF2C2F32.toInt()
    const val BAND_BASELINE = 36
    const val ARROW_X = 27

    // The Week view's band (sim-224): 115 px, the month and year bold in the hours' column, each day
    // its number (27) over its name (23), the focused day blue.
    const val WEEK_BAND = 115
    const val WEEK_NUMBER_BASELINE = 46
    const val WEEK_NAME_BASELINE = 88

    // The hours (sim-220, sim-224): the column #7C7E80, the hour light grey at its top left, 36 px
    // wide in the Day view, 66 in the Week view; rows of 78 and 55 px.
    const val HOURS = 0xFF7C7E80.toInt()
    const val HOUR_TEXT = 0xFFBDBFC2.toInt()
    const val DAY_HOURS_WIDTH = 36
    const val WEEK_HOURS_WIDTH = 66
    const val DAY_ROW = 78
    const val WEEK_ROW = 55
    const val HOUR_X = 4

    // Quick Entry's box (sim-227, C): its frame, the width of its blue times cell, their x and
    // baselines, the subject's x, the block cursor.
    const val QUICK_FRAME = 0xFF45494D.toInt()
    const val QUICK_FIELD_TOP = 0xFFFFFFFF.toInt()
    const val QUICK_FIELD_BOTTOM = 0xFFECF0F3.toInt()
    const val QUICK_TIMES = 82f
    const val QUICK_TIME_X = 5
    const val QUICK_BASELINE = 27f
    const val QUICK_BASELINE_2 = 66f
    const val QUICK_TEXT_X = 85
    const val QUICK_CURSOR_W = 9
    const val QUICK_CURSOR_H = 32
    const val HOUR_BASELINE = 26
    const val DAY_LINE = 0xFFE0E0E0.toInt()
    const val WEEK_LINE = 0xFFEAEAEA.toInt()
    const val WEEKEND = 0xFFF7F7F7.toInt()
    const val WEEKEND_LINE = 0xFFE4E4E4.toInt()
    const val ALL_DAY_ROW = 0xFFCCCECF.toInt()

    /** The all-day row's hours column, flat under the green clock (sim-224, pixel read). */
    const val ALL_DAY_HOURS = 0xFFBBBDBF.toInt()

    /** The room kept each side of the month and year of the Week band's corner (sim-224: x 4 to 61 of 66). */
    const val CORNER_MARGIN = 4

    // The focused hour (sim-220): the hour's cell in the focus blue, the row light blue.
    const val HOUR_FOCUS_TOP = 0xFF088EEF.toInt()
    const val HOUR_FOCUS_BOTTOM = 0xFF1666DC.toInt()
    const val ROW_FOCUS_TOP = 0xFF6BBCF6.toInt()
    const val ROW_FOCUS_BOTTOM = 0xFF6FAEEE.toInt()

    /** The hour of the moment, not focused: a blue frame around its row (sim-239; the blue D). */
    const val NOW_FRAME = 0xFF1666DC.toInt()

    // An appointment's block (sim-237, sim-239): placed to the minute, 2 px in from the hours, a
    // 1 px border, rounded; focused, the blue gradient and white text; otherwise light with a dark
    // border, the subject black and the place grey in brackets; a light blue bar at its left.
    const val BLOCK_INSET = 2
    const val BLOCK_RADIUS = 5f
    const val BLOCK_BORDER = 0xFF033E67.toInt()
    const val BLOCK_TOP = 0xFF0A8FE9.toInt()
    const val BLOCK_BOTTOM = 0xFF1C63C2.toInt()
    const val BLOCK_IDLE_BORDER = 0xFF6D6E6F.toInt()
    const val BLOCK_IDLE_TOP = 0xFFF8F9F9.toInt()
    const val BLOCK_IDLE_BOTTOM = 0xFFE3E6E9.toInt()
    const val BLOCK_BAR = 0xFF6DBAED.toInt()
    const val BLOCK_BAR_EDGE = 0xFF5CACE5.toInt()
    const val BLOCK_BAR_X = 4
    const val BLOCK_BAR_WIDTH = 6
    const val BLOCK_TEXT_X = 14
    const val BLOCK_BASELINE = 26
    const val PLACE = 0xFF555555.toInt()

    // The panel of the focused appointment (sim-237, sim-238): 90 px of #17181A at the bottom, or
    // under the band when the focus is in the bottom half; its bar, the subject 27, time and place 20.
    const val PANEL = 90
    const val PANEL_COLOR = 0xFF17181A.toInt()
    const val PANEL_TEXT_X = 17
    const val PANEL_TITLE_BASELINE = 30
    const val PANEL_TIME_BASELINE = 56
    const val PANEL_PLACE_BASELINE = 80
    const val PANEL_SMALL = 20

    // The Month view (sim-225, sim-243): the days' names on #6F7173, 33 px; cells 60 px high on the
    // 9900, their lines #D9DCDE, the number black 27 at the top right, the other months' days grey on
    // #C8CACC, the weekend #F7F7F7; a day with appointments a bar 11x4 at its bottom left (white with
    // a #044C80 frame on the focus; black elsewhere, D).
    const val NAMES = 33
    const val NAMES_COLOR = 0xFF6F7173.toInt()
    const val MONTH_LINE = 0xFFD9DCDE.toInt()
    const val OTHER_MONTH = 0xFFC8CACC.toInt()
    const val OTHER_MONTH_TEXT = 0xFF8F9193.toInt()
    const val DAY_FOCUS_TOP = 0xFF0A89ED.toInt()
    const val DAY_FOCUS_BOTTOM = 0xFF1968DF.toInt()
    const val NUMBER_RIGHT = 9
    const val NUMBER_BASELINE = 27
    const val MARK_X = 5
    const val MARK_BOTTOM = 10
    const val MARK_WIDTH = 11
    const val MARK_HEIGHT = 4
    const val MARK_FRAME = 0xFF044C80.toInt()

    // The Agenda (sim-226, sim-244): headers as the lists' (ListHeaderView), free time 57 px on
    // #F5F5F5 ("00:00 - 18:30" at x 26, "- Free Time -" right-aligned at x 617), an appointment 95 px
    // on white (bar x 12, subject 27, time 27, place 23 grey, bell at the right).
    const val FREE_ROW = 57
    const val FREE = 0xFFF5F5F5.toInt()
    const val EVENT_ROW = 95
    const val AGENDA_TEXT_X = 26
    const val AGENDA_RIGHT = 23
    const val AGENDA_BAR_X = 12
    const val FREE_BASELINE = 37
    const val EVENT_TITLE_BASELINE = 30
    const val EVENT_TIME_BASELINE = 61
    const val EVENT_PLACE_BASELINE = 87

    // The form (sim-228 to sim-230): #DDDDDD around white boxes with a #C6C6C6 border, 5 px apart,
    // a group's title grey bold 23 over a #D4D4D4 line; the Save button at the top right.
    const val FORM = 0xFFDDDDDD.toInt()
    const val BOX_BORDER = 0xFFC6C6C6.toInt()
    const val BOX_MARGIN = 5
    const val GROUP_LINE = 0xFFD4D4D4.toInt()
    const val GROUP_TITLE = 0xFF7C7E80.toInt()
    const val FORM_TOP = 59
    const val SAVE_WIDTH = 86
    const val SAVE_HEIGHT = 50
}
