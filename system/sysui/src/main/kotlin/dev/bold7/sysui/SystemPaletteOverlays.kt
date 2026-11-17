package dev.bold7.sysui

import android.content.om.FabricatedOverlay
import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Method

internal const val THEME_CONTROLLER = "com.android.systemui.theme.ThemeOverlayController"
internal const val TRANSACTION_BUILDER = "android.content.om.OverlayManagerTransaction\$Builder"
internal const val OVERLAY_INTERNAL = "android.os.FabricatedOverlayInternal"
internal const val OVERLAY_ENTRY = "android.os.FabricatedOverlayInternalEntry"

/**
 * OS 7's colors in Material You's place, two hooks.
 *
 * After ThemeOverlayController.createOverlays(I)V, Android has computed its three fabricated
 * overlays from the wallpaper (accent, neutral, dynamic: each color as system_<name>_light and
 * system_<name>_dark, which the framework's public system_<name> points to), and registers nothing
 * yet. On LineageOS 24, reevaluateSystemTheme then always hands them to ThemeOverlayApplier, which
 * registers and enables them. The hook puts in their place three of the same names, built by the
 * controller's own newFabricatedOverlay, holding the colors Android's held (read from them,
 * paletteOverlays) plus the error palette, with SYSTEM_COLORS' values. A doubt (a color OS 7 has no
 * value for, an overlay missing) throws before anything is swapped: Android's palette, counted.
 *
 * Then, instead of OverlayManagerTransaction$Builder.registerFabricatedOverlay, for our three
 * overlays only and only when every color in place is already OS 7's (paletteStep): no
 * registration, so no new .frro, no new idmap and no relaunch of every activity at each start of
 * SystemUI. The transaction keeps its setEnabled of the same, already enabled, overlays. Its only
 * caller in SystemUI, ThemeOverlayApplier, ignores the builder it returns.
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
    // Hidden (framework.jar of the Q25, SystemPaletteTargets.kt); SystemUI is platform signed.
    val register: Method = Class.forName(TRANSACTION_BUILDER).getMethod("registerFabricatedOverlay", FabricatedOverlay::class.java)
    private val internal = FabricatedOverlay::class.java.getMethod("getInternal")
    private val entries = Class.forName(OVERLAY_INTERNAL).getField("entries")
    private val resourceName = Class.forName(OVERLAY_ENTRY).getField("resourceName")
    private val newOverlay = controller.getDeclaredMethod("newFabricatedOverlay", String::class.java)
    private val fields = PALETTE_OVERLAYS.associate { (field, _) -> field to controller.getField(field) }
    private val resources = controller.getField("mResources")

    // The framework's ids of the system_ colors, most of them private (system_<name>_light):
    // looked up by name once.
    private var colorIds: Map<String, Int>? = null

    /** Overlays built by [replace] whose colors are already in place: not to be registered again. */
    @Volatile private var inPlace: List<FabricatedOverlay> = emptyList()

    /** After Android's createOverlays: the same three overlays, OS 7's values. */
    fun replace(controller: Any) {
        val specs = paletteOverlays(fields.mapValues { (_, f) -> names(f.get(controller)) })
        val built = specs.map { spec ->
            (newOverlay.invoke(controller, spec.name) as FabricatedOverlay).apply {
                for (c in spec.colors) setResourceValue("android:color/system_$c", TypedValue.TYPE_INT_COLOR_ARGB8, SYSTEM_COLORS.getValue(c), null)
            }
        }
        // What Android's overlays would change: SystemUI's resources, with the overlays in place.
        val res = resources.get(controller) as Resources
        val ids = colorIds ?: SYSTEM_COLORS.keys.associateWith { n ->
            res.getIdentifier("system_$n", "color", "android").also { check(it != 0) { "no system_$n in the framework" } }
        }.also { colorIds = it }
        val step = paletteStep { res.getColor(ids.getValue(it), null) }
        specs.zip(built).forEach { (spec, overlay) -> fields.getValue(spec.field).set(controller, overlay) }
        inPlace = if (step == PaletteStep.ALREADY_IN_PLACE) built else emptyList()
        Log.i(TAG, "palette: OS 7's colors in accent, neutral and dynamic (${specs.sumOf { it.colors.size }} colors), " +
            if (step == PaletteStep.ALREADY_IN_PLACE) "already in place: not registered again" else "not in place: to register")
    }

    /** True for one of our overlays whose colors are already in place: Android's registration is skipped. */
    fun alreadyRegistered(overlay: Any?): Boolean = inPlace.any { it === overlay }

    /** The resources an overlay holds, android:color/system_… */
    private fun names(overlay: Any?): List<String> =
        if (overlay == null) emptyList() else (entries.get(internal.invoke(overlay)) as List<*>).map { resourceName.get(it) as String }
}
