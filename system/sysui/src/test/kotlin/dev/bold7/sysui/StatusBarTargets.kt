package dev.bold7.sysui

import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.`BarTransitions$BarBackgroundDrawable`
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController
import com.android.systemui.statusbar.phone.`HeadsUpAppearanceController$$ExternalSyntheticLambda2`
import com.android.systemui.statusbar.policy.Clock

// What StatusBarLayout reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run, the stubs have no code.
@Suppress("unused", "UNUSED_VARIABLE")
private fun statusBarTargets(
    clock: Clock,
    queue: CommandQueue,
    background: `BarTransitions$BarBackgroundDrawable`,
    regions: Array<AppearanceRegion>,
    letterbox: Array<LetterboxDetails>,
    headsUp: HeadsUpAppearanceController,
    view: android.view.View,
    done: `HeadsUpAppearanceController$$ExternalSyntheticLambda2`?,
) {
    val time: CharSequence = clock.getSmallTime()
    // displayId, appearance, regions, navbarColorManagedByIme, behavior, requestedVisibleTypes,
    // packageName, letterboxDetails: StatusBarLayout reads arguments 0 and 6.
    queue.onSystemBarAttributesChanged(0, 0, regions, false, 0, 0, "package", letterbox)
    background.mOverrideAlpha = 1f
    headsUp.hide(view, android.view.View.INVISIBLE, done)
}
