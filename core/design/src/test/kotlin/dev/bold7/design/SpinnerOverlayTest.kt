package dev.bold7.design

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The drop-down list of a third-party app's Spinner (system/overlays/android).
 * Two things broke it on the Q25: the DeviceDefault themes name Widget.DeviceDefault(.Light).Spinner.DropDown
 * (spinnerStyle is ?attr/dropDownSpinnerStyle), which kept Android's light panel under the rows'
 * light text (#CCCCCC on #F4F5F6, 1.4 to 1); and nothing checked the pairs. Here every style a
 * theme can name carries OS 7's dark panel, and every text/panel pair is readable.
 */
class SpinnerOverlayTest {
    private val res = File("../../system/overlays/android/src/main/res")
    private val widgets = File(res, "values/widgets.xml").readText()

    private fun style(name: String): String =
        Regex("""<style name="${Regex.escape(name)}"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(widgets)?.groupValues?.get(1) ?: error("no style $name")

    @Test fun everyDropDownStyleOfTheThemesIsTheDarkPanel() {
        val names = listOf("Widget.DeviceDefault.Spinner.DropDown", "Widget.DeviceDefault.Light.Spinner.DropDown",
            "Widget.DeviceDefault.Spinner.DropDown.ActionBar", "Widget.DeviceDefault.Light.Spinner.DropDown.ActionBar",
            "Widget.DeviceDefault.Spinner", "Widget.DeviceDefault.Light.Spinner")
        for (n in names) {
            val body = style(n)
            // The panel is bold7_inplace, laid by the fabricated overlay (ImageOverlays) on this public drawable.
            assertTrue("popupBackground\">@android:drawable/editbox_dropdown_dark_frame" in body, "$n: panel")
            assertTrue("dropDownSelector\">@drawable/spin_row_bold7" in body, "$n: row selector")
        }
    }

    private val colors = Regex("""<item (?:android:state_(\w+)="true" )?android:color="#(\w{8})" />""")
        .findAll(File(res, "color/spin_text_bold7.xml").readText())
        .map { (it.groupValues[1].ifEmpty { "rest" }) to it.groupValues[2].toLong(16).toInt() }.toMap()

    // border_inplace.png's fill (A), the lightest and the darkest row of border_spinboxbutton~focus.png (A).
    private val panel = 0xFF212429.toInt()
    private val bandLight = 0xFF0D81E9.toInt()
    private val bandDark = 0xFF1766DB.toInt()

    @Test fun textIsReadableOnItsPanel() {
        assertTrue(colors.getValue("rest") == ListColors.ChoiceListText, "values are ChoiceListText (#CCCCCC)")
        assertTrue(contrast(colors.getValue("rest"), panel) >= 4.5, "values on the panel")
        for (s in listOf("pressed", "focused", "selected")) {
            // OS 7's chosen value is white on the blue band at 27 px: large text, 3 to 1 (the band's
            // lightest row gives 3.9, its darkest 5.5).
            assertTrue(colors.getValue(s) == 0xFFFFFFFF.toInt(), s)
            assertTrue(contrast(colors.getValue(s), bandLight) >= 3.0, "$s on the band's lightest row")
            assertTrue(contrast(colors.getValue(s), bandDark) >= 4.5, "$s on the band's darkest row")
        }
    }

    private fun luminance(argb: Int): Double {
        fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(argb shr 16 and 0xFF) + 0.7152 * lin(argb shr 8 and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
