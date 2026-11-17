package dev.bold7.sysui

import dev.bold7.ui.DialogLayout
import dev.bold7.ui.ELLIPSIS
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HearingWarningTest {
    // DialogInterface.BUTTON_*.
    private val positive = -1
    private val negative = -2
    private val neutral = -3
    private val os7 = HearingWords(text = "WARNING: …", proceed = "Proceed", cancel = "Cancel")

    // Android's safe media volume warning (Yes / No) takes OS 7's Audio Boost warning (sim-341 to
    // sim-343): its words, Proceed on Android's Yes and Cancel on its No, the focus on Cancel.
    @Test fun safeVolumeWarningTakesOs7sWords() {
        val face = hearingFace(HearingWarning.SAFE_VOLUME, "Raise volume above recommended level?", listOf(HearingButton(positive, "Yes"), HearingButton(negative, "No")), os7)
        assertEquals(HearingFace("WARNING: …", listOf(HearingButton(positive, "Proceed"), HearingButton(negative, "Cancel")), focused = 1), face)
    }

    // OS 7 had no warning of the sound dose: Android's words and buttons, in OS 7's box, the focus
    // on Android's "Lower volume".
    @Test fun csdWarningKeepsAndroidsWords() {
        val buttons = listOf(HearingButton(positive, "Keep listening"), HearingButton(negative, "Lower volume"))
        assertEquals(HearingFace("You've been listening at high volume", buttons, focused = 1), hearingFace(HearingWarning.CSD, "You've been listening at high volume", buttons, os7))
    }

    // Whatever Android's dialog does not have as expected stays Android's: a box with fewer
    // choices would weaken the warning.
    @Test fun anythingUnexpectedStaysAndroids() {
        assertNull(hearingFace(HearingWarning.SAFE_VOLUME, "?", listOf(HearingButton(negative, "No")), os7))
        assertNull(hearingFace(HearingWarning.SAFE_VOLUME, "?", listOf(HearingButton(positive, "Yes")), os7))
        assertNull(hearingFace(HearingWarning.SAFE_VOLUME, "?", listOf(HearingButton(positive, "Yes"), HearingButton(negative, "No"), HearingButton(neutral, "More")), os7))
        assertNull(hearingFace(HearingWarning.CSD, null, listOf(HearingButton(negative, "Lower volume")), os7))
        assertNull(hearingFace(HearingWarning.CSD, " ", listOf(HearingButton(negative, "Lower volume")), os7))
        assertNull(hearingFace(HearingWarning.CSD, "Listening", emptyList(), os7))
    }

    // Positive, negative, neutral from the top, as the framework's alerts; the
    // focus on the first when there is no negative.
    @Test fun buttonsInAndroidsStackedOrder() {
        val face = hearingFace(HearingWarning.CSD, "x", listOf(HearingButton(neutral, "C"), HearingButton(positive, "A")), os7)!!
        assertEquals(listOf(positive, neutral), face.buttons.map { it.which })
        assertEquals(0, face.focused)
    }

    @Test fun dialogsAreTheQ25s() = ZipFile("../../stubs/systemui.jar").use { jar ->
        assertNotNull(jar.getEntry(CSD_DIALOG.replace('.', '/') + ".class"))
        // R8 folded SafetyWarningDialog into VolumeDialogImpl's fifth anonymous class: the one
        // that holds SafetyWarningDialog's receiver and its volume key switch.
        val bytes = jar.getInputStream(jar.getEntry(SAFE_VOLUME_DIALOG.replace('.', '/') + ".class")).readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("SafetyWarningDialog\$1" in bytes && "mDisableOnVolumeUp" in bytes)
    }

    // The whole of OS 7's warning shows on the Q25's 720x720, BBAlpha Sans 27 px, in OS 7's English:
    // nothing cut (DialogLayout ends what does not fit in an ellipsis).
    @Test fun os7sWarningFitsTheScreen() {
        val font = Font.createFont(Font.TRUETYPE_FONT, File("../../assets-gen/module/system/fonts/BBAlphaSans.ttf")).deriveFont(27f)
        val context = FontRenderContext(null, true, true)
        // 3 % wider than AWT measures it, for Android's own rounding.
        val measure = { s: String -> font.getStringBounds(s, context).width.toFloat() * 1.03f }
        val strings = File("src/main/res/values/strings.xml").readText()
        val text = Regex("""<string name="hearing_warning">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)!!.groupValues[1]
            .replace("\\n", "\n").replace("\\'", "'")
        val layout = DialogLayout(text, listOf("Proceed", "Cancel"), icon = true, measure = measure, screenWidth = 720, screenHeight = 720)
        assertTrue(layout.lines.none { it.endsWith(ELLIPSIS) }, layout.lines.joinToString("\n"))
    }
}
