package dev.bold7.design

import kotlin.math.roundToInt
import kotlin.math.sign

// Sizes in pixels. A pixel of the 9900 (640x480, 287 ppi) and one of the Q25 (720x720, 295 ppi)
// have the same size within 2.7 %: every measure of BlackBerry OS 7.1 is taken as it is, never
// scaled, and only the layouts are recomputed for the square ("D"). Sources as in Colors.kt,
// details in docs/BBOS7-VISUEL.md.

/** The home screen, top to bottom: banner, indicator row, tab of the views, the tray's grid (§1, §2). */
object HomeDimens {
    /** banner_background.png, 640x93 (A); its bottom line is banner_divider.png, the row's top. */
    const val BANNER = 93

    /** indicator_bar_background.png, 640x47 (A). */
    const val INDICATOR_ROW = 47

    /** The tab of the views, its light lines (tabmanager_header_line_*.png, 2 px) included: y 337 to 382 on sim-02 (C). */
    const val TAB = 46

    /**
     * An app icon of the theme, drawn at the top of its cell, centred in its width (A, sim-06). In a
     * 120x97 cell: 14 px each side (7.3 on the 9900) and 5 px under it (5 on the 9900).
     */
    const val ICON = 92

    /** Row pitch of the grid (sim-06, C): the focus covers it whole. */
    const val ROW = 97

    /**
     * 6 columns of 120 px (D). The 9900 has 6 of 106.7 (sim-06, gsm 003); 7 of 102.9 would be
     * nearer that width, but 6 keeps the 9900's arrangement (an app's row and column, how many
     * trackpad moves reach it, Move), the 6 columns of the universal search
     * (universal-search-default-columns-count = 6, theme.xml, A), and gives the 80 px more to air
     * around the icons, never to their size.
     */
    const val COLUMNS = 6
    const val COLUMN = 720 / COLUMNS

    /**
     * 5 rows expanded (D): 720 - 93 - 47 - 46 leaves 534 px, 5 rows of 97 (485), and the 49 px
     * left stay under the grid: the first row starts right under the tab, its icons 11 px down,
     * as on sim-06 (D). The 9900 shows 3 (sim-06); collapsed, 1 row at the
     * bottom with the tab above it, as on the 9900 (sim-02).
     */
    const val ROWS_EXPANDED = 5
    const val GRID_TOP_MARGIN = 0

    /** The banner's left anchors (sim-03, C): battery 4 px from the edge, date 7 px; coverage and Wi-Fi 6 px from the right. */
    const val BANNER_LEFT = 4
    const val BANNER_DATE_LEFT = 7
    const val BANNER_RIGHT = 6

    /** By eye (Eyeballed): the calendar's day, the banner's focus squares, the AM/PM gap, the open folder's icon. */
    const val CALENDAR_DATE_SIZE = Eyeballed.CALENDAR_DATE_SIZE
    const val CALENDAR_DATE_BASELINE = Eyeballed.CALENDAR_DATE_BASELINE
    const val BANNER_FOCUS_SQUARE = Eyeballed.BANNER_FOCUS_SQUARE
    const val AM_PM_GAP = Eyeballed.AM_PM_GAP
    const val FOLDER_OPEN_FRAME = Eyeballed.FOLDER_OPEN_FRAME
}

/**
 * The list of notifications (§5), px of the 9900 (sim-26, C) but the summary's end: from y 141
 * under the indicator row, every line 46 px, the header's divider on its last. On the Q25 the
 * summary runs 80 px further right (D, BBOS7-VISUEL §5).
 */
