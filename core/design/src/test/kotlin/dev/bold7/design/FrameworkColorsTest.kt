package dev.bold7.design

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * system/overlays/android's colors.xml against the tokens it is made of, and the
 * text drawn on each surface it sets: a color that drifts from its token, or a pair that becomes
 * unreadable, fails here. Android's own text colors on these surfaces are the framework's
 * (black or white, foreground_material_*), so those are the texts checked.
 */
class FrameworkColorsTest {
    private val file = File("../../system/overlays/android/src/main/res/values/colors.xml")
    private val posed = Regex("""<color name="(\w+)">#([0-9a-fA-F]{8})</color>""")
        .findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }

    private val tokens: Map<String, Int> = mapOf(
        "background_material_dark" to Theme.Dark.background,
        "background_material_light" to Theme.Bright.background,
        "background_floating_material_dark" to Theme.Dark.overlay,
        "autofill_background_material_dark" to Theme.Dark.overlay,
        "autofill_background_material_light" to Theme.Bright.overlay,
        "primary_material_dark" to Theme.Dark.surface,
        "primary_material_light" to Theme.Bright.surface,
        "primary_dark_material_light" to Theme.Bright.textSecondary,
        "secondary_material_settings" to Theme.Dark.surface,
        "secondary_material_settings_light" to Theme.Bright.surfaceBase,
        "tertiary_material_settings" to Theme.Dark.divider,
        "material_deep_teal_100" to Palette.PrimaryPastel,
        "material_deep_teal_200" to Palette.PrimaryHighlight,
        "material_deep_teal_300" to Palette.PrimaryHighlight,
        "material_deep_teal_500" to Palette.PrimaryHighlight,
        "datepicker_default_selected_text_color_material_light" to Palette.PrimaryHighlight,
        "datepicker_default_selected_text_color_material_dark" to Palette.PrimaryHighlight,
        "datepicker_default_pressed_text_color_material_light" to Palette.PrimaryBase,
        "datepicker_default_pressed_text_color_material_dark" to Palette.PrimaryBase,
        "datepicker_default_header_selector_background_material_dark" to Theme.Dark.surface,
        "datepicker_default_view_animator_color_material_light" to Theme.Bright.surface,
        "datepicker_default_view_animator_color_material_dark" to Theme.Dark.surface,
        "button_material_dark" to Theme.Dark.divider,
        "button_material_light" to Theme.Bright.surfaceBase,
        "switch_thumb_normal_material_dark" to Theme.Dark.textSecondary,
        "switch_thumb_normal_material_light" to Theme.Bright.background,
        "switch_thumb_disabled_material_dark" to Theme.Dark.textTertiary,
        "switch_thumb_disabled_material_light" to Theme.Bright.textTertiary,
        "error_color_material_dark" to Palette.RedHighlight,
        "error_color_material_light" to Palette.RedBase,
        "loading_gradient_background_color_dark" to Theme.Dark.divider,
        "loading_gradient_background_color_light" to Theme.Bright.surface,
        "loading_gradient_highlight_color_light" to Theme.Bright.surfaceBase,
        "tooltip_background_dark" to withOpacity(Theme.Dark.overlay, 0.9f),
    )

    @Test fun everyColorIsItsToken() {
        for ((name, token) in tokens) assertEquals(token, posed[name], "$name")
        // The one with no token (the dark shimmer's highlight, D) is the only one left over.
        assertEquals(setOf("loading_gradient_highlight_color_dark"), posed.keys - tokens.keys)
    }

    @Test fun noneOfAndroidsOrangeTealOrMaterialGreyRemains() {
        val gone = setOf(0xFF5722, 0xFF7043, 0x008577, 0x80CBC4, 0x424242, 0x303030, 0xFAFAFA, 0x212121, 0xF5F5F5)
        for ((name, c) in posed) assertTrue((c and 0xFFFFFF) !in gone, "$name still ${hex(c)}")
    }

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // [text, surface, minimum ratio]: WCAG 4.5 for text, 3 for what is only an accent or a state (D).
    @Test fun textStaysReadable() {
        val d = Theme.Dark
        val b = Theme.Bright
        val pairs = listOf(
            Triple("white on background_material_dark", white to posed.getValue("background_material_dark"), 4.5),
            Triple("white on background_floating_material_dark", white to posed.getValue("background_floating_material_dark"), 4.5),
            Triple("white on primary_material_dark", white to posed.getValue("primary_material_dark"), 4.5),
            Triple("white on secondary_material_settings", white to posed.getValue("secondary_material_settings"), 4.5),
            Triple("white on tertiary_material_settings", white to posed.getValue("tertiary_material_settings"), 4.5),
            Triple("white on button_material_dark", white to posed.getValue("button_material_dark"), 4.5),
            Triple("white on tooltip", white to over(posed.getValue("tooltip_background_dark"), black), 4.5),
            Triple("white on the dark shimmer", white to posed.getValue("loading_gradient_highlight_color_dark"), 4.5),
            Triple("dark primary text on background", d.textPrimary to posed.getValue("background_floating_material_dark"), 4.5),
            Triple("black on background_material_light", black to posed.getValue("background_material_light"), 4.5),
            Triple("black on primary_material_light", black to posed.getValue("primary_material_light"), 4.5),
            Triple("black on secondary_material_settings_light", black to posed.getValue("secondary_material_settings_light"), 4.5),
            Triple("black on button_material_light", black to posed.getValue("button_material_light"), 4.5),
            Triple("black on the light shimmer", black to posed.getValue("loading_gradient_highlight_color_light"), 4.5),
            Triple("black on autofill light", black to posed.getValue("autofill_background_material_light"), 4.5),
            Triple("white on the picker's accent", white to posed.getValue("material_deep_teal_500"), 3.0),
            Triple("white on primary_dark_material_light", white to posed.getValue("primary_dark_material_light"), 3.0),
            Triple("error, light, on white", posed.getValue("error_color_material_light") to b.background, 4.5),
            Triple("error, light, on its surface", posed.getValue("error_color_material_light") to b.surface, 4.5),
            Triple("error, dark, on black", posed.getValue("error_color_material_dark") to d.background, 4.5),
            Triple("error, dark, on the dialog", posed.getValue("error_color_material_dark") to d.overlay, 3.0),
            Triple("accent on black", posed.getValue("material_deep_teal_200") to d.background, 4.5),
            Triple("accent on the dialog", posed.getValue("material_deep_teal_200") to d.overlay, 3.0),
            Triple("accent on white", posed.getValue("material_deep_teal_500") to b.background, 3.0),
            Triple("the switch's thumb on the dark dialog", posed.getValue("switch_thumb_normal_material_dark") to d.overlay, 3.0),
        )
        for ((label, p, min) in pairs) assertTrue(contrast(p.first, p.second) >= min, "$label: ${"%.2f".format(contrast(p.first, p.second))} < $min")
    }

    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)

    private fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255f
        return (0..2).fold(0xFF shl 24) { acc, k -> val s = k * 8; acc or ((((fg ushr s and 0xFF) * a + (bg ushr s and 0xFF) * (1 - a)).toInt()) shl s) }
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
