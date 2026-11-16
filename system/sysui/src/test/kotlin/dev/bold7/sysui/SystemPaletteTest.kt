package dev.bold7.sysui

import dev.bold7.design.Palette
import dev.bold7.design.Theme
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPaletteTest {
    private fun c(name: String) = SYSTEM_COLORS.getValue(name)
    private fun hex(argb: Int) = "#%08X".format(argb)

    // Every system_ color of the API 34 framework that SystemUI computes, and nothing else: a name
    // the framework does not have would make the fabricated overlay fail. The _0 shades are not
    // SystemUI's (TonalPalette.SHADE_KEYS starts at 10), they stay the framework's white.
    @Test fun coversExactlyThePublicSystemColorsSystemUiWrites() {
        val public = android.R.color::class.java.fields.map { it.name }
            .filter { it.startsWith("system_") && !it.endsWith("_0") }
            .map { it.removePrefix("system_") }.toSet()
        assertEquals(165 - 5, public.size)
        assertEquals(public, SYSTEM_COLORS.keys)
    }

    @Test fun anchorsAreOs7Colors() {
        assertEquals(Palette.PrimaryHighlight, c("accent1_600"))
        assertEquals(Palette.PrimaryBase, c("accent1_700"))
        assertEquals(Palette.PrimaryShade, c("accent1_800"))
        assertEquals(Palette.PrimaryPastel, c("accent1_200"))
        assertEquals(Palette.FocusOutline, c("accent1_500"))
        assertEquals(Palette.BlueHighlight, c("accent3_600"))
        assertEquals(Theme.Bright.surface, c("neutral1_50"))
        assertEquals(Theme.Dark.surface, c("neutral1_800"))
        assertEquals(Theme.Dark.surfaceBase, c("neutral1_900"))
        assertEquals(Theme.Dark.background, c("neutral1_1000"))
    }

    @Test fun inBetweenShadesAreInterpolatedInSrgb() {
        assertEquals("#FF93C6F4", hex(c("accent1_300"))) // half way from #BAD1F3 to #6BBBF5
        assertEquals("#FFEEF4FC", hex(c("accent1_10"))) // a quarter from white to #BAD1F3
        assertEquals("#FF4F5155", hex(c("neutral1_700"))) // half way from #7C7E80 to #212429
        assertEquals("#FFD5D7D9", hex(c("neutral2_200"))) // a third from #E8EAEB to #AFB1B4
    }

    @Test fun oneAccentLikeOs7() {
        for (shade in SHADES) assertEquals(c("accent1_$shade"), c("accent2_$shade"))
        assertEquals(Palette.PrimaryHighlight, c("primary_light"))
        assertEquals(Palette.PrimaryHighlight, c("primary_dark"))
    }

    // OS 7's greys lean a little blue (#212429, #1E2125, #43454A): the cast Material You would take
    // from the wallpaper is theirs, a few steps at most, blue never below red or green.
    @Test fun neutralsAreOs7sCoolGreys() {
        for (p in listOf("neutral1", "neutral2")) for (shade in SHADES) {
            val v = c("${p}_$shade")
            val (r, g, b) = Triple(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
            assertTrue(b >= r && b >= g && b - r <= 10, "${p}_$shade ${hex(v)}")
        }
    }

    // Material keeps 10 the lightest and 1000 the darkest; the framework pairs shades on that.
    @Test fun tonalPalettesDarkenStepByStep() {
        for (p in listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")) {
            SHADES.zipWithNext().forEach { (a, b) ->
                assertTrue(luminance(c("${p}_$a")) > luminance(c("${p}_$b")), "${p}_$a ${hex(c("${p}_$a"))} not lighter than ${p}_$b ${hex(c("${p}_$b"))}")
            }
        }
    }

    // Dark dialogs: the floating surface of the dark scheme is OS 7's overlay grey.
    @Test fun darkDialogSurfaces() {
        assertEquals(Theme.Dark.overlay, c("surface_container_high_dark"))
        assertEquals(Theme.Dark.textPrimary, c("on_surface_dark"))
        assertEquals(Theme.Dark.background, c("background_dark"))
    }

    // Only control_highlight is translucent, as Android's: contrast code throws on a translucent background.
    @Test fun opaqueButTheHighlight() {
        for ((name, v) in SYSTEM_COLORS) if (!name.startsWith("control_highlight")) assertEquals(0xFF, v ushr 24, name)
        assertEquals(Theme.Bright.pressed, c("control_highlight_light"))
        assertEquals(Theme.Dark.pressed, c("control_highlight_dark"))
    }

    // What is written on a color stays legible: 4.5:1 for text on surfaces, 3:1 on accents
    // (white on #088EEF is 3.4:1).
    @Test fun textOnItsSurfaceIsLegible() {
        for (scheme in listOf("light", "dark")) {
            fun pair(on: String, under: String, min: Double) {
                val ratio = contrast(c("${on}_$scheme"), c("${under}_$scheme"))
                assertTrue(ratio >= min, "${on}_$scheme on ${under}_$scheme: $ratio")
            }
            for (s in listOf("background", "surface", "surface_container_high", "surface_container_highest")) pair(if (s == "background") "on_background" else "on_surface", s, 4.5)
            pair("on_surface_variant", "surface_variant", 3.0)
            for (r in listOf("primary", "secondary", "tertiary", "error", "primary_container", "secondary_container", "tertiary_container", "error_container")) pair("on_$r", r, 3.0)
        }
    }

    @Test fun someRolesPinned() {
        assertEquals("#FFD9DCDE", hex(c("outline_variant_light"))) // the list rule, opaque
        assertEquals("#FF43454A", hex(c("outline_variant_dark"))) // the menu rule, opaque
        assertEquals(Theme.Bright.textSecondary, c("on_surface_variant_light"))
        assertEquals(Theme.Dark.textSecondary, c("on_surface_variant_dark"))
        assertEquals(Palette.PrimaryHighlight, c("palette_key_color_secondary_light"))
        assertEquals(Palette.PrimaryHighlight, c("palette_key_color_secondary_dark"))
    }

    // The roles of DynamicColors on the Q25 (ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED), read
    // in its dex, in their order.
    private val q25Roles = listOf(
        "primary_container", "on_primary_container", "primary", "on_primary", "secondary_container", "on_secondary_container",
        "secondary", "on_secondary", "tertiary_container", "on_tertiary_container", "tertiary", "on_tertiary", "background",
        "on_background", "surface", "on_surface", "surface_container_low", "surface_container_lowest", "surface_container",
        "surface_container_high", "surface_container_highest", "surface_bright", "surface_dim", "surface_variant",
        "on_surface_variant", "outline", "outline_variant", "error", "on_error", "error_container", "on_error_container",
        "control_activated", "control_normal", "control_highlight", "text_primary_inverse", "text_secondary_and_tertiary_inverse",
        "text_primary_inverse_disable_only", "text_secondary_and_tertiary_inverse_disabled", "text_hint_inverse",
        "palette_key_color_primary", "palette_key_color_secondary", "palette_key_color_tertiary", "palette_key_color_neutral",
        "palette_key_color_neutral_variant",
    )
    private val q25Fixed = listOf(
        "primary_fixed", "primary_fixed_dim", "on_primary_fixed", "on_primary_fixed_variant", "secondary_fixed", "secondary_fixed_dim",
        "on_secondary_fixed", "on_secondary_fixed_variant", "tertiary_fixed", "tertiary_fixed_dim", "on_tertiary_fixed", "on_tertiary_fixed_variant",
    )

    @Test fun theThreeOverlaysOfTheQ25() {
        val overlays = paletteOverlays(listOf(10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000), q25Roles, q25Fixed)
        assertEquals(listOf("mNeutralOverlay" to "neutral", "mSecondaryOverlay" to "accent", "mDynamicOverlay" to "dynamic"), overlays.map { it.field to it.name })
        assertEquals(listOf(24, 36, 100), overlays.map { it.colors.size })
        assertEquals(SYSTEM_COLORS.keys, overlays.flatMap { it.colors }.toSet())
        assertEquals(160, overlays.sumOf { it.colors.size })
        assertTrue(overlays[0].colors.all { it.startsWith("neutral") })
        assertTrue(overlays[1].colors.all { it.startsWith("accent") })
        assertEquals(listOf("primary_container_dark", "primary_container_light"), overlays[2].colors.take(2))
        assertEquals("on_tertiary_fixed_variant", overlays[2].colors.last())
    }

    @Test fun otherShadesOrAnUnknownRoleKeepAndroids() {
        assertFailsWith<IllegalStateException> { paletteOverlays(listOf(0) + SHADES, q25Roles, q25Fixed) }
        assertFailsWith<IllegalStateException> { paletteOverlays(SHADES.dropLast(1), q25Roles, q25Fixed) }
        assertFailsWith<IllegalStateException> { paletteOverlays(SHADES, q25Roles + "surface_tint", q25Fixed) }
    }

    @Test fun inPlaceOnlyWhenEveryColorIsBb10s() {
        assertTrue(bb10PaletteInPlace { SYSTEM_COLORS.getValue(it) })
        assertFalse(bb10PaletteInPlace { if (it == "accent1_600") 0xFF495D92.toInt() else SYSTEM_COLORS.getValue(it) })
        assertFalse(bb10PaletteInPlace { if (it == "on_tertiary_fixed_variant") 0 else SYSTEM_COLORS.getValue(it) })
    }

    // Apply or not. Android's check, as reevaluateSystemTheme does it on the Q25 (colorSchemeIsApplied
    // of AOSP, inlined): shade 500 of the five tonal palettes of the controller's scheme, then five
    // dynamic colors it recomputes from the wallpaper, each against the color in place.
    private fun androidSkips(posed: Map<String, Int>, scheme500: (String) -> Int, computed: Map<String, Int>) =
        SCHEME_PALETTES.all { posed.getValue("${it}_500") == scheme500(it) } && computed.all { (n, v) -> posed.getValue(n) == v }

    // Read on the Q25 (cmd overlay lookup): Android's palette in place, and what
    // Android computes for it.
    private val q25Posed = mapOf(
        "accent1_500" to 0xFF6174AB, "accent2_500" to 0xFF70758A, "accent3_500" to 0xFF8C6C8A, "neutral1_500" to 0xFF75757C,
        "neutral2_500" to 0xFF74757F, "outline_variant_dark" to 0xFF45464F, "outline_variant_light" to 0xFFC5C6D0,
        "primary_container_dark" to 0xFF314578, "primary_container_light" to 0xFFDAE2FF, "primary_fixed" to 0xFFDAE2FF,
    ).mapValues { it.value.toInt() }
    private val androidComputed = q25Posed.filterKeys { !it.endsWith("_500") }
    private val bb10Scheme500 = { p: String -> schemeShades(p)[SHADES.indexOf(500)] }

    @Test fun schemeShadesAreBb10sInAllShadesOrder() {
        for (p in SCHEME_PALETTES) assertEquals(SHADES.map { c("${p}_$it") }, schemeShades(p))
        assertEquals(6, SHADES.indexOf(500)) // TonalPalette.getS500 reads index 6
    }

    // The fault seen on the Q25 with 5f59a43: the scheme left as Android computed it matches
    // Android's palette in place, the check skips, and OS 7's overlays are never registered.
    @Test fun androidsSchemeSkipsOverAndroidsPalette() =
        assertTrue(androidSkips(q25Posed, { q25Posed.getValue("${it}_500") }, androidComputed))

    // First start with the module: Android's palette in place, OS 7's shades in the scheme: the
    // check fails on the first shade, and the overlays, not in place, are registered.
    @Test fun firstStartRegistersBb10s() {
        assertFalse(androidSkips(q25Posed, bb10Scheme500, androidComputed))
        assertEquals(PaletteStep.REGISTER, paletteStep { q25Posed[it] ?: 0 })
    }

    // Every later start: OS 7's palette in place. The check passes the tonal shades but fails on the
    // dynamic colors recomputed from the wallpaper; the registration is skipped, as nothing changes.
    @Test fun laterStartsDoNotRegisterAgain() {
        val posed = SYSTEM_COLORS
        assertTrue(SCHEME_PALETTES.all { posed.getValue("${it}_500") == bb10Scheme500(it) })
        assertFalse(androidSkips(posed, bb10Scheme500, androidComputed))
        assertEquals(PaletteStep.ALREADY_IN_PLACE, paletteStep { posed.getValue(it) })
    }

    // After uninstall.sh disabled the overlays, or with one of them left Android's: registered again.
    @Test fun aPartlyBb10PaletteIsRegistered() =
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
