package dev.bold7.sysui

import android.content.om.FabricatedOverlay
import android.content.res.Resources
import android.util.Log
import android.util.Pair
import android.util.TypedValue
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Method

internal const val THEME_CONTROLLER = "com.android.systemui.theme.ThemeOverlayController"
internal const val TONAL_PALETTE = "com.android.systemui.monet.TonalPalette"
internal const val COLOR_SCHEME = "com.android.systemui.monet.ColorScheme"
internal const val DYNAMIC_COLORS = "com.android.systemui.theme.DynamicColors"
internal const val TRANSACTION_BUILDER = "android.content.om.OverlayManagerTransaction\$Builder"

/**
 * OS 7's colors in Material You's place, two hooks.
 *
 * After ThemeOverlayController.createOverlays(I)V, Android has computed its scheme (mColorScheme)
 * and its three fabricated overlays from the wallpaper (neutral, accent, dynamic), and registers
 * nothing yet. reevaluateSystemTheme then compares the colors in place (its mResources) with that
 * scheme: shade 500 of the five tonal palettes, then five dynamic colors; all equal, it logs
 * "Skipping overlay creation" and stops, otherwise ThemeOverlayApplier registers and enables the
 * overlays. The hook puts in place of the three overlays three of the same names, built by the
 * controller's own newFabricatedOverlay, holding the same resources (paletteOverlays) with
 * SYSTEM_COLORS' values, and gives mColorScheme's five palettes the same shades
 * (schemeShades): the scheme the controller holds is the one it applies, so its check compares
 * the colors in place with OS 7's. While Android's palette is in place, the check fails on the
 * first shade and the OS 7 overlays are registered, once. A doubt (other shades, a role
 * SYSTEM_COLORS does not know) throws before anything is swapped: Android's palette, counted.
 *
 * Once OS 7's palette is in place, the check still fails on the dynamic colors, which it
 * recomputes from the wallpaper. Then, instead of
 * OverlayManagerTransaction$Builder.registerFabricatedOverlay, for our three overlays only and only
 * when every one of the 160 colors in place is already OS 7's (paletteStep): no registration, so no
 * new .frro, no new idmap and no relaunch of every activity. The transaction keeps its setEnabled
 * of the same, already enabled, overlays: Android's path when it has nothing to create
 * (mNeedsOverlayCreation false). Its only caller in SystemUI, ThemeOverlayApplier, ignores the
 * builder it returns.
 */
internal fun installSystemPalette(
    loader: ClassLoader,
    fail: (String, Throwable?) -> Unit,
    after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean,
    instead: (String, () -> Method, (Chain) -> Boolean) -> Boolean,
) {
    val overlays = try {
        SystemPaletteOverlays(loader)
    } catch (e: ReflectiveOperationException) {
        fail("hook target missing: $THEME_CONTROLLER (palette), Android's kept", e)
        return
    }
    // First: without it, the palette still goes in, registered again at each start (counted).
    instead("$TRANSACTION_BUILDER.registerFabricatedOverlay(FabricatedOverlay)", { overlays.register }) { chain ->
        overlays.alreadyRegistered(chain.getArg(0))
    }
    after("$THEME_CONTROLLER.createOverlays(I)V", { overlays.createOverlays }) { chain, result ->
        result.also { overlays.replace(chain.thisObject!!) }
    }
}

private class SystemPaletteOverlays(loader: ClassLoader) {
    private val controller = loader.loadClass(THEME_CONTROLLER)
    val createOverlays: Method = controller.getDeclaredMethod("createOverlays", Int::class.javaPrimitiveType)
    // Hidden in API 34 (framework.jar of the Q25, SystemPaletteTargets.kt); SystemUI is platform signed.
    val register: Method = Class.forName(TRANSACTION_BUILDER).getMethod("registerFabricatedOverlay", FabricatedOverlay::class.java)
    private val newOverlay = controller.getDeclaredMethod("newFabricatedOverlay", String::class.java)
    private val fields = listOf("mNeutralOverlay", "mSecondaryOverlay", "mDynamicOverlay").associateWith(controller::getField)
    private val scheme = controller.getDeclaredField("mColorScheme").apply { isAccessible = true }
    private val schemePalettes = loader.loadClass(COLOR_SCHEME).let { cs -> SCHEME_PALETTES.associateWith(cs::getField) }
    private val allShades = loader.loadClass(TONAL_PALETTE).getField("allShades")
    private val resources = controller.getField("mResources")
    private val shadeKeys = loader.loadClass(TONAL_PALETTE).getField("SHADE_KEYS")
    private val dynamicColors = loader.loadClass(DYNAMIC_COLORS)
    private val roles = dynamicColors.getField("ALL_DYNAMIC_COLORS_MAPPED")
    private val fixedRoles = dynamicColors.getField("FIXED_COLORS_MAPPED")
    // The framework's ids of the system_ colors, public constants of API 34.
    private val colorIds by lazy { SYSTEM_COLORS.keys.associateWith { android.R.color::class.java.getField("system_$it").getInt(null) } }

    /** Overlays built by [replace] whose colors are already in place: not to be registered again. */
    @Volatile private var inPlace: List<FabricatedOverlay> = emptyList()

    /** After Android's createOverlays: the same three overlays, OS 7's values. */
    fun replace(controller: Any) {
        val specs = paletteOverlays(shadeKeys.get(null), names(roles.get(null)), names(fixedRoles.get(null)))
        val targets = specs.map { fields.getValue(it.field) }
        val built = specs.map { spec ->
            (newOverlay.invoke(controller, spec.name) as FabricatedOverlay).apply {
                for (c in spec.colors) setResourceValue("android:color/system_$c", TypedValue.TYPE_INT_COLOR_ARGB8, SYSTEM_COLORS.getValue(c), null)
            }
        }
        // What Android's own check reads: SystemUI's resources, with the overlays in place.
        val res = resources.get(controller) as Resources
        val step = paletteStep { res.getColor(colorIds.getValue(it), null) }
        @Suppress("UNCHECKED_CAST")
        val palettes = scheme.get(controller).let { cs -> SCHEME_PALETTES.map { allShades.get(schemePalettes.getValue(it).get(cs)) as MutableList<Any?> } }
        targets.zip(built).forEach { (field, overlay) -> field.set(controller, overlay) }
        inPlace = if (step == PaletteStep.ALREADY_IN_PLACE) built else emptyList()
        // Last: should it fail, Android's check decides on its own scheme, as without the hook.
        SCHEME_PALETTES.zip(palettes).forEach { (name, shades) -> schemeShades(name).forEachIndexed { i, c -> shades[i] = c } }
        Log.i(TAG, "palette: OS 7's colors in neutral, accent and dynamic (${specs.sumOf { it.colors.size }} colors), " +
            if (step == PaletteStep.ALREADY_IN_PLACE) "already in place: not registered again" else "not in place: to register")
    }

    /** True for one of our overlays whose colors are already in place: Android's registration is skipped. */
    fun alreadyRegistered(overlay: Any?): Boolean = inPlace.any { it === overlay }

    private fun names(pairs: Any?) = (pairs as List<*>).map { (it as Pair<*, *>).first as String }
}
