package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsTest {
    private val limits = MmsLimits(bytes = 307_200, width = 640, height = 480)

    @Test fun onlyMediaCardsAndAppointmentsGoInAText() {
        // Guide 7.1 p. 130: a vCard, an iCalendar or a media file.
        listOf("image/jpeg", "image/gif", "video/3gpp", "audio/amr", "audio/mpeg", "text/x-vcard", "text/vcard", "text/calendar", "TEXT/X-VCALENDAR")
            .forEach { assertTrue(attachable(it), it) }
        listOf("application/pdf", "application/zip", "text/plain", "", "image").forEach { assertFalse(attachable(it), it) }
    }

    @Test fun eachKindPicksItsTypes() {
        assertEquals(listOf("image/*"), pickerTypes(AttachKind.PICTURE))
        assertEquals(listOf("video/*"), pickerTypes(AttachKind.VIDEO))
        assertEquals(listOf("audio/*"), pickerTypes(AttachKind.AUDIO))
        assertTrue(pickerTypes(AttachKind.FILE).containsAll(listOf("image/*", "video/*", "audio/*", "text/x-vcard", "text/calendar")))
    }

    @Test fun aSmallPictureGoesAsItIs() {
        assertEquals(Fit.KEEP, fit("image/jpeg", 100_000, 640, 480, limits))
        // Turned, it still fits: the carrier's 640x480 is either way.
        assertEquals(Fit.KEEP, fit("image/jpeg", 100_000, 480, 640, limits))
    }

    @Test fun aPictureTooHeavyOrTooBigIsShrunk() {
        assertEquals(Fit.SHRINK, fit("image/jpeg", 2_000_000, 640, 480, limits))
        assertEquals(Fit.SHRINK, fit("image/png", 50_000, 4000, 3000, limits))
        // Unknown size: read whole, then shrunk if it must be.
        assertEquals(Fit.SHRINK, fit("image/jpeg", -1, 640, 480, limits))
    }

    @Test fun anythingElseTooHeavyIsRefused() {
        assertEquals(Fit.KEEP, fit("video/3gpp", 200_000, 0, 0, limits))
        assertEquals(Fit.TOO_LARGE, fit("video/mp4", 5_000_000, 0, 0, limits))
        assertEquals(Fit.TOO_LARGE, fit("audio/mpeg", limits.budget + 1L, 0, 0, limits))
        assertEquals(Fit.KEEP, fit("audio/mpeg", limits.budget.toLong(), 0, 0, limits))
    }

    @Test fun theBudgetLeavesRoomForTheHeaders() {
        assertEquals(307_200 - MMS_OVERHEAD, limits.budget)
    }

    @Test fun shrinkingStartsWithinTheCarrierSizeAndGoesDown() {
        val steps = shrinkSteps(4000, 3000, limits)
        assertEquals(640 to 480, steps.first())
        assertTrue(steps.zipWithNext().all { (a, b) -> b.first < a.first && b.second < a.second })
        assertTrue(steps.last().first >= MIN_SIDE || steps.last().second >= MIN_SIDE)
        // A portrait picture keeps its way up.
        assertEquals(480 to 640, shrinkSteps(3000, 4000, limits).first())
        // Already small: never enlarged.
        assertEquals(300 to 200, shrinkSteps(300, 200, limits).first())
    }

    @Test fun aNameIsMadeSafeForAPath() {
        assertEquals("IMG_0001.jpg", safeName("IMG_0001.jpg"))
        assertEquals("_.._etc_passwd", safeName("/../etc/passwd"))
        assertEquals("photo_d__t_.jpg", safeName("photo d'été.jpg"))
        assertEquals("attachment", safeName(""))
        assertEquals("attachment", safeName(".."))
        assertEquals(64, safeName("a".repeat(200) + ".jpg").length)
        assertTrue(safeName("a".repeat(200) + ".jpg").endsWith(".jpg"))
    }

    @Test fun theAttachmentsIcon() {
        assertEquals(AttachIcon.PICTURE, iconOf("image/png"))
        assertEquals(AttachIcon.VIDEO, iconOf("video/mp4"))
        assertEquals(AttachIcon.MUSIC, iconOf("audio/ogg"))
        assertEquals(AttachIcon.CONTACT, iconOf("text/x-vcard"))
        assertEquals(AttachIcon.CALENDAR, iconOf("text/calendar"))
        assertEquals(AttachIcon.UNKNOWN, iconOf("application/octet-stream"))
    }

    @Test fun aBubbleOpensItsFirstPictureElseItsFirstMedia() {
        val base = ThreadMessage("content://mms/7", 1, false, "", Delivery.RECEIVED)
        assertNull(base.opens)
        assertEquals(Opens.Picture("content://mms/part/1"), base.copy(images = listOf("content://mms/part/1"), media = listOf(MediaPart("content://mms/part/2", "video/mp4"))).opens)
        assertEquals(Opens.Media("content://mms/part/2", "video/mp4"), base.copy(media = listOf(MediaPart("content://mms/part/2", "video/mp4"))).opens)
    }
}
