package dev.bold7.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import dev.bold7.design.ListColors

/**
 * The one-line banner's black, 34 px, then a 1 px #1F1F1F line at y 34, the content from y 35
 * (sim-109, sim-141, C: black to y 33, the line, the content's first row at y 35).
 */
const val BAR_BLACK = 34
const val BAR_LINE = BAR_BLACK + 1

/**
 * Sets [content] as the screen, drawn under the Phone's one-line status bar: the window starts at
 * the screen's top edge, black for the banner's line, and the content from y [BAR_LINE] (35) instead of
 * under the 67 px Android reserves (the bar's window leaves the 33 px below its line clear for
 * the Phone, SystemUI's barBackground). Only the bottom keeps its system insets (keyboard, bar).
 */
fun Activity.setContentUnderBar(content: View) {
    window.setDecorFitsSystemWindows(false)
    // Lays itself out: left alone by fitBars (Bars.kt). statusBarColor has no effect from target 35.
    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    setContentView(FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        setPadding(0, BAR_LINE, 0, 0)
        clipToPadding = false
        // Margins count from the padding: -1 puts the line on y 34, the padding being 35.
        addView(View(context).apply { setBackgroundColor(ListColors.TitleBarLine) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = BAR_BLACK - BAR_LINE })
        addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, BAR_LINE, 0, insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom)
            insets
        }
    })
}
