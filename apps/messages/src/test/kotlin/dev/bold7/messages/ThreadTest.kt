package dev.bold7.messages

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadTest {
    private fun sms(id: Long, date: Long, type: Int, status: Int = -1, read: Boolean = true, body: String = "t$id") =
        SmsRecord(id, date, body, type, status, read, address = "513")

    private fun mms(id: Long, seconds: Long, box: Int, images: List<Long> = emptyList(), others: List<Pair<Long, String>> = emptyList(), read: Boolean = true, text: String = "") =
        MmsRecord(id, seconds, box, read, text, images, others, address = "513")

    @Test fun smsAndMmsMergeOldestFirstWithoutDrafts() {
        val list = threadMessages(
            listOf(sms(1, 3_000, 1), sms(2, 1_000, 2), sms(3, 9_000, 3)),
            listOf(mms(7, 2, 1)),
        )
        assertEquals(listOf("content://sms/2", "content://mms/7", "content://sms/1"), list.map { it.uri })
        assertEquals(listOf(true, false, false), list.map { it.out })
        assertEquals(2_000L, list[1].time)
    }

    @Test fun smsDelivery() {
        assertEquals(Delivery.UNREAD, smsDelivery(type = 1, status = -1, read = false))
        assertEquals(Delivery.RECEIVED, smsDelivery(type = 1, status = -1, read = true))
        assertEquals(Delivery.SENT, smsDelivery(type = 2, status = -1, read = true))
        assertEquals(Delivery.SENT, smsDelivery(type = 2, status = 32, read = true))
        assertEquals(Delivery.DELIVERED, smsDelivery(type = 2, status = 0, read = true))
        assertEquals(Delivery.FAILED, smsDelivery(type = 2, status = 64, read = true))
        assertEquals(Delivery.SENDING, smsDelivery(type = 4, status = -1, read = true))
        assertEquals(Delivery.SENDING, smsDelivery(type = 6, status = -1, read = true))
        assertEquals(Delivery.FAILED, smsDelivery(type = 5, status = -1, read = true))
        assertEquals(null, smsDelivery(type = 3, status = -1, read = true))
    }

    @Test fun mmsDelivery() {
        assertEquals(Delivery.UNREAD, mmsDelivery(box = 1, read = false))
        assertEquals(Delivery.RECEIVED, mmsDelivery(box = 1, read = true))
        assertEquals(Delivery.SENT, mmsDelivery(box = 2, read = true))
        assertEquals(null, mmsDelivery(box = 3, read = true))
        assertEquals(Delivery.SENDING, mmsDelivery(box = 4, read = true))
        assertEquals(Delivery.FAILED, mmsDelivery(box = 5, read = true))
    }

    @Test fun anMmsKeepsItsImagesAndCountsTheOtherParts() {
        val m = threadMessages(emptyList(), listOf(mms(7, 2, 1, images = listOf(40, 41), others = listOf(42L to "video/3gpp"), text = "look"))).single()
        assertEquals(listOf("content://mms/part/40", "content://mms/part/41"), m.images)
        assertEquals(1, m.otherParts)
        assertEquals(listOf(MediaPart("content://mms/part/42", "video/3gpp")), m.media)
        assertEquals("look", m.text)
    }

    @Test fun bubbleTimeIsTheHourTodayAndTheDateBefore() {
        val zone = ZoneId.of("Europe/Paris")
        val now = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val earlier = ZonedDateTime.of(2026, 11, 13, 0, 33, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 11, 12, 23, 5, 0, 0, zone).toInstant().toEpochMilli()
        val f = InboxFormat(zone, Locale.UK, clock24 = true)
        assertEquals("00:33", bubbleTime(earlier, now, f))
        assertEquals("12 Nov 23:05", bubbleTime(yesterday, now, f))
        assertEquals("12:33 AM", bubbleTime(earlier, now, f.copy(locale = Locale.US, clock24 = false)))
    }
}
