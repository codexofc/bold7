package dev.bold7.launcher.search

import dev.bold7.ui.Dir
import java.text.Normalizer

// The universal search of OS 7, opened by typing on the home, as pure functions: no android.*,
// tested on the JVM. What OS 7 shows and where it comes from: docs/BBOS7-VISUEL.md, § 9. The words
// of the query start words of a result (sim-54 to sim-56 highlight word starts), case and accents
// aside.

/** [c] without its accent and case, one char for one: indices stay those of the text. */
private fun fold(c: Char): Char = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()

private fun fold(s: String) = String(CharArray(s.length) { fold(s[it]) })

/** The words of [text] as index ranges: runs of letters and digits. */
private fun words(text: String): List<IntRange> {
    val words = mutableListOf<IntRange>()
    var start = -1
    for (i in 0..text.length) {
        val inWord = i < text.length && text[i].isLetterOrDigit()
        if (inWord && start < 0) start = i
        if (!inWord && start >= 0) {
            words += start until i
            start = -1
        }
    }
    return words
}

/**
 * Where each word of [query] starts a word of [text], in the text's order, or null if one does
 * not. Each query word takes the first free word it starts, a greedy pass; a query
 * word that would fit only a word an earlier one took fails (« p pl » on « Pl P »).
 */
fun matchRanges(text: String, query: String): List<IntRange>? = matchRanges(Folded(text), query)

/** A title folded and cut into words once, for a list matched at every key (the apps). */
class Folded(val text: String) {
    internal val folded = fold(text)
    internal val words = words(text)
}

fun matchRanges(text: Folded, query: String): List<IntRange>? {
    val terms = words(query).map { fold(query.substring(it)) }
    if (terms.isEmpty()) return null
    val folded = text.folded
    val free = text.words.toMutableList()
    val found = mutableListOf<IntRange>()
    for (term in terms) {
        val word = free.firstOrNull { folded.startsWith(term, it.first) && term.length <= it.last - it.first + 1 } ?: return null
        free.remove(word)
        found += word.first until word.first + term.length
    }
    return found.sortedBy { it.first }
}

/**
 * A result: [item] (an App, a contact, a message...), its [title] with the [ranges] the
 * query matched, and a second line.
 */
data class Hit(
    val item: Any,
    val title: String,
    val ranges: List<IntRange>,
    val detail: String = "",
    val detailRanges: List<IntRange> = emptyList(),
    /** A category's tile: how many results it holds (« Options (35) », sim-54). */
    val count: Int? = null,
)

/** [items] whose title the query matches, in their order. */
fun <T : Any> hits(items: List<T>, query: String, title: (T) -> String): List<Hit> = hits(items, items.map { Folded(title(it)) }, query)

/**
 * The same, the titles [folded] beforehand, one for each item; an item whose title does not
 * match is found all the same by one of its [aliases] (the app's alternative names), nothing highlighted.
 */
fun <T : Any> hits(items: List<T>, folded: List<Folded>, query: String, aliases: (Int) -> List<Folded> = { emptyList() }): List<Hit> =
    items.indices.mapNotNull { i ->
        val ranges = matchRanges(folded[i], query) ?: aliases(i).firstNotNullOfOrNull { matchRanges(it, query) }?.let { emptyList() }
        ranges?.let { Hit(items[i], folded[i].text, it) }
    }

/** The row « Extended Search »: a request to the network, taken only when chosen. */
object WebSearch

/**
 * What Enter (or the trackpad's click) opens: the target of the focus; on the soft focus, the
 * focus not moved, never the web (null then).
 */
fun entered(page: Page, selected: Int, moved: Boolean): Hit? =
    page.targets.getOrNull(selected)?.takeIf { moved || it.item !== WebSearch }

/** The index of [was] among the targets of a page rebuilt by results come in, or the top. */
fun reselect(page: Page, was: Hit?): Int = page.targets.indexOf(was).coerceAtLeast(0)

/** A phone number typed, rather than a name: digits and dialling signs, three digits at least. */
fun phoneLike(s: String) = s.all { it.isDigit() || it in "+-*#() ./" } && s.count { it.isDigit() } >= 3


/**
 * The categories that Android can fill, in the order of BlackBerry 10's category bar (D: OS 7's
 * order is not documented). OS 7 has no Instant Actions (« call Bob »). [CALLS] is our own, with the contacts.
 */
