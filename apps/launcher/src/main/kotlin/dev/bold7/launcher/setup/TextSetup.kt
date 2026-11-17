package dev.bold7.launcher.setup

// Options > Device > Text Messages Setup: letting Bold7 read and send the texts through Google
// Messages. Pure: what each step says and does. The screen is TextSetupActivity; the first unlock
// shows the Setup Wizard (Wizard.kt) instead, whose Text Messages page does the same.

/** What Google Messages' "Allow Bixby and text on Galaxy devices" switch reads: UNKNOWN when it cannot be read. */
enum class Consent { ON, OFF, UNKNOWN }

enum class Step(val text: String, val buttons: List<String>) {
    INTRO(
        "Allow Bold7 to read and send your text messages through Google Messages? On the next screen, turn on \"Allow Bixby and text on Galaxy devices\".",
        listOf("Continue", "Later"),
    ),
    STILL_OFF(
        "The switch is still off. Without it, text messages open in Google Messages.",
        listOf("Try Again", "Later"),
    ),
    UNSURE(
        "The switch could not be checked. Is \"Allow Bixby and text on Galaxy devices\" on?",
        listOf("Yes", "Try Again", "Later"),
    ),
}

enum class Press { OPEN, CLOSE }

/** What button [index] of [step] does: open Google's page, or close the screen for good. */
fun press(step: Step, index: Int): Press = when (step) {
    Step.INTRO, Step.STILL_OFF -> if (index == 0) Press.OPEN else Press.CLOSE
    Step.UNSURE -> if (index == 1) Press.OPEN else Press.CLOSE
}

/** The step after coming back from Google's page with [consent] read; null when it is on: done. */
fun afterReturn(consent: Consent): Step? = when (consent) {
    Consent.ON -> null
    Consent.OFF -> Step.STILL_OFF
    Consent.UNKNOWN -> Step.UNSURE
}
