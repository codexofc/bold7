package dev.bold7.launcher.search

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val zone = ZoneId.of("America/Toronto")
private const val NOW = 1_800_000_000_000L
private val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(NOW), zone)

class ShowcaseSearchTest {
    private fun real(): Nothing = error("real provider reached in the showcase")
    private fun find(category: Category, query: String) = findIn(true, category, query, NOW, zone, ::real)

    @Test fun everyCategoryAnswersFromTheShowcaseWithoutTheProviders() {
        Category.entries.forEach { find(it, "o") }
    }

    @Test fun contactsAreFoundByNameAndNumber() {
        assertEquals("Olivia Bennett", (find(Category.CONTACTS, "oliv").items.single() as Contact).name)
        assertEquals(1, find(Category.CONTACTS, "519-555-0105").total)
    }

    @Test fun callsTextsAndAppointmentsAreFound() {
        assertTrue(find(Category.CALLS, "olivia").items.all { (it as Call).name == "Olivia Bennett" })
        assertTrue(find(Category.TEXT_MESSAGES, "camera").total > 0)
        assertTrue(find(Category.CALENDAR, "design review").items.isNotEmpty())
        assertEquals(0, find(Category.PICTURES, "a").total)
    }

    @Test fun theProvidersServeWhenItIsOff() {
        var asked = false
        findIn(false, Category.CONTACTS, "a", NOW, zone) { asked = true; Found(emptyList(), 0) }
        assertTrue(asked)
    }
}