object NoticeDimens {
    const val TOP = HomeDimens.BANNER + HomeDimens.INDICATOR_ROW
    const val GAP = 1 // y 140, between the row and the first header
    const val LINE = 46
    const val ICON_X = 3 // the strip's frame: its ink from x 8, y 150
    const val ICON_Y = 7
    const val TITLE_X = 43
    const val TITLE_BASELINE = 33
    const val TIME_X = 2
    const val SENDER_X = 92
    const val SUMMARY_X = 266
    const val ROW_BASELINE = 32
    /** "Dee Dee…" ends at 236 (gsm 021): the sender is cut before the summary (D). */
    const val SENDER_WIDTH = SUMMARY_X - SENDER_X - 12
    /** The summary's "…" ends at x 613 on the 9900; 80 px more on the Q25 (D). */
    const val SUMMARY_RIGHT = 615 + 80
}

/**
 * Places in the home banner and its indicator row, in px from their top left (sim-03, C), what is
 * anchored right kept from the right edge (D, as `width - X_RIGHT`). The launcher's banner and
 * SystemUI's lock screen (sim-64: the same banner, A, lockscreen.pme) both read them.
 */
object HomeBanner {
    const val BATTERY_X = 3 // its light part x 4 to 51, y 6 to 16; the frame's ink at (1, 4)
    const val BATTERY_Y = 2
    const val ALARM_X = 63 // net_rim_Alarm_Indicator (21x21) right of the battery, an alarm set or snoozed (sim-337, sim-338)
    const val ALARM_Y = 4
    const val DATE_X = 7 // "Fri 13 Nov" y 36 to 56
    const val DATE_BASELINE = 56
    const val NETWORK_BASELINE = 24 // "Simulated Network" y 4 to 24, centred at 320.5
    const val TIME_BASELINE = 77 // "00:36" y 36 to 78, round digits 1 px under the line
    const val COVERAGE_RIGHT = 640 - 578 // "edge" ends at x 578
    const val SIGNAL_RIGHT = 640 - 587 // the strip's frame from x 587, y 5
    const val SIGNAL_Y = 5
    const val WIFI_RIGHT = 640 - 594 // by eye, x 595 to 625, y 33 to 55 (BBOS7-VISUEL §1)
    const val WIFI_Y = 33
    const val PROFILE_X = 1 // the speaker x 4 to 42; its frame's ink from x 3
    const val ROW_ICON_Y = 2 // 43 px icons in the 47 px row (D, centred)
    const val SEARCH_RIGHT = 640 - 592 // the magnifier centred at x 613
    const val COUNT_BASELINE = 33 // "2" y 108 to 126 (row from 93)
    const val COUNT_GAP = 6 // "2" ends at 306, the icon's frame starts at 312
    const val UNREAD_Y = 9 // the icon's ink from y 104, 2 px into its frame
    const val UNREAD_WIDTH = 33 // net_rim_Ribbon_UnreadCount-icons33x29 (A)
    const val INDICATOR_GAP = 10 // GridIndicators hpadding=10 (homescreen-banner.pme, A)
}

/** Menus (§4). */
object MenuDimens {
    /**
     * A row of the full menu, all of it the focus band when focused; between two groups a
     * separator of 3 px, its #43454A line in the middle (sim-04: lines at y 239, 299, 359, none
     * between Manage Panels and Options; sim-44: Search by and Display Style 57 px apart, C).
     */
    const val ROW = 57
    const val FOCUS = ROW
    const val SEPARATOR = 3

    /** From the panel's top border pixel to its first row, and under its last row (sim-04, sim-45, C). */
    const val PAD_TOP = 17
    const val PAD_BOTTOM = 5

    /**
     * Labels' origin and baseline in a row; the panel's right border pixel is 7 px after the
     * longest label: "Switch Application" (252 px) gives 266 on sim-04, "View Saved Messages"
     * (299) gives 313 on sim-44 (C). Its left edge and its bottom stay off the screen.
     */
    const val TEXT_X = 7
    const val PAD_RIGHT = 7
    const val TEXT_BASELINE = 37

    /** Rows scroll under the top border from 3 px below it (sim-45: Search cut at y 73, C). */
    const val VIEWPORT_TOP = 3

