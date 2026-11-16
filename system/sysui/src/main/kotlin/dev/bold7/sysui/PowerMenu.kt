package dev.bold7.sysui

// What OS 7 shows at the power key, over Android's own power menu actions (docs/BBOS7-VISUEL.md
// §23). Pure.

/** The Android actions the dialog carries; anything else Android offers is left out. */
enum class PowerAction { POWER_OFF, RESTART, EMERGENCY }

/** By the class of Android's action (GlobalActionsDialogLite's inner classes, kept by R8). */
fun powerAction(className: String): PowerAction? = when (className.substringAfterLast('$')) {
    "ShutDownAction" -> PowerAction.POWER_OFF
    "RestartAction" -> PowerAction.RESTART
    "EmergencyDialerAction", "EmergencyAffordanceAction" -> PowerAction.EMERGENCY
    else -> null
}

/** A button of the dialog and the Android action it runs, none for Cancel. */
enum class PowerButton(val action: PowerAction?) {
    /** Full Power Off: OS 7's button when the phone may stay off (manual p. 301, B), Android's power off. */
    FULL_POWER_OFF(PowerAction.POWER_OFF),

    /** Not on the 9900 (D): Android's restart, kept. */
    RESTART(PowerAction.RESTART),
    CANCEL(null),

    /** Last, as Emergency under Cancel in "Device is locked" (sim-65, C). */
    EMERGENCY(PowerAction.EMERGENCY),
}

/** The buttons, top to bottom, with the actions Android offers right now; Cancel always. */
fun powerButtons(offered: Set<PowerAction>): List<PowerButton> =
    PowerButton.entries.filter { it.action == null || it.action in offered }

/** The focus at opening: Cancel, as on OS 7's confirmation of an emergency call (sim-88, C; D here). */
fun powerFocus(buttons: List<PowerButton>): Int = buttons.indexOf(PowerButton.CANCEL)
