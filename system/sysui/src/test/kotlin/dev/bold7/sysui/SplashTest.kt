package dev.bold7.sysui

import android.window.StartingWindowInfo
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SplashTest {
    // The 9900 opens an app without a splash: none of Android's.
    @Test fun everySplashBecomesNone() {
        for (type in listOf(STARTING_SPLASH, STARTING_SOLID_SPLASH, STARTING_LEGACY_SPLASH)) {
            assertEquals(STARTING_NONE, startingWindowType(type), "type $type")
        }
    }

    // A recent app brought back shows its own last picture; the windowless splash has no window to
    // give way to; anything unknown stays Android's.
    @Test fun otherTypesStayAndroids() {
        for (type in listOf(STARTING_NONE, STARTING_SNAPSHOT, STARTING_WINDOWLESS, 6, -1)) {
            assertEquals(type, startingWindowType(type), "type $type")
        }
    }

    @Test fun algorithmIsInTheQ25SystemUi() {
        ZipFile("../../stubs/systemui.jar").use { jar -> assertNotNull(jar.getEntry(STARTING_WINDOW_ALGORITHM.replace('.', '/') + ".class")) }
    }

    // The constants are the framework's (StartingWindowInfo, hidden from the SDK), inlined from
    // the Q25's framework.jar when this compiles.
    @Test fun typesAreTheFrameworks() {
        assertEquals(StartingWindowInfo.STARTING_WINDOW_TYPE_NONE, STARTING_NONE)
        assertEquals(StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN, STARTING_SPLASH)
        assertEquals(StartingWindowInfo.STARTING_WINDOW_TYPE_SNAPSHOT, STARTING_SNAPSHOT)
        assertEquals(StartingWindowInfo.STARTING_WINDOW_TYPE_SOLID_COLOR_SPLASH_SCREEN, STARTING_SOLID_SPLASH)
        assertEquals(StartingWindowInfo.STARTING_WINDOW_TYPE_LEGACY_SPLASH_SCREEN, STARTING_LEGACY_SPLASH)
        assertEquals(StartingWindowInfo.STARTING_WINDOW_TYPE_WINDOWLESS, STARTING_WINDOWLESS)
    }
}