enum class Category { APPS, CONTACTS, CALLS, DOCUMENTS, SETTINGS, MUSIC, VIDEOS, PICTURES, CALENDAR, TEXT_MESSAGES, WEB }

/** Results a source keeps per query; the rest is only counted, a filter shows these. */
const val MAX_RESULTS = 50

/** Rows a source reads before its own matching, to count and keep [MAX_RESULTS]. */
const val MAX_READ = 200

/**
 * The longest word of [query], for a provider's LIKE to narrow its rows before [matchRanges]
 * decides: LIKE knows neither accents nor word starts. Null for a query without words.
 */
fun narrowing(query: String): String? = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.maxByOrNull { it.length }

/**
 * The music's order: MediaProvider parses a sort with a strict grammar that refuses COLLATE
 * LOCALIZED ("Invalid token LOCALIZED" on the Q25); NOCASE passes.
 */
const val MUSIC_ORDER = "title COLLATE NOCASE"

/**
 * A provider's error [message], to log only if it holds none of the words of [query] (a name,
 * digits of a number, typed into a URI or echoed back), cut to 200 characters; null otherwise.
 */
fun loggable(message: String?, query: String): String? {
    if (message == null) return null
    val folded = fold(message)
    val words = Regex("[\\p{L}\\p{N}]{2,}").findAll(query).map { fold(it.value) }
    return message.take(200).takeIf { words.none { it in folded } }
}

/** [word] inside a LIKE pattern, its wildcards escaped by a backslash (ESCAPE '\\'). */
fun like(word: String): String = "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** A number found by digits typed anywhere in it, 3 digits at least (not specified). */
fun numberMatches(number: String, query: String): Boolean {
    val digits = query.filter { it.isDigit() }
    return phoneLike(query) && digits.length >= 3 && digits in number.filter { it.isDigit() }
}

/**
 * The events of the agenda, one per event: those to come first, soonest first, then those
 * past, latest first ([now], [begin] and [id] of each).
 */
fun <T> upcomingFirst(items: List<T>, now: Long, begin: (T) -> Long, id: (T) -> Any): List<T> {
    val (next, past) = items.partition { begin(it) >= now }
    return (next.sortedBy(begin) + past.sortedByDescending(begin)).distinctBy(id)
}

/**
 * BlackBerry 10's keywords of its own apps, by the Bold7 icon of the app that stands for it
 * (core/icons, bbos7_icons.tsv): /usr/lib/search/plugins/resource/<locale>/search.mo, English
 * and French. Which app each list belongs to is not in the firmware: matched by hand, from their words.
 */
val APP_KEYWORDS: Map<String, List<String>> = mapOf(
    "bbos7_contacts" to listOf("Address Book", "Address List", "Carnet d'adresses", "Liste d'adresses"),
    "bbos7_calendar" to listOf("Agenda", "Datebook", "Date book", "Diary", "Schedule", "Journal", "Planification"),
    "bbos7_app_world" to listOf("Apps", "Applications", "Games", "Applis", "Jeux"),
    "bbos7_bbm" to listOf("BlackBerry Messenger", "IM", "Chat", "Video Chat", "MI", "chat vidéo"),
    "bbos7_maps" to listOf("Directions", "Navigation", "Local Search", "My Places", "POI", "My Location", "Address", "Itinéraires", "recherche locale", "Mes lieux", "Mon emplacement", "adresse"),
    "bbos7_text_messages" to listOf("Instant Message", "Messaging", "Text Message", "messagerie instantanée", "messagerie", "message texte"),
    "bbos7_browser" to listOf("Internet", "WWW"),
    "bbos7_memopad" to listOf("Notes", "Notepad", "Note pad", "Notebook", "Memo pad", "Memorandum", "Tasks", "Todo", "To do list", "Errands", "Bloc-note", "Blocs-notes", "Mémo", "Tâches", "Liste de tâches", "Courses"),
    "bbos7_options" to listOf("Options", "device settings", "general settings", "configure", "configuration", "paramètres du terminal", "paramètres généraux", "configurer"),
    "bbos7_pictures" to listOf("Photos", "Snapshots", "Snap shots", "Copies d'écran"),
    "bbos7_music" to listOf("Songs", "Tunes", "Morceaux", "Sonneries", "Music", "Musique"),
    "bbos7_clock" to listOf("Watch", "Alarm", "Timer", "Stopwatch", "World Clock", "Montre", "alarme", "minuterie", "chronomètre", "horloge mondiale"),
    "bbos7_videos" to listOf("recordings", "video tape", "videotape", "film", "enregistrements", "bande vidéo"),
    "bbos7_camera" to listOf("video camera", "caméra vidéo"),
    "bbos7_messages" to listOf("email", "message", "e-mail"),
)