    /** The home's menu, as wide as its longest label (sim-04, C); Messages' is 314. */
    const val HOME_WIDTH = 266

    /** Rows the full menu shows at once on the square, against 6 on the 9900 (D). */
    const val VISIBLE_ROWS = 10

    /** The scroll arrows: 10x5 blue triangles, 8 px left of the border, 16 px under the viewport's top or 8 above the bottom (sim-44, sim-45, C). */
    const val ARROW_WIDTH = 10
    const val ARROW_HEIGHT = 5
    const val ARROW_RIGHT = 8
    const val ARROW_TOP = 16
    const val ARROW_BOTTOM = 8

    /**
     * A row that opens a submenu: arrow_menu.png (15x12, A, a #E1E3E5 triangle from its column 2)
     * against the panel's right border, 23 px under the row's top (Attach of the mail's composer,
     * sim-292: the triangle from x 424, y 392, on a row from 369, the border at 437, C). It does not
     * widen the menu (§4).
     */
    const val SUBMENU_ARROW_WIDTH = 15
    const val SUBMENU_ARROW_TOP = 23

    /**
     * A submenu opens at the right of its menu, its own dark panel (border_inplace.png, rounded on
     * every side) [SUB_GAP] px after the menu's border, on the bottom 5 px above the screen's,
     * [SUB_PAD] px over its first row and under its last, labels at x 8 (Week | Month | Agenda,
     * sim-223: x 295 to 415, y 268 to 475; To Metric, sim-251: x 270 after a border at 266, C).
     * The pad under the rows and the right pad are read to the pixel off the captures' scale (D).
     */
    const val SUB_GAP = 4
    const val SUB_PAD = 16
    const val SUB_BOTTOM = 5
    const val SUB_TEXT_X = 8
    const val SUB_PAD_RIGHT = 12

    /** The least a submenu keeps from the screen's sides when it must move left of its menu (D). */
    const val SUB_EDGE = 4

    /**
     * The short menu: a box of 337 px (x 151 to 487), a 2 px frame, 3 columns of 111 px and rows
     * of 119 (sim-17: 2 rows, y 117 to 358; sim-52: 3 rows, y 57 to 417, C), centred on the screen.
     */
    const val SHORT_COLUMNS = 3
    const val SHORT_COLUMN = 111
    const val SHORT_ROW = 119
    const val SHORT_FRAME = 2
    const val SHORT_WIDTH = 2 * SHORT_FRAME + SHORT_COLUMNS * SHORT_COLUMN

    /** The focus in a cell: 107x105, 2 px in from its sides and 6 from its top (sim-52, Send; sim-17, Launch, C). */
    const val SHORT_FOCUS_X = 2
    const val SHORT_FOCUS_Y = 6
    const val SHORT_FOCUS_WIDTH = 107
    const val SHORT_FOCUS_HEIGHT = 105

    /** The action's image, sm_*.png 82x55 (A), centred, 8 px under the cell's top (C). */
    const val SHORT_ICON_WIDTH = 82
    const val SHORT_ICON_HEIGHT = 55
    const val SHORT_ICON_Y = 8

    /**
     * The label under it, BBAlpha Sans Condensed 17 ("Full Menu" 71 px, "Send" 37, C), centred: one
     * line on a baseline 95 px under the cell's top, two lines 21 px apart around the same middle;
     * a label wider than the focus wraps ("Add Smiley" 86 px stays, "Check Spelling" 113 wraps, C).
     */
    const val SHORT_LABEL_SIZE = 17
    const val SHORT_LABEL_BASELINE = 95
    const val SHORT_LABEL_LINE = 21
    const val SHORT_LABEL_MAX = SHORT_FOCUS_WIDTH

    /** At most 3 rows: 8 actions and Full Menu, always the last cell (sim-17, sim-52, C; D for the cap). */
    const val SHORT_MAX_ACTIONS = 8

