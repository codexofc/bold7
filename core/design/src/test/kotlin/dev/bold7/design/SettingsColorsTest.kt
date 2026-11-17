package dev.bold7.design

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * system/overlays/settings' colors against the tokens they are made of, and the text
 * drawn on each surface: a color that drifts from its token, a Google hue that comes back, or a
 * pair that becomes unreadable fails here. Same method as FrameworkColorsTest.
 */
class SettingsColorsTest {
    private val res = File("../../system/overlays/settings/src/main/res")
    private val rx = Regex("""<color name="(\w+)">#([0-9a-fA-F]{8})</color>""")
    private fun read(dir: String) = rx.findAll(File(res, "$dir/colors.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }

    private val day = read("values")
    private val night = read("values-night")

    // The categories of Lineage's Settings (aapt2 dump resources), each with its disc and glyph.
    private val categories = listOf(
        "about", "accessibility", "accounts", "apps", "battery", "connected_device", "device", "display",
        "generic_icon", "hub_mode", "location", "modes", "network", "notification", "safety", "security", "sound",
        "storage", "supervision", "support", "system", "wallpaper",
    )
    private val homepage = categories.map { "homepage_${it}_background" }
    private val glyphs = (categories - "generic_icon").map { "homepage_${it}_foreground" }

    // SettingsLib's Google palette by index, folded onto OS 7's blue, red and greys (D).
    private val blue = mapOf("50" to Palette.BluePastel, "100" to Palette.PrimaryPastel, "300" to Palette.AccentLight, "400" to Palette.PrimaryHighlight, "600" to Palette.PrimaryBase)
    private val red = mapOf("50" to Palette.RedPastel, "100" to Palette.RedPastel, "300" to Palette.RedPastel, "400" to Palette.RedHighlight, "600" to Palette.RedBase)
    private val b = Theme.Bright
    private val d = Theme.Dark
    private val grey = mapOf(
        "50" to b.surface, "100" to b.surfaceBase, "200" to b.divider, "300" to b.divider, "400" to b.textTertiary,
        "500" to d.textTertiary, "600" to b.textSecondary, "700" to d.divider, "800" to d.surface, "900" to d.surfaceBase,
    )
    private val hues = mapOf(
        "blue" to blue, "cyan" to blue, "green" to blue, "purple" to blue,
        "red" to red, "orange" to red, "pink" to red, "yellow" to red, "grey" to grey,
    )

    // The shades MtkSettings declares (aapt2 dump resources): the table above over-lists them.
    private val declared = mapOf(
        "blue" to "50 100 300 400 600", "cyan" to "100 300 400 600", "green" to "50 100 400 600",
        "purple" to "100 300 400 600", "red" to "50 100 400 600", "orange" to "100 300 400 600",
        "pink" to "100 300 400 600", "yellow" to "50 100 400 600", "grey" to "50 100 200 300 400 500 600 700 800 900",
    ).mapValues { it.value.split(" ") }

    private val tokens: Map<String, Int> = buildMap {
        put("settingslib_surface_light", b.background)
        put("settingslib_surface_dark", d.surface)
        put("settingslib_text_color_primary_device_default", ListColors.Title)
        put("settingslib_text_color_secondary_device_default", ListColors.Subtitle)
        put("settingslib_text_color_preference_category_title", ListColors.GroupTitle)
        put("settingslib_dialog_colorError", Palette.RedBase)
        homepage.forEach { put(it, d.surface) }
        glyphs.forEach { put(it, b.background) }
        put("preference_fallback_accent_color", Palette.PrimaryHighlight)
        put("switch_thumb_normal_material_dark", d.textSecondary)
        put("switch_thumb_normal_material_light", b.background)
        put("switch_thumb_disabled_material_dark", d.textTertiary)
        put("switch_thumb_disabled_material_light", b.textTertiary)
        put("cardview_dark_background", d.surface)
        for ((hue, shades) in hues) for ((n, c) in shades) if (n in declared.getValue(hue)) put("settingslib_color_$hue$n", c)
    }

    private val nightTokens: Map<String, Int> = buildMap {
        put("settingslib_surface_dark", d.surface)
        put("settingslib_text_color_primary_device_default", d.textPrimary)
        put("settingslib_text_color_secondary_device_default", d.textSecondary)
        put("settingslib_text_color_preference_category_title", d.textSecondary)
        put("settingslib_dialog_colorError", Palette.RedHighlight)
    }

    @Test fun everyColorIsItsToken() {
        for ((name, token) in tokens) assertEquals(token, day[name], name)
        for ((name, token) in nightTokens) assertEquals(token, night[name], "$name (night)")
        // A name posed with no token, or a token with no name, is a color that was added or lost by hand.
        assertEquals(emptySet(), day.keys - tokens.keys)
        assertEquals(emptySet(), tokens.keys - day.keys)
        assertEquals(emptySet(), night.keys - nightTokens.keys)
    }

    @Test fun noGoogleHueRemains() {
        // Google's blue, red, green, orange and the teal fallback, as MtkSettings ships them.
        val gone = setOf(0x1A73E8, 0xD93025, 0x1E8E3E, 0xFF7E0F, 0xFFA727, 0x2196F3, 0x0F9D58, 0x008577, 0xF28B82, 0x424242)
        for ((name, c) in day + night) assertTrue((c and 0xFFFFFF) !in gone, "$name still ${"#%06x".format(c and 0xFFFFFF)}")
        // Every homepage disc is the same colour, in both themes.
        assertEquals(1, (homepage.map { day.getValue(it) } + night.filterKeys { it.startsWith("homepage_") }.values).toSet().size)
    }

    // [text, surface, minimum]: 4.5 for text; ListColors.Subtitle on white is OS 7's own and measures 4.07 (C).
    @Test fun textStaysReadable() {
        val pairs = listOf(
            Triple("title on a light row", day.getValue("settingslib_text_color_primary_device_default") to day.getValue("settingslib_surface_light"), 4.5),
            Triple("summary on a light row", day.getValue("settingslib_text_color_secondary_device_default") to day.getValue("settingslib_surface_light"), 4.0),
            Triple("group title on a light page", day.getValue("settingslib_text_color_preference_category_title") to b.background, 4.0),
            Triple("title on a dark row", night.getValue("settingslib_text_color_primary_device_default") to night.getValue("settingslib_surface_dark"), 4.5),
            Triple("summary on a dark row", night.getValue("settingslib_text_color_secondary_device_default") to night.getValue("settingslib_surface_dark"), 4.5),
            Triple("group title on a dark row", night.getValue("settingslib_text_color_preference_category_title") to night.getValue("settingslib_surface_dark"), 4.5),
            Triple("group title on a black page", night.getValue("settingslib_text_color_preference_category_title") to d.background, 4.5),
            Triple("dialog error on white", day.getValue("settingslib_dialog_colorError") to b.background, 4.5),
            Triple("dark dialog error on its surface", night.getValue("settingslib_dialog_colorError") to night.getValue("settingslib_surface_dark"), 3.0),
            Triple("glyph on a homepage disc", day.getValue("homepage_system_foreground") to day.getValue("homepage_system_background"), 4.5),
            Triple("blue 600 on white", day.getValue("settingslib_color_blue600") to b.background, 4.5),
            Triple("red 600 on white", day.getValue("settingslib_color_red600") to b.background, 4.5),
            Triple("grey 900 on grey 50", day.getValue("settingslib_color_grey900") to day.getValue("settingslib_color_grey50"), 4.5),
        )
        for ((label, p, min) in pairs) assertTrue(contrast(p.first, p.second) >= min, "$label: ${"%.2f".format(contrast(p.first, p.second))} < $min")
    }

    /**
     * The row and group layouts name no resource of MtkSettings: an overlay layout
     * cannot reach one it does not redefine (its ids are the overlay's, not the target's). Their
     * literal colors are the tokens, light and dark.
     */
    @Test
    fun layoutsUseOnlyFrameworkReferencesAndTokenColors() {
        val files = listOf("layout", "layout-night").flatMap { dir -> listOf("settingslib_preference", "preference_category_material").map { "$dir/$it.xml" } }
        for (f in files) {
            val text = File(res, f).readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            val refs = Regex("""[@?](?!android:)[A-Za-z]*/?[A-Za-z_]+/[A-Za-z_.0-9]+|[@?](?!android:)attr/\w+""").findAll(text).map { it.value }.toList()
            assertTrue(refs.isEmpty(), "$f names a resource that is not the framework's: $refs")
            assertTrue(!text.contains("app:"), "$f uses an attribute of MtkSettings")
        }
        fun colors(f: String) = Regex("""#([0-9A-Fa-f]{8})""").findAll(File(res, f).readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")).map { it.groupValues[1].toLong(16).toInt() }.toList()
        assertEquals(listOf(b.background, ListColors.Subtitle), colors("layout/settingslib_preference.xml"))
        assertEquals(listOf(d.surface, night.getValue("settingslib_text_color_secondary_device_default")), colors("layout-night/settingslib_preference.xml"))
        assertEquals(listOf(b.divider, ListColors.GroupTitle, ListColors.GroupTitle), colors("layout/preference_category_material.xml"))
        val nightTitle = night.getValue("settingslib_text_color_preference_category_title")
        assertEquals(listOf(d.divider, nightTitle, nightTitle), colors("layout-night/preference_category_material.xml"))
        for ((label, p, min) in listOf(
            Triple("summary on the light row", ListColors.Subtitle to b.background, 4.0),
            Triple("group title on the light strip", ListColors.GroupTitle to b.divider, 2.9),
            Triple("group title on the dark strip", nightTitle to d.divider, 3.0),
        )) assertTrue(contrast(p.first, p.second) >= min, "$label: ${"%.2f".format(contrast(p.first, p.second))} < $min")
    }

    private val settings = File(res, "values/settings.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    private fun style(name: String) = Regex("""<style name="${Regex.escape(name)}"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(settings)!!.groupValues[1]
    private fun px(name: String) = Regex("""<dimen name="$name">(\d+)px</dimen>""").find(settings)!!.groupValues[1].toInt()
    private fun textColor(style: String) = Regex("""android:textColor">([^<]+)<""").find(style)!!.groupValues[1]

    /**
     * The title of the bar must be readable wherever it is drawn (an earlier layout made it
     * white over a scrim that is not there at rest, and the home page's title inherits that style).
     * At rest the expanded title lies on the page's surface, light or dark: its colour is a token of
     * the page's own text, never white. Folded, the collapsed title lies on the scrim.
     */
    @Test fun barTitleIsReadableAtRestAndFolded() {
        val expanded = textColor(style("CollapsingToolbarTitle.Expanded"))
        assertEquals("@color/settingslib_text_color_primary_device_default", expanded)
        val pages = listOf("light" to (day to listOf(b.surface, b.background)), "dark" to (night to listOf(d.surface, d.background)))
        for ((theme, p) in pages) {
            val title = (if (theme == "dark") night else day).getValue("settingslib_text_color_primary_device_default")
            for (surface in p.second) assertTrue(contrast(title, surface) >= 4.5, "expanded title on the $theme page: ${"%.2f".format(contrast(title, surface))}")
        }
        val scrim = Regex("""<color name="settingslib_colorSurfaceHeader">#([0-9A-Fa-f]{8})<""").find(settings)!!.groupValues[1].toLong(16).toInt()
        val collapsed = textColor(style("CollapsingToolbarTitle.Collapsed")).removePrefix("#").toLong(16).toInt()
        assertTrue(contrast(collapsed, scrim) >= 7.0, "collapsed title on the scrim: ${"%.2f".format(contrast(collapsed, scrim))}")
        // No -night override of the scrim: it is the same black in both themes, which the white title needs.
        assertTrue(!File(res, "values-night/colors.xml").readText().contains("settingslib_colorSurfaceHeader"))
    }

    /**
     * The bar does not fold (OS 7's, sim-57): one pixel taller than its toolbar, so that its fold
     * range is 1 px and never 0, and the scrim always up (trigger over the bar's height), which keeps
     * the white collapsed title drawn at every offset.
     */
    @Test fun barIsFixedAndBlack() {
        val rest = px("settingslib_toolbar_layout_height")
        val trigger = px("settingslib_scrim_visible_height_trigger")
        val toolbar = px("mtrl_toolbar_default_height")
        assertEquals(toolbar + 1, rest, "the bar must be its toolbar and one pixel")
        // The fade threshold, (rest - trigger) / range, far under 0: the collapsed title at an alpha of at least 0.99.
        val start = (rest - trigger).toDouble()
        val threshold = start + (1 - start) * 0.5
        assertTrue(-threshold / (1 - threshold) >= 0.99, "the title would fade: trigger $trigger")
        // The toolbar the bar reads is Material's (mtrl_), and the qualified table agrees.
        assertEquals(toolbar, Regex("""<dimen name="mtrl_toolbar_default_height">(\d+)px""").find(File(res, "values-sw600dp/settings.xml").readText())!!.groupValues[1].toInt())
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
