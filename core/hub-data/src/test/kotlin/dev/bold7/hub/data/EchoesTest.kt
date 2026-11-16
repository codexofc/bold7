package dev.bold7.hub.data

import kotlin.test.Test
import kotlin.test.assertEquals

private const val MESSAGES = "com.google.android.apps.messaging"
private const val DIALER = "com.google.android.dialer"
private const val MINUTE = 60_000L

class EchoesTest {
    private val t = 1_700_000_000_000L
    private fun sms(time: Long, body: String, sender: String = "Alice", kind: ItemKind = ItemKind.SMS) =
        HubItem("sms", "sms:$time", kind, time, sender, address = "+33600000000", preview = body, conversationId = "1")
    private fun call(time: Long, sender: String = "Bob") = HubItem("calls", "$time", ItemKind.MISSED_CALL, time, sender, address = "+33611111111")
    private fun notification(pkg: String, time: Long, text: String, sender: String = "Alice") =
        HubItem("notifications", "0|$pkg|1|null|1@$time", ItemKind.NOTIFICATION, time, sender, preview = text, folder = pkg, conversationId = "0|$pkg|1|null|1")

    private fun shown(vararg items: HubItem) = items.toList().withoutEchoes(smsApp = MESSAGES, dialer = DIALER)

    @Test fun theSmsAppsNotificationOfAnSmsTheHubAlreadyHasIsLeftOut() {
        val text = sms(t, "See you at 6 pm?")
        assertEquals(listOf(text), shown(text, notification(MESSAGES, t + 2_000, "See you at 6 pm?")))
    }

    @Test fun theNotificationCanComeBeforeTheProviderWritesTheSms() {
        val text = sms(t, "ok")
        assertEquals(listOf(text), shown(text, notification(MESSAGES, t - 2_000, "ok")))
    }

    @Test fun theWindowIncludesItsBound() {
        val text = sms(t, "ok")
        assertEquals(listOf(text), shown(text, notification(MESSAGES, t + ECHO_WINDOW_MS, "ok")))
        val after = notification(MESSAGES, t + ECHO_WINDOW_MS + 1, "ok")
        assertEquals(listOf(text, after), shown(text, after))
    }

    @Test fun spacesAroundTheTextDoNotMakeAnotherMessage() {
        val text = sms(t, "ok \n")
        assertEquals(listOf(text), shown(text, notification(MESSAGES, t, " ok")))
    }

    @Test fun anMmsTextIsAnEchoToo() {
        val mms = sms(t, "Photo du chantier", kind = ItemKind.MMS)
        assertEquals(listOf(mms), shown(mms, notification(MESSAGES, t, "Photo du chantier")))
    }

    @Test fun onlyANotificationIsAnEcho() {
        // An item of another kind filed under the app's name is never left out.
        val invitation = HubItem("calendar", "9", ItemKind.INVITATION, t, "Alice", preview = "ok", folder = MESSAGES)
        assertEquals(listOf(sms(t, "ok"), invitation), shown(sms(t, "ok"), invitation))
    }

    @Test fun theSameTextFromSomeoneElseIsAnotherMessage() {
        // Bob's RCS "ok" a minute after Alice's SMS "ok": only its notification carries it.
        val rcs = notification(MESSAGES, t + MINUTE, "ok", sender = "Bob")
        assertEquals(listOf(sms(t, "ok"), rcs), shown(sms(t, "ok"), rcs))
    }

    @Test fun aNumberWrittenWithSpacesIsTheSameSender() {
        // No contact: the SMS has the raw number, the notification a formatted one.
        val text = sms(t, "ok", sender = "+33612345678")
        assertEquals(listOf(text), shown(text, notification(MESSAGES, t, "ok", sender = "+33 6 12 34 56 78")))
    }

    @Test fun aNationalNumberIsTheSameSenderAsItsInternationalForm() {
        // Seen on the device: the provider writes "+33700000093", Google Messages titles "07 00 00 00 93".
        val text = sms(t, "Yyy", sender = "+33700000093")
        assertEquals(listOf(text), shown(text, notification(MESSAGES, t + 1_000, "Yyy", sender = "07 00 00 00 93")))
    }

    @Test fun twoNumbersEndingDifferentlyAreTwoSenders() {
        val text = sms(t, "ok", sender = "+33700000093")
        val other = notification(MESSAGES, t, "ok", sender = "07 00 00 00 94")
        assertEquals(listOf(text, other), shown(text, other))
    }

    @Test fun aShortCodeMustMatchWhole() {
        val text = sms(t, "Code 1234", sender = "36179")
        val other = notification(MESSAGES, t, "Code 1234", sender = "6179")
        assertEquals(listOf(text, other), shown(text, other))
    }

    @Test fun aMessageOnlyTheNotificationCarriesStays() {
        // RCS chats are not in content://sms: their notification is all the Hub has of them.
        val chat = notification(MESSAGES, t + 2_000, "Sent by RCS")
        assertEquals(listOf(sms(t, "Something else"), chat), shown(sms(t, "Something else"), chat))
    }

    @Test fun aNotificationPostedAgainLaterIsStillAnEcho() {
        // Seen on the device: Google Messages restarted by tools/install.sh
        // posts its notification again hours later, same Notification.when (the key's "@" part),
        // new postTime (the item's time). Left in, it opened Google Messages instead of the thread.
        val text = sms(t, "ok")
        val again = notification(MESSAGES, t + 1_000, "ok").copy(timestamp = t + 8 * 60 * MINUTE)
        assertEquals(listOf(text), shown(text, again))
    }

    @Test fun theSameTextLongBeforeIsAnotherMessage() {
        val again = notification(MESSAGES, t + 6 * MINUTE, "ok")
        assertEquals(listOf(sms(t, "ok"), again), shown(sms(t, "ok"), again))
        val before = notification(MESSAGES, t - 6 * MINUTE, "ok")
        assertEquals(listOf(sms(t, "ok"), before), shown(sms(t, "ok"), before))
    }

    @Test fun anotherAppSayingTheSameStays() {
        val other = notification("org.example.chat", t, "ok")
        assertEquals(listOf(sms(t, "ok"), other), shown(sms(t, "ok"), other))
    }

    @Test fun theDialersNotificationOfAMissedCallTheHubHasIsLeftOut() {
        // The dialer titles it "Missed call" and names the caller in its text.
        val missed = call(t)
        assertEquals(listOf(missed), shown(missed, notification(DIALER, t + 40_000, "Bob", sender = "Appel manqué")))
        assertEquals(listOf(missed), shown(missed, notification(DIALER, t - 40_000, "Appel manqué", sender = "Bob")))
    }

    @Test fun aDialerNotificationOfSomeoneElseStays() {
        val voicemail = notification(DIALER, t + MINUTE, "Nouveau message vocal", sender = "Messagerie vocale")
        val other = notification(DIALER, t + MINUTE, "Carol", sender = "Appel manqué")
        assertEquals(listOf(call(t), voicemail, other), shown(call(t), voicemail, other))
    }

    @Test fun aDialerNotificationFarFromAnyMissedCallStays() {
        val late = notification(DIALER, t + 30 * MINUTE, "Bob", sender = "Appel manqué")
        assertEquals(listOf(call(t), late), shown(call(t), late))
    }

    @Test fun withoutKnownAppsNothingIsLeftOut() {
        val all = listOf(sms(t, "ok"), notification(MESSAGES, t, "ok"))
        assertEquals(all, all.withoutEchoes(smsApp = null, dialer = null))
    }
}
