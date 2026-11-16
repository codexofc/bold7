package com.android.systemui.theme

import com.android.systemui.monet.ColorScheme

// ThemeOverlayController.mColorScheme is protected: compiled from its own package, so that the
// compiler checks the field SystemPaletteOverlays reaches by name (dev.bold7.sysui). Never run.
@Suppress("unused")
private fun schemeTarget(controller: ThemeOverlayController): ColorScheme = controller.mColorScheme
