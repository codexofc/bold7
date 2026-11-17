package dev.bold7.sysui

import dev.bold7.design.Palette
import dev.bold7.design.Theme
import dev.bold7.design.withOpacity
import kotlin.math.roundToInt

// OS 7's colors (the Precision theme of the 9900, core/design Palette and Theme) in place of the
// Material You palette SystemUI computes from the wallpaper (ThemeOverlayController.createOverlays).
// Mapping and sources: docs/BBOS7-VISUEL.md §19, "System palette"; the overlay switch is
// SystemPaletteOverlays.

/** The shades of a tonal palette, TonalPalette.SHADE_KEYS of LineageOS 24 (0 is white). */
internal val SHADES = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

/** The two schemes SystemUI writes each color for: system_<name>_light and system_<name>_dark. */
private val SCHEMES = listOf("light", "dark")

private val WHITE = withOpacity(0xFFFFFF, 1f)
private val BLACK = withOpacity(0x000000, 1f)

/**
 * The 13 shades of a tonal palette from its [anchors] (shade to color, 0 and 1000 included). A
 * shade between two anchors is interpolated in sRGB, by its position in the list of shades.
 */
internal fun ramp(anchors: Map<Int, Int>): Map<Int, Int> {
    val at = anchors.mapKeys { SHADES.indexOf(it.key) }.toSortedMap()
    return SHADES.withIndex().associate { (i, shade) ->
        val lo = at.headMap(i + 1).lastKey()
        val hi = at.tailMap(i).firstKey()
        shade to if (lo == hi) at.getValue(lo) else lerp(at.getValue(lo), at.getValue(hi), (i - lo).toFloat() / (hi - lo))
    }
}

private fun lerp(a: Int, b: Int, t: Float): Int = (0..3).fold(0) { acc, k ->
    val s = k * 8
    val ca = a ushr s and 0xFF
    acc or ((ca + ((b ushr s and 0xFF) - ca) * t).roundToInt() shl s)
}

/** [fg] drawn over the opaque [bg]: OS 7's translucent dividers as the solid color Material expects. */
private fun over(fg: Int, bg: Int): Int = lerp(bg, fg or (0xFF shl 24), (fg ushr 24) / 255f)

/** [rgb] at the opacity of Android's own value (0 to 255). */
private fun alpha(rgb: Int, a: Int): Int = (a shl 24) or (rgb and 0xFFFFFF)

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

// The red of new_overlay.png, its rim, and the pastel (D): the error palette, which SystemUI
// leaves to the framework's.
private val RED = ramp(mapOf(
    0 to WHITE, 100 to Palette.RedPastel, 400 to Palette.RedHighlight, 600 to Palette.RedBase, 800 to Palette.RedShade, 1000 to BLACK,
))

/** Material roles of one scheme, light ([t] = Bright) or dark, in OS 7's tokens. */
private fun roles(t: Theme, inverse: Theme, dark: Boolean): Map<String, Int> {
    val container = if (dark) Palette.PrimaryShade else Palette.PrimaryPastel
    val onContainer = if (dark) Palette.PrimaryPastel else Palette.PrimaryShade
    val tertiaryContainer = if (dark) Palette.BlueShade else Palette.BluePastel
    val error = if (dark) Palette.RedHighlight else Palette.RedBase
    return mapOf(
        "primary" to Palette.PrimaryHighlight,
        "on_primary" to Palette.TextOnPrimary,
        "primary_container" to container,
        "on_primary_container" to onContainer,
        "primary_dim" to Palette.PrimaryBase,
        "inverse_primary" to if (dark) Palette.PrimaryBase else Palette.AccentLight,
        "surface_tint" to Palette.PrimaryHighlight,
        "secondary" to Palette.PrimaryBase,
        "on_secondary" to Palette.TextOnPrimary,
        "secondary_container" to container,
        "on_secondary_container" to onContainer,
        "secondary_dim" to Palette.PrimaryShade,
        "tertiary" to Palette.BlueHighlight,
        "on_tertiary" to Palette.TextOnPrimary,
        "tertiary_container" to tertiaryContainer,
        "on_tertiary_container" to if (dark) Palette.BluePastel else Palette.BlueShade,
        "tertiary_dim" to Palette.BlueBase,
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
        // A snackbar: the other scheme's overlay.
        "inverse_surface" to inverse.overlay,
        "inverse_on_surface" to inverse.textPrimary,
        "outline" to t.textTertiary,
        "outline_variant" to over(t.divider, t.overlay),
        "error" to error,
        "on_error" to Palette.TextOnPrimary,
        "error_container" to if (dark) Palette.RedShade else Palette.RedPastel,
        "on_error_container" to if (dark) Palette.RedPastel else Palette.RedShade,
        "error_dim" to if (dark) Palette.RedBase else Palette.RedShade,
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
        "palette_key_color_error" to Palette.RedBase,
        "scrim" to BLACK,
        "shadow" to BLACK,
        "under_surface" to BLACK,
        "overview_background" to if (dark) t.surface else t.surfaceBase,
        "widget_background" to t.overlay,
        // Quick settings sit on the dark shade in both schemes, as Android's: a tile on is the
        // focus blue, off the flat button grey of the dark menus, white text.
        "shade_active" to Palette.PrimaryHighlight,
        "on_shade_active" to Palette.TextOnPrimary,
        "on_shade_active_variant" to Palette.TextOnPrimary,
        "shade_inactive" to Theme.Dark.divider,
        "on_shade_inactive" to Theme.Dark.textPrimary,
        "on_shade_inactive_variant" to Theme.Dark.textSecondary,
        "shade_disabled" to Theme.Dark.surfaceBase,
        // The shade's layers, at Android's opacity (0x80, 0x8A, 0x52 and 0x26 light, 0x26 and 0x1A
        // dark): OS 7's overlay, its background, then a white and a blue sheen. Without blur, the
        // overlay, opaque.
        "surface_effect_0" to alpha(t.overlay, 0x80),
        "surface_effect_0_fallback" to t.overlay,
        "surface_effect_1" to alpha(t.background, 0x8A),
        "surface_effect_2" to if (dark) alpha(Theme.Dark.textPrimary, 0x26) else alpha(WHITE, 0x52),
        "surface_effect_3" to if (dark) alpha(Palette.PrimaryPastel, 0x1A) else alpha(Palette.PrimaryHighlight, 0x26),
        // Android's clocks, weather and themed icons, which OS 7 does not have: its text and its blue.
        "clock_hour" to t.textPrimary,
        "clock_minute" to t.textPrimary,
        "clock_second" to Palette.PrimaryHighlight,
        "weather_temp" to Palette.PrimaryHighlight,
        "brand_a" to Palette.PrimaryHighlight,
        "brand_b" to Palette.AccentLight,
        "brand_c" to Palette.PrimaryBase,
        "brand_d" to Palette.BlueHighlight,
        "theme_app" to container,
        "on_theme_app" to onContainer,
        "theme_app_ring" to Palette.AccentLight,
        "theme_notif" to tertiaryContainer,
    )
}

