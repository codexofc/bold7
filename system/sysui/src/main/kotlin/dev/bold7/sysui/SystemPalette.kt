package dev.bold7.sysui

import dev.bold7.design.Palette
import dev.bold7.design.Theme
import dev.bold7.design.withOpacity
import kotlin.math.roundToInt

// OS 7's colors (the Precision theme of the 9900, core/design Palette and Theme) in place of the
// Material You palette SystemUI computes from the wallpaper (ThemeOverlayController.createOverlays).
// Mapping and sources: docs/BBOS7-VISUEL.md §19, "System palette"; the overlay switch is
// SystemPaletteOverlays.

/** The shades SystemUI writes for each tonal palette (TonalPalette.SHADE_KEYS of the Q25). */
internal val SHADES = listOf(10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

private val WHITE = withOpacity(0xFFFFFF, 1f)
private val BLACK = withOpacity(0x000000, 1f)

/**
 * The 12 shades of a tonal palette from its [anchors] (shade to color, 0 and 1000 included; 0 is
 * not written, it only starts the ramp). A shade between two anchors is interpolated in sRGB, by
 * its position in the list of shades.
 */
internal fun ramp(anchors: Map<Int, Int>): Map<Int, Int> {
    val steps = listOf(0) + SHADES
    val at = anchors.mapKeys { steps.indexOf(it.key) }.toSortedMap()
    return SHADES.associateWith { shade ->
        val i = steps.indexOf(shade)
        val lo = at.headMap(i + 1).lastKey()
        val hi = at.tailMap(i).firstKey()
        if (lo == hi) at.getValue(lo) else lerp(at.getValue(lo), at.getValue(hi), (i - lo).toFloat() / (hi - lo))
    }
}

private fun lerp(a: Int, b: Int, t: Float): Int = (0..3).fold(0) { acc, k ->
    val s = k * 8
    val ca = a ushr s and 0xFF
    acc or ((ca + ((b ushr s and 0xFF) - ca) * t).roundToInt() shl s)
}

/** [fg] drawn over the opaque [bg]: OS 7's translucent dividers as the solid color Material expects. */
private fun over(fg: Int, bg: Int): Int = lerp(bg, fg or (0xFF shl 24), (fg ushr 24) / 255f)

// The palette's greys, each at the shade whose Material tone (L*) is nearest to its own.
private val GREY = ramp(mapOf(
    0 to WHITE, 10 to Theme.Bright.background, 50 to Theme.Bright.surface, 100 to Theme.Bright.surfaceBase,
    400 to Theme.Dark.textSecondary, 500 to Theme.Dark.textTertiary, 600 to Theme.Bright.textSecondary,
    800 to Theme.Dark.surface, 900 to Theme.Dark.surfaceBase, 1000 to Theme.Dark.background,
))

// OS 7's blue: the light theme's accent (shade 600, colorAccent of DeviceDefault.Light) is the
// focus blue #088EEF, the darker shades the button's #1766DB and the icon focus's #0753B4, the
// lighter ones the theme's light and inverted gradients, then the pastel.
private val BLUE = ramp(mapOf(
    0 to WHITE, 200 to Palette.PrimaryPastel, 400 to Palette.AccentLight, 500 to Palette.FocusOutline,
    600 to Palette.PrimaryHighlight, 700 to Palette.PrimaryBase, 800 to Palette.PrimaryShade, 1000 to BLACK,
))

private val INDIGO = ramp(mapOf(
    0 to WHITE, 200 to Palette.BluePastel, 600 to Palette.BlueHighlight, 700 to Palette.BlueBase, 800 to Palette.BlueShade, 1000 to BLACK,
))

/** Material roles of one scheme, light ([t] = Bright) or dark, in OS 7's tokens. */
private fun roles(t: Theme, inverse: Theme, dark: Boolean): Map<String, Int> {
    val container = if (dark) Palette.PrimaryShade else Palette.PrimaryPastel
    val onContainer = if (dark) Palette.PrimaryPastel else Palette.PrimaryShade
    return mapOf(
        "primary" to Palette.PrimaryHighlight,
        "on_primary" to Palette.TextOnPrimary,
        "primary_container" to container,
        "on_primary_container" to onContainer,
        "secondary" to Palette.PrimaryBase,
        "on_secondary" to Palette.TextOnPrimary,
        "secondary_container" to container,
        "on_secondary_container" to onContainer,
        "tertiary" to Palette.BlueHighlight,
        "on_tertiary" to Palette.TextOnPrimary,
        "tertiary_container" to if (dark) Palette.BlueShade else Palette.BluePastel,
        "on_tertiary_container" to if (dark) Palette.BluePastel else Palette.BlueShade,
        "background" to t.background,
        "on_background" to t.textPrimary,
        "surface" to t.background,
        "on_surface" to t.textPrimary,
        "surface_dim" to if (dark) t.background else t.surfaceBase,
        "surface_bright" to if (dark) t.surface else t.background,
        "surface_container_lowest" to t.background,
        "surface_container_low" to if (dark) t.surfaceBase else t.surface,
        // Dialogs, menus, sheets: OS 7's overlay.
        "surface_container" to t.overlay,
        "surface_container_high" to t.overlay,
        "surface_container_highest" to if (dark) t.surface else t.surfaceBase,
        "surface_variant" to if (dark) t.surface else t.surfaceBase,
        "on_surface_variant" to t.textSecondary,
        "outline" to t.textTertiary,
        "outline_variant" to over(t.divider, t.overlay),
        "error" to if (dark) Palette.RedHighlight else Palette.RedBase,
        "on_error" to Palette.TextOnPrimary,
        "error_container" to if (dark) Palette.RedShade else Palette.RedPastel,
        "on_error_container" to if (dark) Palette.RedPastel else Palette.RedShade,
        "control_activated" to Palette.PrimaryHighlight,
        "control_normal" to t.textSecondary,
        "control_highlight" to t.pressed,
        // Text drawn on the other scheme's surfaces.
        "text_primary_inverse" to inverse.textPrimary,
        "text_secondary_and_tertiary_inverse" to inverse.textSecondary,
        "text_primary_inverse_disable_only" to inverse.textPrimary,
        "text_secondary_and_tertiary_inverse_disabled" to inverse.textTertiary,
        "text_hint_inverse" to inverse.textSecondary,
        "palette_key_color_primary" to Palette.PrimaryHighlight,
        "palette_key_color_secondary" to Palette.PrimaryHighlight,
        "palette_key_color_tertiary" to Palette.BlueHighlight,
        "palette_key_color_neutral" to GREY.getValue(500),
        "palette_key_color_neutral_variant" to GREY.getValue(500),
    )
}

/** Material's fixed roles: tones 90, 80, 10 and 30, shades 100, 200, 900 and 700 of the palette. */
private fun fixed(role: String, palette: Map<Int, Int>) = mapOf(
    "${role}_fixed" to palette.getValue(100),
    "${role}_fixed_dim" to palette.getValue(200),
    "on_${role}_fixed" to palette.getValue(900),
    "on_${role}_fixed_variant" to palette.getValue(700),
)

/**
 * The OS 7 value of each system_<name> color SystemUI writes in its fabricated overlays, keyed by
 * <name>: the tonal palettes (accent1_600…), the light and dark roles (primary_light…), the fixed
 * roles (primary_fixed…). One accent like OS 7 (accent2 = accent1), greys without tint.
 */
val SYSTEM_COLORS: Map<String, Int> = buildMap {
    for ((name, palette) in listOf("neutral1" to GREY, "neutral2" to GREY, "accent1" to BLUE, "accent2" to BLUE, "accent3" to INDIGO)) {
        for ((shade, color) in palette) put("${name}_$shade", color)
    }
    for ((role, color) in roles(Theme.Bright, Theme.Dark, dark = false)) put("${role}_light", color)
    for ((role, color) in roles(Theme.Dark, Theme.Bright, dark = true)) put("${role}_dark", color)
    putAll(fixed("primary", BLUE))
    putAll(fixed("secondary", BLUE))
    putAll(fixed("tertiary", INDIGO))
}

/** One of the fabricated overlays SystemUI builds in createOverlays: its field in the controller, its name, its colors. */
internal data class PaletteOverlay(val field: String, val name: String, val colors: List<String>)

/**
 * The three overlays ThemeOverlayController.createOverlays writes, from SystemUI's own lists:
 * [shadeKeys] (TonalPalette.SHADE_KEYS), [roles] (DynamicColors.ALL_DYNAMIC_COLORS_MAPPED, each in
 * _dark then _light) and [fixedRoles] (FIXED_COLORS_MAPPED). Throws IllegalStateException when
 * they are not the ones SYSTEM_COLORS was written for: Android's overlays are then kept.
 */
internal fun paletteOverlays(shadeKeys: Any?, roles: List<String>, fixedRoles: List<String>): List<PaletteOverlay> {
    check(shadeKeys == SHADES) { "SystemUI's shades $shadeKeys are not $SHADES" }
    fun tonal(vararg palettes: String) = palettes.flatMap { p -> SHADES.map { "${p}_$it" } }
    val overlays = listOf(
        PaletteOverlay("mNeutralOverlay", "neutral", tonal("neutral1", "neutral2")),
        PaletteOverlay("mSecondaryOverlay", "accent", tonal("accent1", "accent2", "accent3")),
        PaletteOverlay("mDynamicOverlay", "dynamic", roles.flatMap { listOf("${it}_dark", "${it}_light") } + fixedRoles),
    )
    val missing = overlays.flatMap { it.colors }.filter { it !in SYSTEM_COLORS }
    check(missing.isEmpty()) { "no OS 7 value for ${missing.joinToString()}" }
    return overlays
}

/** Whether the colors in place, [posed] reading system_<name>, are already every one of SYSTEM_COLORS. */
internal fun bb10PaletteInPlace(posed: (String) -> Int): Boolean = SYSTEM_COLORS.all { (name, color) -> posed(name) == color }

/** The tonal palettes of SystemUI's ColorScheme, by field name. */
internal val SCHEME_PALETTES = listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")

/**
 * OS 7's shades of one of [SCHEME_PALETTES], in the order of TonalPalette.allShades (SHADES):
 * what ThemeOverlayController's scheme holds once the hook went through, so that its check
 * (getS500, index 6) compares the colors in place with OS 7's.
 */
internal fun schemeShades(palette: String): List<Int> = SHADES.map { SYSTEM_COLORS.getValue("${palette}_$it") }

internal enum class PaletteStep { REGISTER, ALREADY_IN_PLACE }

/**
 * What becomes of our overlays once Android's check sends them to ThemeOverlayApplier: registered,
 * unless every color in place ([posed]) is already OS 7's, in which case registering them again
 * would only make a new .frro and relaunch every activity.
 */
internal fun paletteStep(posed: (String) -> Int): PaletteStep =
    if (bb10PaletteInPlace(posed)) PaletteStep.ALREADY_IN_PLACE else PaletteStep.REGISTER
