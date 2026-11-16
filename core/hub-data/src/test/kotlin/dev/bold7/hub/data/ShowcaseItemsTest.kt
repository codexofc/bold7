package dev.bold7.hub.data

import dev.bold7.showcase.Showcase
import dev.bold7.showcase.ShowcaseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowcaseItemsTest {
    private val hub = showcaseHub(1_800_000_000_000L)

    @Test
    fun theCountersOfSystemUiAreTheHubsRule() {
        val rule = unreadByKind(hub.accounts, hub.items, smsApp = null, dialer = null).mapKeys { it.key.name }
        assertEquals(rule, Showcase.unread)
        assertEquals(mapOf("SMS" to 3, "EMAIL" to 2, "MISSED_CALL" to 1), Showcase.unread)
    }

    @Test
    fun everyItemIsAShowcaseOneInAnAccountOfTheShowcase() {
        val accounts = hub.accounts.map { it.id }.toSet()
        assertTrue(hub.items.isNotEmpty())
        assertTrue(hub.items.all { it.sourceKey.startsWith(SHOWCASE_KEY) && it.accountId in accounts })
    }

    @Test
    fun emoticonsAreShapedByTheCaller() {
        val shaped = showcaseHub(0L) { "<$it>" }.items.filter { it.kind == ItemKind.SMS }
        assertTrue(shaped.all { it.preview.startsWith("<") })
    }

    @Test
    fun theStorysTextArrivesFirstAndCountsAsTheHubWould() {
        for (event in ShowcaseEvent.entries) {
            val h = showcaseHub(1_800_000_000_000L, event = event)
            val rule = unreadByKind(h.accounts, h.items, smsApp = null, dialer = null).mapKeys { it.key.name }
            assertEquals(rule, Showcase.unread(event), "$event")
        }
        val newest = showcaseHub(1_800_000_000_000L, event = ShowcaseEvent.MESSAGE).items.maxBy { it.timestamp }
        assertEquals(Showcase.STORY_TEXT, newest.preview)
        assertEquals("Daniel Okafor", newest.sender)
    }
}