/** Material's fixed roles: tones 90, 80, 10 and 30, shades 100, 200, 900 and 700 of the palette. */
private fun fixed(role: String, palette: Map<Int, Int>) = mapOf(
    "${role}_fixed" to palette.getValue(100),
    "${role}_fixed_dim" to palette.getValue(200),
    "on_${role}_fixed" to palette.getValue(900),
    "on_${role}_fixed_variant" to palette.getValue(700),
)

private fun tonal(name: String) = SHADES.flatMap { s -> SCHEMES.map { "${name}_${s}_$it" } }

/** OS 7's error palette, written with the accents: SystemUI leaves it to the framework's red. */
private val ERROR_COLORS = tonal("error")

/**
 * The OS 7 value of each system_<name> color, keyed by <name>: the tonal palettes
 * (accent1_600_light…), the roles (primary_light…), the fixed roles (primary_fixed_light…), each
 * in both schemes. One accent like OS 7 (accent2 = accent1), greys without tint, one palette for
 * both schemes.
 */
val SYSTEM_COLORS: Map<String, Int> = buildMap {
    val palettes = listOf("neutral1" to GREY, "neutral2" to GREY, "accent1" to BLUE, "accent2" to BLUE, "accent3" to INDIGO, "error" to RED)
    for ((name, palette) in palettes) for ((shade, color) in palette) for (s in SCHEMES) put("${name}_${shade}_$s", color)
    for ((role, color) in roles(Theme.Bright, Theme.Dark, dark = false)) put("${role}_light", color)
    for ((role, color) in roles(Theme.Dark, Theme.Bright, dark = true)) put("${role}_dark", color)
    for ((role, color) in fixed("primary", BLUE) + fixed("secondary", BLUE) + fixed("tertiary", INDIGO)) for (s in SCHEMES) put("${role}_$s", color)
}

/** One of the fabricated overlays SystemUI builds in createOverlays: its field in the controller, its name, its colors. */
internal data class PaletteOverlay(val field: String, val name: String, val colors: List<String>)

/** The controller's overlay fields and the names newFabricatedOverlay gives them. */
internal val PALETTE_OVERLAYS = listOf("mAccentOverlay" to "accent", "mNeutralOverlay" to "neutral", "mDynamicOverlay" to "dynamic")

private const val COLOR_PREFIX = "android:color/system_"

/**
 * The three overlays to write, from the resources Android's own hold ([written], each overlay
 * field to its resource names, android:color/system_…): the same colors, plus the error palette
 * with the accents. Throws IllegalStateException when one of Android's is missing or empty, or
 * holds a resource OS 7 has no value for: Android's overlays are then kept.
 */
internal fun paletteOverlays(written: Map<String, List<String>>): List<PaletteOverlay> {
    val overlays = PALETTE_OVERLAYS.map { (field, name) ->
        val resources = written[field]
        check(!resources.isNullOrEmpty()) { "SystemUI wrote no $name colors" }
        val colors = resources.map { check(it.startsWith(COLOR_PREFIX)) { "$it in $name is not a system color" }; it.removePrefix(COLOR_PREFIX) }
        PaletteOverlay(field, name, if (name == "accent") (colors + ERROR_COLORS).distinct() else colors)
    }
    val missing = overlays.flatMap { it.colors }.filter { it !in SYSTEM_COLORS }
    check(missing.isEmpty()) { "no OS 7 value for ${missing.joinToString()}" }
    return overlays
}

/** Whether the colors in place, [posed] reading system_<name>, are already every one of SYSTEM_COLORS. */
internal fun os7PaletteInPlace(posed: (String) -> Int): Boolean = SYSTEM_COLORS.all { (name, color) -> posed(name) == color }

internal enum class PaletteStep { REGISTER, ALREADY_IN_PLACE }

/**
 * What becomes of our overlays once ThemeOverlayApplier takes them: registered, unless every
 * color in place ([posed]) is already OS 7's, in which case registering them again would only make
 * a new .frro and relaunch every activity.
 */
internal fun paletteStep(posed: (String) -> Int): PaletteStep =
    if (os7PaletteInPlace(posed)) PaletteStep.ALREADY_IN_PLACE else PaletteStep.REGISTER
