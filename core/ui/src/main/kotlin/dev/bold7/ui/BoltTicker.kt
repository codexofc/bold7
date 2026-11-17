package dev.bold7.ui

import android.os.SystemClock
import android.view.Display
import android.view.View
import dev.bold7.design.ChargingBolt

/**
 * The charging bolt's clock for a banner [view] (ChargingBolt, core/design): one redraw of the
 * view at each change of image while it charges, the view is shown and its screen is on, and none
 * otherwise. A delayed message on the view's own thread, on uptime: it never wakes the phone and
 * stops while the screen is off. The view tells it [charging], [visible] (onVisibilityAggregated)
 * and [screen] (onScreenStateChanged), and draws [image].
 */
class BoltTicker(private val view: View) : Runnable {
    private var since = 0L
    private var shown = false
    private var screenOn = false

    var charging = false
        set(value) {
            if (field == value) return
            field = value
            since = SystemClock.uptimeMillis()
            update()
        }

    /** The image to draw now: 1 to 5 for lightning_bolt_01 to _05, 0 for none. */
    val image: Int
        get() = if (charging) ChargingBolt.image(SystemClock.uptimeMillis() - since) else 0

    fun visible(isVisible: Boolean) {
        shown = isVisible
        screenOn = view.display?.state == Display.STATE_ON
        update()
    }

    fun screen(state: Int) {
        screenOn = state == View.SCREEN_STATE_ON
        update()
    }

    private fun update() {
        view.removeCallbacks(this)
        if (charging && shown && screenOn) run() else view.invalidate()
    }

    override fun run() {
        view.invalidate()
        view.postDelayed(this, ChargingBolt.untilNext(SystemClock.uptimeMillis() - since))
    }
}
