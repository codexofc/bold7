package dev.bold7.sysui

import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The status message's box is core/ui's DialogLayout without button, checked against sim-42 there
// (DialogLayoutTest).
class ToastTest {
    @Test fun textToastOfAndroidIsDressed() =
        assertTrue(toastDressable(plugin = false, hasIcon = true, hasText = true, hasInAnimator = true, hasOutAnimator = true))

    // Anything Android did not build as its own text toast stays Android's: a plugin's toast, a
    // view without the icon or text, or one Android gave no animator (ours would then make ToastUI
    // take a path it does not take for it).
    @Test fun anythingElseStaysAndroids() {
        assertFalse(toastDressable(plugin = true, hasIcon = true, hasText = true, hasInAnimator = true, hasOutAnimator = true))
        assertFalse(toastDressable(plugin = false, hasIcon = false, hasText = true, hasInAnimator = true, hasOutAnimator = true))
        assertFalse(toastDressable(plugin = false, hasIcon = true, hasText = false, hasInAnimator = true, hasOutAnimator = true))
        assertFalse(toastDressable(plugin = false, hasIcon = true, hasText = true, hasInAnimator = false, hasOutAnimator = true))
        assertFalse(toastDressable(plugin = false, hasIcon = true, hasText = true, hasInAnimator = true, hasOutAnimator = false))
    }

    @Test fun toastClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(TOAST_FACTORY, SYSTEMUI_TOAST)) assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
    }
}
