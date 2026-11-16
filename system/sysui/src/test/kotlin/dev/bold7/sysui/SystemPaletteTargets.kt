package dev.bold7.sysui

import android.content.om.FabricatedOverlay
import android.content.om.OverlayManagerTransaction
import android.content.res.Resources
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.TonalPalette
import com.android.systemui.theme.DynamicColors
import com.android.systemui.theme.ThemeOverlayController
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull

// What SystemPaletteOverlays reaches by name, compiled against the Q25's SystemUI
// (stubs/systemui.jar), like HookTargets.kt. Never run, the stubs have no code.
@Suppress("unused", "UNUSED_VARIABLE")
private fun systemPaletteTargets(controller: ThemeOverlayController, builder: OverlayManagerTransaction.Builder, scheme: ColorScheme) {
    controller.createOverlays(0)
    val overlay: FabricatedOverlay = controller.newFabricatedOverlay("neutral")
    controller.mNeutralOverlay = overlay
    controller.mSecondaryOverlay = overlay
    controller.mDynamicOverlay = overlay
    val resources: Resources = controller.mResources
    val same: OverlayManagerTransaction.Builder = builder.registerFabricatedOverlay(overlay)
    val palettes: List<TonalPalette> = listOf(scheme.accent1, scheme.accent2, scheme.accent3, scheme.neutral1, scheme.neutral2)
    val allShades: List<*> = palettes[0].allShades
    val shades: List<*> = TonalPalette.SHADE_KEYS
    val roles: List<*> = DynamicColors.ALL_DYNAMIC_COLORS_MAPPED
    val fixed: List<*> = DynamicColors.FIXED_COLORS_MAPPED
}

class SystemPaletteTargetsTest {
    @Test fun classesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(THEME_CONTROLLER, TONAL_PALETTE, DYNAMIC_COLORS, COLOR_SCHEME)) assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
    }

    @Test fun builderIsInTheQ25Framework() {
        ZipFile("../../stubs/framework.jar").use { assertNotNull(it.getEntry(TRANSACTION_BUILDER.replace('.', '/') + ".class")) }
    }
}