    /** Corners of the box and of the focus, by eye. */
    const val SHORT_RADIUS = Eyeballed.SHORT_MENU_RADIUS
    const val SHORT_FOCUS_RADIUS = Eyeballed.SHORT_FOCUS_RADIUS
}

/** Lists and bars of the apps (§5, §15 to §18). */
object ListDimens {
    /** Two-line row with icon: Options, Text Messages (sim-57, sim-53, C). */
    const val ROW = 71

    /** One-line row of the notifications list (sim-26, C). */
    const val NOTIFICATION_ROW = 46

    /** A date header of Messages (sim-43, gsm 027, C). */
    const val HEADER = 56

    /** Title band of a screen (Options, sim-57, C). */
    const val TITLE_BAR = 35

    /** An app's status bar: two lines (Messages, sim-48) or one (a call, sim-18), C; banner.pme (A). */
    const val APP_BAR = 67
    const val APP_BAR_ONE_LINE = 35

    /** The line under a row. */
    const val DIVIDER = 1

    /**
     * A row's texts (sim-57, Options, C): title 27 px on a baseline 31 under the row's top, subtitle
     * 23 px on 60; at x 79 after a category icon centred on x 37 (cat_*.png, 44 to 48 px, A), at 9
     * without one (sim-24, C).
     */
    const val TEXT_X = 9
    const val ICON_TEXT_X = 79
    const val ICON_CENTRE_X = 37
    const val TITLE_BASELINE = 31
    const val SUBTITLE_BASELINE = 60

    /** A date header's text, centred on a baseline 37 under its top (sim-53, C). */
    const val HEADER_BASELINE = 37

    /** A screen's title, white 27 px at x 5 on a baseline 25, over 34 px of black and a #1F1F1F line (sim-57, sim-60, C). */
    const val SCREEN_TITLE_X = 5

    /**
     * A row with a check box (sim-60, C): 57 px, its label on a baseline 37, the box (27x27 of
     * net_rim_system_Check-icons27x27.png, A) 12 px from the right edge, centred in the row.
     */
    const val CHECK_ROW = 57
    const val CHECK_BASELINE = 37
    const val CHECK_BOX = 27
    const val CHECK_RIGHT = 12

    /**
     * A row with a choice field, as Trackpad Sensitivity's (sim, C): 57 px, its label as
     * a check box's, the field border_button.png (A, ~focus under the trackpad) 186 px wide, 12 px
     * from the right edge, 5 px under the row's top and 51 high, its value centred left of
     * arrow_choice.png (20x8, A), 13 px from the field's right edge.
     */
    const val CHOICE_FIELD_WIDTH = 186
    const val CHOICE_FIELD_TOP = 5
    const val CHOICE_FIELD_HEIGHT = 51
    const val CHOICE_ARROW_RIGHT = 13
    const val CHOICE_ARROW_WIDTH = 20

    /**
     * Its open list (C): border_inplace.png (A) 78 px wide, 8 px right of the field's left edge, rows
     * of 57 px, the chosen one in border_spinboxbutton~focus.png (A); placed with the chosen value on
     * the field's row and kept on the screen (D: the 640x480 simulator cuts it at the top).
     */
    const val CHOICE_LIST_X = 8
    const val CHOICE_LIST_WIDTH = 78
    const val CHOICE_LIST_ROW = 57

    /**
     * The search field of Options (sim-57, C): a band of 60 px (background_find_field.png, A), the
     * field border_find.png (A) from 2 px of each side and of its top to 2 px of its bottom, its
     * text 27 px at x 25 on a baseline 39 under the band's top.
     */
    const val SEARCH = 60
    const val SEARCH_INSET = 2
    const val SEARCH_TEXT_X = 25
    const val SEARCH_BASELINE = 39
}

/**
 * A text message's thread (§16), measured on sim-50, sim-49 and sim-52 (C, 7.1) and gsm 023, 025
 * (C, 7.0). Bubble measures from its top border; the band at the bottom from its top.
 */
