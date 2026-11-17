package dev.bold7.launcher.setup

import dev.bold7.ui.Dir

// The Setup Wizard shown at the first unlock after an install, pure: its pages, what each says,
// the trackpad's focus and what a key or a touch does. WizardActivity draws it, WizardDevice acts.
// Sources (docs/BBOS7-VISUEL.md §38): OS 7's Setup application (sim-375 to sim-389, C); the
// simulator opens no wizard at first boot (sim-390), so the order of the pages and their texts
// are Bold7's own (D).

/** The pages, in their order. */
enum class Page(val title: String) {
    WELCOME("Welcome"),
    CHANGES("What Changed"),
    TEXTS("Text Messages"),
    LOOK("Sound and Wallpaper"),
    DONE("All Set"),
}

/** Where a page stands, for the title bar: "2 of 5" (the guidelines' "identify the steps", B). */
fun progress(page: Page) = "${page.ordinal + 1} of ${Page.entries.size}"

/** What the home does at an unlock. */
enum class Opening { SHOW, MARK_DONE, NOTHING }

/**
 * Shown once: [done] is bold7_setup_done, set as soon as it opens and kept by updates. A phone that
 * already went through the earlier text screen ([textScreenAsked]) is set up: marked done unseen.
 */
fun opening(done: Boolean, textScreenAsked: Boolean): Opening = when {
    done -> Opening.NOTHING
    textScreenAsked -> Opening.MARK_DONE
    else -> Opening.SHOW
}

/** The 9900's pictures are missing when neither the mounted folder nor the APK (development) has them. */
fun assetsMissing(folder: Boolean, apkImages: Boolean) = !folder && !apkImages

/** What takes the focus and the clicks. */
sealed interface Control {
    data object Back : Control
    data object Next : Control
    data object KeepHome : Control
    data object KeepPhone : Control
    data object OpenTexts : Control
    data object Profile : Control
    data class Wallpaper(val index: Int) : Control
}

data class Focus(val row: Int, val col: Int)

data class State(
    val page: Page = Page.WELCOME,
    val focus: Focus = Focus(0, 0),
    val keepHome: Boolean = true,
    val keepPhone: Boolean = true,
    val messagesInstalled: Boolean = true,
    val consent: Consent = Consent.UNKNOWN,
    /** The consent was read back from Google's page: only then is "not yet" said. */
    val consentBack: Boolean = false,
    val profile: String = "Normal",
    val wallpapers: Int = 0,
    val chosenWallpaper: Int? = null,
)

sealed interface Event {
    data class Move(val dir: Dir) : Event
    data class Click(val control: Control) : Event
    data class ConsentRead(val consent: Consent, val back: Boolean) : Event
    data class WallpaperSet(val index: Int) : Event
    data class ProfileSet(val name: String) : Event
    data class Wallpapers(val count: Int) : Event
    data class Messages(val installed: Boolean) : Event

    /** To the next page, once the default apps' question is answered. */
    data object Advance : Event
}

/** What the activity does after a click: always something the user asked for. */
sealed interface Effect {
    data object Finish : Effect
    data object OpenTexts : Effect
    data object PickProfile : Effect
    data object ConfirmDefaultApps : Effect
    data class ConfirmWallpaper(val index: Int) : Effect
}

data class Result(val state: State, val effect: Effect? = null)

fun band(page: Page): List<Control> = if (page == Page.WELCOME) listOf(Control.Next) else listOf(Control.Back, Control.Next)

const val COLUMNS = 4

/** The focusable rows of the page, top to bottom, the band last. */
fun rows(s: State): List<List<Control>> = when (s.page) {
    Page.CHANGES -> listOf(listOf(Control.KeepHome), listOf(Control.KeepPhone))
    Page.TEXTS -> if (s.messagesInstalled) listOf(listOf(Control.OpenTexts)) else emptyList()
    Page.LOOK -> listOf(listOf(Control.Profile)) + (0 until s.wallpapers).map { Control.Wallpaper(it) }.chunked(COLUMNS)
    else -> emptyList()
} + listOf(band(s.page))

fun defaultFocus(s: State): Focus {
    val r = rows(s)
    return Focus(r.lastIndex, r.last().lastIndex)
}

fun focused(s: State): Control {
    val r = rows(s)
    val row = r[s.focus.row.coerceIn(r.indices)]
    return row[s.focus.col.coerceIn(row.indices)]
}

fun nextLabel(s: State): String = when {
    s.page == Page.DONE -> "Start"
    s.page == Page.TEXTS && s.messagesInstalled && s.consent != Consent.ON -> "Skip"
    else -> "Next"
}

fun consentLine(s: State): String? = when {
    s.consent == Consent.ON -> CONSENT_ON
    !s.consentBack -> null
    s.consent == Consent.OFF -> CONSENT_OFF
    else -> CONSENT_UNKNOWN
}

private fun to(s: State, page: Page) = s.copy(page = page).let { it.copy(focus = defaultFocus(it)) }

private fun move(s: State, dir: Dir): State {
    val r = rows(s)
    val (row, col) = s.focus
    val f = when (dir) {
        Dir.UP, Dir.DOWN -> {
            val to = (row + if (dir == Dir.UP) -1 else 1).coerceIn(r.indices)
            // Down into the band lands on Next, as each page starts.
            Focus(to, if (to == r.lastIndex && to != row) r[to].lastIndex else col.coerceAtMost(r[to].lastIndex))
        }
        Dir.LEFT -> Focus(row, (col - 1).coerceAtLeast(0))
        Dir.RIGHT -> Focus(row, (col + 1).coerceAtMost(r[row].lastIndex))
    }
    return s.copy(focus = f)
}

