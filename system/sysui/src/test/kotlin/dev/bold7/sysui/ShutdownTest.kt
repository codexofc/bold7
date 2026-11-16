package dev.bold7.sysui

import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShutdownTest {
    // Full Power Off and Restart of the power key's dialog (PowerManager reasons "userrequested" or
    // none): OS 7's screen, which keeps Android's words for a screen reader.
    @Test fun powerOffAndRestartGetOs7sScreen() {
        assertEquals("shutdown_progress", shutdownScreenText(isReboot = false, reason = null))
        assertEquals("shutdown_progress", shutdownScreenText(isReboot = false, reason = "userrequested"))
        assertEquals("reboot_to_reset_message", shutdownScreenText(isReboot = true, reason = null))
        assertEquals("reboot_to_reset_message", shutdownScreenText(isReboot = true, reason = "userrequested"))
        assertEquals("reboot_to_reset_message", shutdownScreenText(isReboot = true, reason = ""))
    }

    // A restart into recovery (factory reset, Android's update) tells what it is doing: Android's.
    @Test fun recoveryKeepsAndroidsScreen() {
        assertNull(shutdownScreenText(isReboot = true, reason = "recovery"))
        assertNull(shutdownScreenText(isReboot = true, reason = "recovery-update"))
        assertNull(shutdownScreenText(isReboot = true, reason = "recovery-update,quiescent"))
        assertNull(shutdownScreenText(isReboot = false, reason = "recovery"))
    }

    // The words are the framework's own strings on the Q25 (framework.jar, compiled in).
    @Test fun wordsAreTheFrameworks() {
        assertNotEquals(0, com.android.internal.R.string.shutdown_progress)
        assertNotEquals(0, com.android.internal.R.string.reboot_to_reset_message)
    }

    @Test fun globalActionsImplIsInTheQ25SystemUi() {
        ZipFile("../../stubs/systemui.jar").use { jar -> assertNotNull(jar.getEntry(GLOBAL_ACTIONS_IMPL.replace('.', '/') + ".class")) }
    }

    // osicon_hourglass.gif of the theme (A): 12 frames of 35x35, which the build ships unchanged.
    @Test fun busyImageIsTheThemesAnimation() {
        val file = File("../../assets-gen/sysui/res/drawable-nodpi/$SHUTDOWN_BUSY.gif")
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(file).use { input ->
            reader.input = input
            assertEquals(12, reader.getNumImages(true))
            assertEquals(35, reader.getWidth(0))
            assertEquals(35, reader.getHeight(0))
        }
    }
}