/**
 * What finds an app besides its name, nothing highlighted: the keywords of its icon, and the
 * name Android gives it when bbos7_apps.tsv renamed it (« chrome » finds Browser).
 */
fun appAliases(tableIcon: String?, androidName: String?): List<String> = APP_KEYWORDS[tableIcon].orEmpty() + listOfNotNull(androidName)

/** A category's results; [total] counts those not loaded too (contacts). */
data class Section(val category: Category, val hits: List<Hit>, val total: Int = hits.size)

/** The number typed, offered by the row « Dial: » (sim-54): the Phone app, its number filled in. */
data class Dial(val number: String)

/** What the result list draws, top to bottom; each result and each tile is a target, by index. */
sealed interface Row {
    /** The name of the category whose results fill the list, and their number. */
    data class Header(val category: Category, val total: Int) : Row

    data class Item(val hit: Hit, val target: Int) : Row

    /** A row of tiles (apps, then categories), its targets [first] onwards. */
    data class Apps(val hits: List<Hit>, val first: Int) : Row
}

/** The rows, and the targets the trackpad walks through: each a [Hit]. */
data class Page(val rows: List<Row>, val targets: List<Hit>)

private val Row.targets: IntRange
    get() = when (this) {
        is Row.Header -> IntRange.EMPTY
        is Row.Item -> target..target
        is Row.Apps -> first until first + hits.size
    }

/**
 * The target after [selected] towards [dir]: up and down from row to row, by column between two
 * rows of tiles; left and right along a row of tiles only. OS 7's own walk is not documented (D).
 */
fun step(page: Page, selected: Int, dir: Dir): Int {
    val rows = page.rows.filter { it !is Row.Header }
    val i = rows.indexOfFirst { selected in it.targets }
    val row = rows.getOrNull(i) ?: return 0
    val range = row.targets
    fun column(to: Row) = to.targets.first + minOf(selected - range.first, to.targets.last - to.targets.first)
    return when (dir) {
        Dir.UP -> rows.getOrNull(i - 1)?.let { if (row is Row.Apps && it is Row.Apps) column(it) else it.targets.last } ?: selected
        Dir.DOWN -> rows.getOrNull(i + 1)?.let { if (row is Row.Apps && it is Row.Apps) column(it) else it.targets.first } ?: selected
        Dir.LEFT -> maxOf(range.first, selected - 1)
        Dir.RIGHT -> minOf(range.last, selected + 1)
    }
}

/** The columns of the tiles: `universal-search-default-columns-count = 6` (theme.xml, A). */
const val APP_COLUMNS = 6

/**
 * The page of OS 7's universal search (sim-54 to sim-56): « Dial: » when [dial] is a number, then
 * ONE grid of tiles, the apps found and one tile for each other category that found something,
 * with its number (« Options (35) »), then the web's row (Extended Search). [filter], the category
 * whose tile was taken: its results alone, as a list (D: OS 7's list is not captured).
 */
fun page(sections: List<Section>, filter: Category?, dial: String? = null): Page {
    val rows = mutableListOf<Row>()
    val targets = mutableListOf<Hit>()
    fun item(hit: Hit) {
        rows += Row.Item(hit, targets.size)
        targets += hit
    }
    val ordered = sections.sortedBy { it.category.ordinal }
    if (filter != null) {
        val s = ordered.firstOrNull { it.category == filter && it.hits.isNotEmpty() } ?: return Page(emptyList(), emptyList())
        rows += Row.Header(s.category, s.total)
        s.hits.forEach(::item)
        return Page(rows, targets)
    }
    if (dial != null) item(Hit(Dial(dial), dial, emptyList()))
    val apps = ordered.filter { it.category == Category.APPS }.flatMap { it.hits }
    val others = ordered.filter { it.category != Category.APPS && it.category != Category.WEB && it.hits.isNotEmpty() }
        .map { Hit(it.category, "", emptyList(), count = it.total) }
    for (chunk in (apps + others).chunked(APP_COLUMNS)) {
        rows += Row.Apps(chunk, targets.size)
        targets.addAll(chunk)
    }
    ordered.filter { it.category == Category.WEB }.flatMap { it.hits }.forEach(::item)
    return Page(rows, targets)
}