object ThreadDimens {
    /** The contact's band: black, 65 px (sim-50: rows 0 to 64). */
    const val BAND = 65

    /** Its picture: default_avatar.png (115x115, A) drawn 49x49 at 8, 8, as the 9900 draws it (RMSE 0.019 on sim-50). */
    const val AVATAR = 49
    const val AVATAR_X = 8
    const val AVATAR_Y = 8

    /** The name, 27 px white at x 66 on a baseline 30 (sim-50: "513", the digits of a 27 px list row). */
    const val NAME_X = 66
    const val NAME_BASELINE = 30

    /** A new message's To band (sim-49): 64 px black, "To:" at x 2 on a baseline 42, the field border_reply_field.png from x 52 to 4 px of the right edge, y 4 to 59. */
    const val TO_BAND = 64
    const val TO_LABEL_X = 2
    const val TO_BASELINE = 42
    const val TO_FIELD_X = 52
    const val TO_FIELD_RIGHT = 4
    const val TO_FIELD_TOP = 4
    const val TO_FIELD_HEIGHT = 56

    /**
     * Each more To line, a recipient's or the empty one under them (gsm 025, C, 7.0): 60 px lower
     * (its fields' white rows 11 to 56 and 71 to 116). The band ends 2 px lower there than 64 plus
     * 60 (row 125): JPEG of 7.0, D.
     */
    const val TO_LINE = 60

    /**
     * A bubble's place: its top border 4 px under the band or the bubble above's pointer; 4 px
     * from the screen's edge on its pointer's side, 24 px from the other (sim-50: sent 4 to 615 on
     * 640, gsm 023: received 24 to 635); the next one's top border 19 px under its bottom border.
     */
    const val BUBBLE_TOP = 4
    const val BUBBLE_NEAR = 4
    const val BUBBLE_FAR = 24
    const val BUBBLE_GAP = 19

    /** Corners of 5 px (sim-50, by its rows 69 to 74); the gradient of its top 32 rows (rows 70 to 101). */
    const val BUBBLE_RADIUS = 5
    const val BUBBLE_GRADIENT = 32

    /** The pointer under its bottom border: 9 px down, its straight side 6 px in from the bubble's edge, 9 px wide (sim-50: x 10 to 19, rows 145 to 154). */
    const val POINTER = 9
    const val POINTER_IN = 6

    /** Inside: "Me" bold and the time, 21 px (C: "00:33" 58 px wide, renders 57 at 21), 9 px from the sides, baselines 28 and 23; the separator 37 under the top. */
    const val HEAD_SIZE = 21
    const val PAD = 9
    const val SENDER_BASELINE = 28
    const val TIME_BASELINE = 23
    const val SEPARATOR = 37

    /** The state icon, a 32x32 cell of net_rim_Message-icons32x32.png (A, RMSE 0.002 on sim-50) at 7, 39; the text 27 px at 40 on a baseline 64, 12 px over the bottom border. */
    const val ICON = 32
    const val ICON_X = 7
    const val ICON_Y = 39
    const val TEXT_X = 40
    const val TEXT_BASELINE = 64
    const val TEXT_BOTTOM = 12

    /**
     * The field's band at the bottom (sim-50, gsm 025): 66 px; the field border_reply_field.png
     * (31x25, A, in 9 slices) from x 3 to 74 px of the right edge, 5 px under the band's top, 55
     * high; its text 27 px at 13 px into the field on a baseline 39 under its top; the caret 9x32,
     * the focus blue, 12 px under its top; smiley_button.png (40x40, A) 56 px from the right edge,
     * 13 px under the band's top.
     */
    const val COMPOSER = 66
    const val FIELD_X = 3
    const val FIELD_RIGHT = 74
    const val FIELD_TOP = 5
    const val FIELD_HEIGHT = 55
    const val FIELD_TEXT_X = 13
    const val FIELD_BASELINE = 39
    const val CARET_WIDTH = 9
    const val CARET_HEIGHT = 32
    const val CARET_TOP = 12
    const val SMILEY_RIGHT = 56
    const val SMILEY_TOP = 13
}

