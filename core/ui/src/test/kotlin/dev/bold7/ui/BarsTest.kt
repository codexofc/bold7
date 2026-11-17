package dev.bold7.ui

import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
import kotlin.test.Test
import kotlin.test.assertEquals

class BarsTest {
    private val own = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    private val plain = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    @Test fun aWindowThatLaysItselfOutGetsNothing() {
        assertEquals(BarFit.NONE, barFit(own, SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_ADJUST_RESIZE))
        assertEquals(BarFit.NONE, barFit(own, 0))
    }

    @Test fun everyOtherStartsUnderTheBars() {
        assertEquals(BarFit.BARS, barFit(plain, 0))
        assertEquals(BarFit.BARS, barFit(plain, SOFT_INPUT_STATE_ALWAYS_HIDDEN))
        assertEquals(BarFit.BARS, barFit(plain, SOFT_INPUT_ADJUST_PAN))
        assertEquals(BarFit.BARS, barFit(plain, SOFT_INPUT_ADJUST_NOTHING))
    }

    @Test fun andAboveTheKeyboardOnlyWhenItResizesForIt() {
        assertEquals(BarFit.BARS_AND_IME, barFit(plain, SOFT_INPUT_STATE_ALWAYS_HIDDEN or SOFT_INPUT_ADJUST_RESIZE))
    }
}
