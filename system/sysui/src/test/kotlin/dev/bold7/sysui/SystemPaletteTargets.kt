package dev.bold7.sysui

import android.content.om.FabricatedOverlay
import android.content.om.OverlayManagerTransaction
import android.content.res.Resources
import android.os.FabricatedOverlayInternalEntry
import com.android.systemui.theme.ThemeOverlayController

// What SystemPaletteOverlays reaches by name, compiled against LineageOS 24's SystemUI and
// framework (stubs/), like HookTargets.kt. Never run, the stubs have no code. TargetTableTest
// checks the same members through hook-targets.tsv.
@Suppress("unused", "UNUSED_VARIABLE")
private fun systemPaletteTargets(controller: ThemeOverlayController, builder: OverlayManagerTransaction.Builder) {
    controller.createOverlays(0)
    val overlay: FabricatedOverlay = controller.newFabricatedOverlay("accent")
    controller.mAccentOverlay = overlay
    controller.mNeutralOverlay = overlay
    controller.mDynamicOverlay = overlay
    val resources: Resources = controller.mResources
    val same: OverlayManagerTransaction.Builder = builder.registerFabricatedOverlay(overlay)
    val entries: List<FabricatedOverlayInternalEntry> = overlay.getInternal().entries
    val name: String = entries[0].resourceName
}