/** Dialogs (§12). */
object DialogDimens {
    /**
     * The box (border_popup.png, A), as wide as its text: border pixel to border pixel, 40 px more
     * than the text, 124 with an icon (sim-19: 439 for 399; sim-62: 485 for 361; sim-42 and sim-65,
     * C), at most the screen less popup-screen-margin-percentage on each side (theme.xml, A), the
     * text wrapping at the space before (sim-62, sim-42, C); centred.
     */
    const val TEXT_X = 19
    const val ICON_X = 20
    const val ICON_TEXT_X = ICON_X + 80 + 3
    const val PAD_RIGHT = 20
    const val SCREEN_MARGIN_PERCENT = 5

    /** An icon left of the text: dialog_*.png, 80x80, its picture 50x50 in the middle (A; sim-47, sim-62, C). */
    const val ICON = 80

    /**
     * The content (icon, or text lines of 32 px with their baseline 25 down) starts 22 px under the
     * top border; the text is centred on the icon (sim-19, sim-47, sim-62, sim-42, C).
     */
    const val PAD_TOP = 22
    const val LINE = 32
    const val ASCENT = 25

    /** Buttons 51 px high, 74 apart, the first 24 px under the content, the box ending 41 px under the last (sim-19, sim-47, sim-62, C); 20 px without buttons (sim-42, C). */
    const val BUTTON_HEIGHT = 51
    const val BUTTON_PITCH = 74
    const val BUTTON_TOP = 24
    const val PAD_BOTTOM_BUTTONS = 41
    const val PAD_BOTTOM = 20

    /** A text box (sim-61, C): the field 4 px under the label, 56 high (border_find.png stretched, A), 20 px in from the box on the left and 19 on the right; its text 13 px in. */
    const val FIELD_GAP = 4
    const val FIELD_HEIGHT = 56
    const val FIELD_LEFT = TEXT_X + 1
    const val FIELD_RIGHT = PAD_RIGHT - 1
    const val FIELD_TEXT_X = 13

    /**
     * A button's width, the same for all: 0.545 of the box (sim-19: 238 of 439; sim-62: 265 of 485,
     * C), or its longest label and the 20 px of each side of border_buttongrey (theme.xml, A)
     * if wider (sim-65, Emergency, C); its label on a baseline 35 px under its top (C).
     */
    const val BUTTON_RATIO = 0.545f
    const val BUTTON_PAD = 20
    const val BUTTON_BASELINE = 35

    /** The first letter underlined, 2 px from 1 under the baseline (sim-47, Save, C). */
    const val MNEMONIC_Y = 1
    const val MNEMONIC_HEIGHT = 2
}

// Legacy values, still read by the trackpad (core/ui) and the frames: the
// previous navigator.
object Dimens {
    const val ICON = HomeDimens.ICON // The 9900 theme's 92x92 icons, pixel for pixel
    const val ICON_ANDROID = 86
    const val FRAMES_MAX = 8
    const val TRACKPAD_STEP = Eyeballed.TRACKPAD_STEP
}

/** Q25: density 208, factor 1.3 (docs/DEVICE.md). */
object Density {
    const val DPI = 208
    const val FACTOR = DPI / 160f

    fun pxToDp(px: Int): Float = px / FACTOR

    /** Same rule as TypedValue.complexToDimensionPixelSize: rounded, and a non-zero size keeps 1 px. */
    fun dpToPx(dp: Float): Int {
        val px = (dp * FACTOR).roundToInt()
        return if (px == 0 && dp != 0f) dp.sign.toInt() else px
    }
}
