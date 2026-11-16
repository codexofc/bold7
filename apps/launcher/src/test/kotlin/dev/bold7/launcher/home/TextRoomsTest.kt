package dev.bold7.launcher.home

import dev.bold7.design.HomeBanner
import dev.bold7.design.HomeDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The room the banner's and the tab's hand-drawn texts get: what `fit` cuts
// them to, so that none runs into its neighbour whatever the width.
class TextRoomsTest {
    private val widths = listOf(640, 720, 1080) // narrower than the 9900 leaves no room for the coverage (not a screen of Bold7)

    // BBOS7-VISUEL §1: the network's name is cut beyond 342 px, centred.
    @Test fun theNetworkNameGets342Px() {
        assertEquals(342f, BannerRoom.network(720))
        assertEquals(300f, BannerRoom.network(300))
    }

    // The date and the coverage end before the network's stretch, with 8 px of air.
    @Test fun theDateAndTheCoverageStopBeforeTheNetwork() {
        assertEquals(174f, BannerRoom.date(720))
        assertEquals(119f, BannerRoom.coverage(720))
        for (w in widths) {
            val networkLeft = (w - BannerRoom.network(w)) / 2
            assertTrue(HomeBanner.DATE_X + BannerRoom.date(w) <= networkLeft - 8)
            assertTrue(w - HomeBanner.COVERAGE_RIGHT - BannerRoom.coverage(w) >= (w + BannerRoom.network(w)) / 2 + 8)
        }
    }

    // The names of the neighbouring views are half a width apart: one never reaches the next.
    @Test fun aViewNameStaysBetweenItsNeighbours() {
        for (w in widths) assertTrue(BannerRoom.network(w) >= 0 && TabRoom.label(w) < w / 2f)
        assertEquals(340f, TabRoom.label(720))
    }

    // An app's name keeps 40 px off each edge, under the mask's darkening.
    @Test fun anAppNameKeepsOffTheEdges() {
        assertEquals(640f, TabRoom.name(720))
        assertEquals(0f, TabRoom.name(60))
        assertTrue(TabRoom.name(HomeDimens.COLUMN * HomeDimens.COLUMNS) <= 720f)
    }
}