private fun focusOn(s: State, c: Control): State {
    val r = rows(s)
    for (i in r.indices) {
        val j = r[i].indexOf(c)
        if (j >= 0) return s.copy(focus = Focus(i, j))
    }
    return s
}

fun reduce(s: State, e: Event): Result = when (e) {
    is Event.Move -> Result(move(s, e.dir))
    is Event.ConsentRead -> Result(s.copy(consent = e.consent, consentBack = s.consentBack || e.back))
    is Event.WallpaperSet -> Result(s.copy(chosenWallpaper = e.index))
    is Event.ProfileSet -> Result(s.copy(profile = e.name))
    is Event.Wallpapers -> s.copy(wallpapers = e.count).let { Result(if (s.page == Page.LOOK) it.copy(focus = defaultFocus(it)) else it) }
    is Event.Messages -> s.copy(messagesInstalled = e.installed).let { Result(if (s.page == Page.TEXTS) it.copy(focus = defaultFocus(it)) else it) }
    Event.Advance -> Result(Page.entries.getOrNull(s.page.ordinal + 1)?.let { to(s, it) } ?: s)
    is Event.Click -> click(focusOn(s, e.control), e.control)
}

private fun click(s: State, c: Control): Result = when (c) {
    Control.Back -> Result(Page.entries.getOrNull(s.page.ordinal - 1)?.let { to(s, it) } ?: s)
    Control.Next -> when {
        s.page == Page.DONE -> Result(s, Effect.Finish)
        s.page == Page.CHANGES && !(s.keepHome && s.keepPhone) -> Result(s, Effect.ConfirmDefaultApps)
        else -> reduce(s, Event.Advance)
    }
    Control.KeepHome -> Result(s.copy(keepHome = !s.keepHome))
    Control.KeepPhone -> Result(s.copy(keepPhone = !s.keepPhone))
    Control.OpenTexts -> Result(s, Effect.OpenTexts)
    Control.Profile -> Result(s, Effect.PickProfile)
    is Control.Wallpaper -> Result(s, Effect.ConfirmWallpaper(c.index))
}

// The words. Short, plain, never Android's insides (WizardTest).

const val WELCOME_TITLE = "Bold7 is installed."
const val WELCOME_TEXT = "Everything is in place. Your phone, your apps and your data are safe and untouched."
const val WELCOME_NEXT = "One or two quick steps remain, about a minute."

val CHANGES_DONE = listOf(
    "The BlackBerry home screen",
    "Phone, Text Messages, Contacts, Calendar and Clock",
    "Typing, the BlackBerry keyboard",
    "BlackBerry sounds and ringtones",
    "The End key goes back to the home screen",
)
const val CHANGES_KEEP_HOME = "Keep as home screen"
const val CHANGES_KEEP_PHONE = "Keep as Phone app"
const val CHANGES_UNDO = "To undo everything: KernelSU > Modules > Bold7 > Remove, then restart: everything goes back as it was."
const val CHANGES_CONFIRM = "Choose your home screen and phone app in Android's Default apps. Bold7 stays installed. Open them now?"

const val TEXTS_WHY = "To show and send your texts, Bold7 needs Google Messages' permission."
const val TEXTS_HOW = "Click Open Google Messages, turn on \"Allow Bixby and text on Galaxy devices\", then press Back."
const val TEXTS_OPEN = "Open Google Messages"
const val TEXTS_SKIP = "If you skip, your texts open in Google Messages. You can come back here from Setup."
const val TEXTS_ABSENT = "Google Messages is not on this phone, so there is nothing to allow."
const val CONSENT_ON = "Done: Bold7 can show and send your texts."
const val CONSENT_OFF = "Not yet, try again."
const val CONSENT_UNKNOWN = "Could not check. If the switch is on, you can go on."

const val LOOK_PROFILE = "Sound Profile"
const val LOOK_PROFILE_NOTE = "Click to choose another profile"
const val LOOK_PROFILE_TEXT = "Your phone uses the BlackBerry's sounds and the Normal profile. Nothing to do."
const val LOOK_WALLPAPER = "Wallpaper"
const val LOOK_WALLPAPER_TEXT = "Keep yours, or pick one of the BlackBerry 9900's."

const val DONE_TEXT = "You're all set. Three keys to remember:"
val DONE_KEYS = listOf(
    "Menu key" to "Shows what you can do, on any screen.",
    "End key" to "Takes you back to the home screen.",
    "Just type" to "On the home screen, typing searches your phone.",
)
const val DONE_AGAIN = "Setup stays on the home screen if you need it again."

const val MISSING_TEXT = "Bold7 is installed, but the BlackBerry 9900 file it needs for its pictures and sounds was not found."
const val MISSING_HOW = "Put the official Bold 9900 OS Loader (9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe) or the BlackBerry 9900 Simulator 7.1.0.318 in your Download folder."
const val MISSING_THEN = "Then install Bold7 again in KernelSU and restart. Your phone works as usual until then."

val ALL_TEXTS = listOf(
    WELCOME_TITLE, WELCOME_TEXT, WELCOME_NEXT, CHANGES_KEEP_HOME, CHANGES_KEEP_PHONE, CHANGES_UNDO, CHANGES_CONFIRM,
    TEXTS_WHY, TEXTS_HOW, TEXTS_OPEN, TEXTS_SKIP, TEXTS_ABSENT, CONSENT_ON, CONSENT_OFF, CONSENT_UNKNOWN,
    LOOK_PROFILE, LOOK_PROFILE_NOTE, LOOK_PROFILE_TEXT, LOOK_WALLPAPER, LOOK_WALLPAPER_TEXT, DONE_TEXT, DONE_AGAIN,
    MISSING_TEXT, MISSING_HOW, MISSING_THEN,
) + CHANGES_DONE + DONE_KEYS.flatMap { listOf(it.first, it.second) }
