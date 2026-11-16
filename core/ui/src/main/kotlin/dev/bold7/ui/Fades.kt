package dev.bold7.ui

import android.view.View
import dev.bold7.design.Curves
import dev.bold7.design.Fade
import dev.bold7.design.toInterpolator

/**
 * Fades measured on the 9900's simulator (docs/BBOS7-VISUEL.md § 36, C). The
 * short menu only: the full menu and the dialogs are cuts.
 */
object MenuFades {
    /** The short menu appears at 65 % opacity, 90 ms (0,67 then 0,98 then 1 on 2 takes). */
    val OPEN = Fade(0.65f, 1f, 90, Curves.QuadraticOut)

    /** It leaves in 190 ms (158 and 225 measured), slowly first: the page below came back 0,03 ; 0,17 ; 0,50 ; 1, a cubic start. */
    val CLOSE = Fade(1f, 0f, 190, Curves.CubicIn)
}

/** Plays [fade] on this view's opacity, then [onEnd] (also run when cancelled: the caller checks its own state). */
fun View.play(fade: Fade, onEnd: () -> Unit = {}) {
    animate().cancel()
    alpha = fade.from
    animate().alpha(fade.to).setDuration(fade.durationMs).setInterpolator(fade.curve.toInterpolator())
        .withEndAction { onEnd() }.start()
}
