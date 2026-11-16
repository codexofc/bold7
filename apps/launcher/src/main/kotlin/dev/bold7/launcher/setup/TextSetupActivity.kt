package dev.bold7.launcher.setup

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.bold7.design.ListColors
import dev.bold7.launcher.graph
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.TitleBar

/**
 * "Setup" over a blank page, as OS 7's Setup application is the place to set the phone up (guide
 * 7.1, "Visit the Setup application", M; no capture of it, so the page is D) and asks in its
 * dialog (sim-62, C): the question, then its buttons stacked. Continue opens Google's switch; back
 * from there the state is read again and the screen closes if it is on, else asks to try again or
 * to leave it. Later, Back and a switch found on all close it for good: it never opens on its own
 * again, only from Options > Device. Nothing is set for the user.
 */
class TextSetupActivity : Activity() {
    private val device by lazy { TextSetupDevice(applicationContext) }
    private var waiting = false
    private var step = Step.INTRO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ListColors.Background)
                addView(TitleBar(this@TextSetupActivity, "Setup"), ViewGroup.LayoutParams.MATCH_PARENT, TitleBar.HEIGHT)
            },
        )
        window.hideStatusBar()
        graph.io.post { device.markAsked() }
        ask(Step.INTRO)
    }

    private fun ask(next: Step) {
        step = next
        Bold7Dialog(
            this, next.text, next.buttons, DialogIcon.QUESTION,
            onButton = { i -> if (press(next, i) == Press.OPEN) open() else finish() },
            onCancel = { finish() },
            // Shown from onCreate, before this window has its insets: the dialog cannot read that the
            // bar is hidden here, and its window would paint the bar's black over the title.
            hideStatusBar = true,
        ).show()
    }

    private fun open() = device.openPage(this) { opened -> if (opened) waiting = true else finish() }

    // Back from Google's page: its switch read again, off the main thread.
    override fun onResume() {
        super.onResume()
        if (!waiting) return
        waiting = false
        graph.io.post {
            val next = afterReturn(device.consent())
            runOnUiThread { if (!isDestroyed) next?.let(::ask) ?: finish() }
        }
    }
}
