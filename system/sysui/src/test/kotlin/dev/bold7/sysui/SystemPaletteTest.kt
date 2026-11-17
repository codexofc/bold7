package dev.bold7.sysui

import dev.bold7.design.Palette
import dev.bold7.design.Theme
import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPaletteTest {
    private fun c(name: String) = SYSTEM_COLORS.getValue(name)
    private fun hex(argb: Int) = "#%08X".format(argb)
    private val schemes = listOf("light", "dark")

    // What SystemUI writes in its three fabricated overlays on the Q25 (LineageOS 24, read in
    // com.android.systemui-{accent,neutral,dynamic}-*.frro): each tonal shade and each role once per
    // scheme, as system_<name>_light and system_<name>_dark, which the framework's public
    // system_<name> points to (values and values-night).
    private val q25Shades = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
    private fun tonal(vararg palettes: String) = palettes.flatMap { p -> q25Shades.flatMap { s -> schemes.map { "${p}_${s}_$it" } } }
    private val q25Roles = """
        background brand_a brand_b brand_c brand_d clock_hour clock_minute clock_second control_activated control_highlight
        control_normal error error_container error_dim inverse_on_surface inverse_primary inverse_surface on_background on_error
        on_error_container on_primary on_primary_container on_primary_fixed on_primary_fixed_variant on_secondary
        on_secondary_container on_secondary_fixed on_secondary_fixed_variant on_shade_active on_shade_active_variant
        on_shade_inactive on_shade_inactive_variant on_surface on_surface_variant on_tertiary on_tertiary_container
        on_tertiary_fixed on_tertiary_fixed_variant on_theme_app outline outline_variant overview_background
        palette_key_color_error palette_key_color_neutral palette_key_color_neutral_variant palette_key_color_primary
        palette_key_color_secondary palette_key_color_tertiary primary primary_container primary_dim primary_fixed
        primary_fixed_dim scrim secondary secondary_container secondary_dim secondary_fixed secondary_fixed_dim shade_active
        shade_disabled shade_inactive shadow surface surface_bright surface_container surface_container_high
        surface_container_highest surface_container_low surface_container_lowest surface_dim surface_effect_0
        surface_effect_0_fallback surface_effect_1 surface_effect_2 surface_effect_3 surface_tint surface_variant tertiary
        tertiary_container tertiary_dim tertiary_fixed tertiary_fixed_dim text_hint_inverse text_primary_inverse
        text_primary_inverse_disable_only text_secondary_and_tertiary_inverse text_secondary_and_tertiary_inverse_disabled
        theme_app theme_app_ring theme_notif under_surface weather_temp widget_background
    """.trim().split(Regex("\\s+"))
    private fun res(names: List<String>) = names.map { "android:color/system_$it" }
    private val q25Written = mapOf(
        "mAccentOverlay" to res(tonal("accent1", "accent2", "accent3")),
        "mNeutralOverlay" to res(tonal("neutral1", "neutral2")),
        "mDynamicOverlay" to res(q25Roles.flatMap { r -> schemes.map { "${r}_$it" } }),
    )

    // Every public system_ color of the Q25's framework (stubs/framework.jar first on the test
    // classpath) is OS 7's: written itself, or both the _light and _dark it points to. Left to the
    // framework: a transparent constant and three state lists over other system_ colors.
    @Test fun coversExactlyThePublicSystemColorsSystemUiWrites() {
        val public = android.R.color::class.java.fields.map { it.name }.filter { it.startsWith("system_") }.map { it.removePrefix("system_") }
        val notPalette = setOf("notification_accent_color", "surface_disabled", "on_surface_disabled", "outline_disabled")
        assertTrue(public.size > 190, "${public.size}")
        for (n in public - notPalette) assertTrue(n in SYSTEM_COLORS || "${n}_light" in SYSTEM_COLORS && "${n}_dark" in SYSTEM_COLORS, n)
        // Every name written is the framework's, else the fabricated overlay would be refused.
        val framework = JarMembers(File("../../stubs/framework.jar")).fields("com.android.internal.R\$color")!!
        for (n in SYSTEM_COLORS.keys) assertTrue("system_$n" in framework, n)
        // What SystemUI writes, plus the error palette it leaves to the framework, and nothing else.
        val written = q25Written.values.flatten().map { it.removePrefix("android:color/system_") }.toSet()
        assertEquals(written + tonal("error"), SYSTEM_COLORS.keys)
        assertEquals(318 + 26, SYSTEM_COLORS.size)
    }

    @Test fun anchorsAreOs7Colors() {
        for (s in schemes) {
            assertEquals(Palette.PrimaryHighlight, c("accent1_600_$s"))
            assertEquals(Palette.PrimaryBase, c("accent1_700_$s"))
            assertEquals(Palette.PrimaryShade, c("accent1_800_$s"))
            assertEquals(Palette.PrimaryPastel, c("accent1_200_$s"))
            assertEquals(Palette.FocusOutline, c("accent1_500_$s"))
            assertEquals(Palette.BlueHighlight, c("accent3_600_$s"))
            assertEquals(Palette.RedBase, c("error_600_$s"))
            assertEquals(Theme.Bright.surface, c("neutral1_50_$s"))
            assertEquals(Theme.Dark.surface, c("neutral1_800_$s"))
            assertEquals(Theme.Dark.surfaceBase, c("neutral1_900_$s"))
            assertEquals(Theme.Dark.background, c("neutral1_1000_$s"))
            assertEquals(0xFFFFFFFF.toInt(), c("neutral1_0_$s"))
        }
    }

    @Test fun inBetweenShadesAreInterpolatedInSrgb() {
        assertEquals("#FF93C6F4", hex(c("accent1_300_light"))) // half way from #BAD1F3 to #6BBBF5
        assertEquals("#FFEEF4FC", hex(c("accent1_10_light"))) // a quarter from white to #BAD1F3
        assertEquals("#FF4F5155", hex(c("neutral1_700_dark"))) // half way from #7C7E80 to #212429
        assertEquals("#FFD5D7D9", hex(c("neutral2_200_dark"))) // a third from #E8EAEB to #AFB1B4
    }

    // OS 7 has one palette: the tonal shades and the fixed roles are the same in both schemes.
    @Test fun tonalShadesAndFixedRolesAreTheSameInBothSchemes() {
        val tonal = Regex("[a-z]+\\d?_\\d+")
        val same = SYSTEM_COLORS.keys.map { it.removeSuffix("_light").removeSuffix("_dark") }.filter { tonal.matches(it) || "fixed" in it }.toSet()
        assertEquals(6 * 13 + 12, same.size)
        for (role in same) assertEquals(c("${role}_light"), c("${role}_dark"), role)
    }

    @Test fun oneAccentLikeOs7() {
        for (shade in q25Shades) assertEquals(c("accent1_${shade}_light"), c("accent2_${shade}_light"))
        assertEquals(Palette.PrimaryHighlight, c("primary_light"))
        assertEquals(Palette.PrimaryHighlight, c("primary_dark"))
    }

    // OS 7's greys lean a little blue (#212429, #1E2125, #43454A): the cast Material You would take
    // from the wallpaper is theirs, a few steps at most, blue never below red or green.
    @Test fun neutralsAreOs7sCoolGreys() {
        for (p in listOf("neutral1", "neutral2")) for (shade in q25Shades) {
            val v = c("${p}_${shade}_dark")
            val (r, g, b) = Triple(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
            assertTrue(b >= r && b >= g && b - r <= 10, "${p}_$shade ${hex(v)}")
        }
    }

    // Material keeps 10 the lightest after white and 1000 the darkest; the framework pairs shades on that.
    @Test fun tonalPalettesDarkenStepByStep() {
        for (p in listOf("accent1", "accent2", "accent3", "neutral1", "neutral2", "error")) {
            q25Shades.drop(1).zipWithNext().forEach { (a, b) ->
                val (x, y) = c("${p}_${a}_light") to c("${p}_${b}_light")
                assertTrue(luminance(x) > luminance(y), "${p}_$a ${hex(x)} not lighter than ${p}_$b ${hex(y)}")
            }
        }
    }

    // Dark dialogs: the floating surface of the dark scheme is OS 7's overlay grey.
    @Test fun darkDialogSurfaces() {
        assertEquals(Theme.Dark.overlay, c("surface_container_high_dark"))
        assertEquals(Theme.Dark.textPrimary, c("on_surface_dark"))
        assertEquals(Theme.Dark.background, c("background_dark"))
    }

    // Quick settings, always on the dark shade: an active tile is OS 7's focus blue with white
    // text, an inactive one the flat button grey of the dark menus.
    @Test fun shadeTilesAreOs7s() {
        for (s in schemes) {
            assertEquals(Palette.PrimaryHighlight, c("shade_active_$s"))
            assertEquals(Palette.TextOnPrimary, c("on_shade_active_$s"))
            assertEquals(Theme.Dark.divider, c("shade_inactive_$s"))
            assertEquals(Theme.Dark.textPrimary, c("on_shade_inactive_$s"))
        }
    }

    // Only control_highlight and the shade's surface effects are translucent, as Android's
    // (contrast code throws on a translucent background): OS 7's colors at Android's opacity.
    @Test fun opaqueButTheHighlightAndTheEffects() {
        val translucent = SYSTEM_COLORS.filterKeys { it.startsWith("control_highlight") || it.startsWith("surface_effect") && "fallback" !in it }
        for ((name, v) in SYSTEM_COLORS - translucent.keys) assertEquals(0xFF, v ushr 24, name)
        assertEquals(10, translucent.size)
        assertEquals(Theme.Bright.pressed, c("control_highlight_light"))
        assertEquals(Theme.Dark.pressed, c("control_highlight_dark"))
        assertEquals("#80212429", hex(c("surface_effect_0_dark")))
        assertEquals(Theme.Dark.overlay, c("surface_effect_0_fallback_dark"))
    }

    // What is written on a color stays legible: 4.5:1 for text on surfaces, 3:1 on accents
    // (white on #088EEF is 3.4:1).
    @Test fun textOnItsSurfaceIsLegible() {
        for (scheme in schemes) {
            fun pair(on: String, under: String, min: Double) {
                val ratio = contrast(c("${on}_$scheme"), c("${under}_$scheme"))
                assertTrue(ratio >= min, "${on}_$scheme on ${under}_$scheme: $ratio")
            }
            for (s in listOf("background", "surface", "surface_container_high", "surface_container_highest")) pair(if (s == "background") "on_background" else "on_surface", s, 4.5)
            pair("on_surface_variant", "surface_variant", 3.0)
            pair("inverse_on_surface", "inverse_surface", 4.5)
            pair("on_shade_inactive", "shade_inactive", 4.5)
            for (r in listOf("primary", "secondary", "tertiary", "error", "primary_container", "secondary_container", "tertiary_container", "error_container", "shade_active", "theme_app")) pair("on_$r", r, 3.0)
        }
    }

    @Test fun someRolesPinned() {
        assertEquals("#FFD9DCDE", hex(c("outline_variant_light"))) // the list rule, opaque
        assertEquals("#FF43454A", hex(c("outline_variant_dark"))) // the menu rule, opaque
        assertEquals(Theme.Bright.textSecondary, c("on_surface_variant_light"))
        assertEquals(Theme.Dark.textSecondary, c("on_surface_variant_dark"))
        assertEquals(Palette.PrimaryHighlight, c("palette_key_color_secondary_light"))
        assertEquals(Palette.PrimaryHighlight, c("palette_key_color_secondary_dark"))
        assertEquals(Theme.Dark.overlay, c("inverse_surface_light"))
        assertEquals(Palette.PrimaryBase, c("primary_dim_light"))
    }

    @Test fun theThreeOverlaysOfTheQ25() {
        val overlays = paletteOverlays(q25Written)
        assertEquals(listOf("mAccentOverlay" to "accent", "mNeutralOverlay" to "neutral", "mDynamicOverlay" to "dynamic"), overlays.map { it.field to it.name })
        assertEquals(listOf(78 + 26, 52, 188), overlays.map { it.colors.size })
        assertEquals(SYSTEM_COLORS.keys, overlays.flatMap { it.colors }.toSet())
        assertTrue(overlays[0].colors.all { it.startsWith("accent") || it.startsWith("error_") })
        assertTrue(overlays[1].colors.all { it.startsWith("neutral") })
        assertEquals("background_light", overlays[2].colors.first())
    }

    // A color OS 7 has no value for, a resource that is not a color, or an overlay gone: Android's kept.
    @Test fun anUnknownColorKeepsAndroids() {
        fun with(field: String, extra: String) = q25Written + (field to q25Written.getValue(field) + extra)
        assertFailsWith<IllegalStateException> { paletteOverlays(with("mDynamicOverlay", "android:color/system_surface_glow_light")) }
        assertFailsWith<IllegalStateException> { paletteOverlays(with("mAccentOverlay", "android:dimen/system_corner")) }
        assertFailsWith<IllegalStateException> { paletteOverlays(q25Written - "mNeutralOverlay") }
        assertFailsWith<IllegalStateException> { paletteOverlays(q25Written + ("mNeutralOverlay" to emptyList())) }
    }

    @Test fun inPlaceOnlyWhenEveryColorIsOs7s() {
        assertTrue(os7PaletteInPlace { SYSTEM_COLORS.getValue(it) })
        assertFalse(os7PaletteInPlace { if (it == "accent1_600_dark") 0xFF495D92.toInt() else SYSTEM_COLORS.getValue(it) })
        assertFalse(os7PaletteInPlace { if (it == "error_0_light") 0 else SYSTEM_COLORS.getValue(it) })
    }

    // Read on the Q25 (cmd overlay lookup): Android's palette in place.
    private val q25Posed = mapOf("accent1_600_dark" to 0xFF46617E, "neutral1_900_dark" to 0xFF191C20).mapValues { it.value.toInt() }

    // First start with the module: Android's palette in place, OS 7's overlays registered.
    @Test fun firstStartRegistersOs7s() = assertEquals(PaletteStep.REGISTER, paletteStep { q25Posed[it] ?: 0 })

    // Every later start: OS 7's palette in place, SystemUI's registration skipped, as nothing changes.
    @Test fun laterStartsDoNotRegisterAgain() = assertEquals(PaletteStep.ALREADY_IN_PLACE, paletteStep { SYSTEM_COLORS.getValue(it) })

    // After the module's retrait disabled the overlays, or with one of them left Android's: registered again.
    @Test fun aPartlyOs7PaletteIsRegistered() =
        assertEquals(PaletteStep.REGISTER, paletteStep { if (it.startsWith("neutral")) 0xFF75757C.toInt() else SYSTEM_COLORS.getValue(it) })

    private fun luminance(argb: Int): Double {
        fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(argb shr 16 and 0xFF) + 0.7152 * lin(argb shr 8 and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
