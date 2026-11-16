package dev.bold7.showcase

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// The showcase: one made-up phone owner, Alex Carter, and the people around them, for the
// screenshots and videos of Bold7. Every number is in the 555-01xx range kept for fiction, every
// address in a domain kept for examples (RFC 2606); no real person, place or brand. Times are
// relative ("minutes ago", "days from today") so the data always looks recent. Pure: the apps map
// it to their own types and never read nor write anything real while it is shown.

/** A contact. [id] also names their avatar ([AVATAR_SCHEME]). */
data class Person(
    val id: Long,
    val first: String,
    val last: String,
    val mobile: String,
    val work: String? = null,
    val home: String? = null,
    val email: String? = null,
    val company: String = "",
    val jobTitle: String = "",
    val address: String? = null,
    val groups: List<String> = emptyList(),
) {
    val name: String get() = "$first $last"

    /** Their picture, as the apps pass a photo's URI around. */
    val photo: String get() = "$AVATAR_SCHEME$id"

    /** Every number of theirs, mobile first. */
    val numbers: List<String> get() = listOfNotNull(mobile, work, home)
}

/** What [Person.photo] starts with; the id follows. */
const val AVATAR_SCHEME = "showcase:avatar/"

/** One message of a thread: [from] the sender in a group (null: the only other member, or the owner when [out]). */
data class Message(val minutesAgo: Int, val out: Boolean, val text: String, val from: Long? = null, val read: Boolean = true)

enum class Channel { TEXT, EMAIL }

/**
 * A conversation: text messages with one person or a group, or one email. [id] is positive and
 * unique; [members] the other people (the owner left out); [attachments] an email's files.
 */
data class Thread(
    val id: Long,
    val channel: Channel,
    val members: List<Long>,
    val messages: List<Message>,
    val subject: String? = null,
    val attachments: Int = 0,
) {
    val last: Message get() = messages.last()
    val unread: Int get() = messages.count { !it.out && !it.read }
}

enum class CallType { RECEIVED, PLACED, MISSED }

/** A call of the log: [person] null for a number the owner does not know; [isNew] a missed call not yet seen. */
data class Call(val person: Long?, val number: String, val type: CallType, val minutesAgo: Int, val seconds: Int = 0, val isNew: Boolean = false)

/**
 * An appointment: on [day] days from today, or on the next [weekday] (today included) when set;
 * [weekdays] repeats it every working day; [start] null for an all-day one, [minutes] long.
 */
data class Event(
    val id: Long,
    val title: String,
    val day: Int = 0,
    val weekday: DayOfWeek? = null,
    val start: LocalTime? = null,
    val minutes: Int = 60,
    val location: String = "",
    val reminder: Boolean = false,
    val invitation: Boolean = false,
    val weekdays: Boolean = false,
    val days: Int = 1,
)

/** One day an [Event] happens on, at [start] (null: all day) for [minutes]. */
data class Occurrence(val event: Event, val date: LocalDate)

enum class TaskStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, WAITING, DEFERRED }

enum class TaskPriority { HIGH, NORMAL, LOW }

/** A task, due [dueDay] days from today at [dueTime] (none when [dueDay] is null). */
data class Task(
    val id: Long,
    val title: String,
    val status: TaskStatus = TaskStatus.NOT_STARTED,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val dueDay: Int? = null,
    val dueTime: LocalTime = LocalTime.of(17, 0),
    val notes: String = "",
    val remindBefore: Int? = null,
)

data class Memo(val id: Long, val title: String, val body: String, val minutesAgo: Int)

/** The alarm of the Clock: [weekdays] Monday to Friday, otherwise every day. */
data class Alarm(val time: LocalTime, val weekdays: Boolean, val on: Boolean = true)

object Showcase {
    const val OWNER = "Alex Carter"
    const val OWNER_NUMBER = "+1 519-555-0100"
    const val OWNER_EMAIL = "alex.carter@example.com"

    private const val STUDIO = "Northgate Studio"

    val people: List<Person> = listOf(
        Person(1, "Olivia", "Bennett", "+1 519-555-0101", work = "+1 519-555-0151", email = "olivia.bennett@example.com",
            company = STUDIO, jobTitle = "Project Lead", address = "48 King Street West, Waterloo", groups = listOf("Work")),
        Person(2, "Daniel", "Okafor", "+1 519-555-0102", email = "daniel.okafor@example.net", groups = listOf("Friends")),
        Person(3, "Mei", "Zhang", "+1 519-555-0103", work = "+1 519-555-0153", email = "mei.zhang@example.com",
            company = STUDIO, jobTitle = "Software Engineer", groups = listOf("Work")),
        Person(4, "Lucas", "Ferreira", "+1 519-555-0104", email = "lucas.ferreira@example.net", groups = listOf("Friends")),
        Person(5, "Grace", "Carter", "+1 519-555-0105", home = "+1 519-555-0175", email = "grace.carter@example.org",
            address = "12 Maple Crescent, Guelph", groups = listOf("Family")),
        Person(6, "Priya", "Raman", "+1 519-555-0106", work = "+1 519-555-0156", email = "priya.raman@example.com",
            company = STUDIO, jobTitle = "Designer", groups = listOf("Work")),
        Person(7, "Tom", "Gallagher", "+1 519-555-0107", email = "tom.gallagher@example.net", groups = listOf("Running Club")),
        Person(8, "Sofia", "Romano", "+1 519-555-0108", email = "sofia.romano@example.net", groups = listOf("Friends")),
        Person(9, "Hannah", "Schmidt", "+1 519-555-0109", home = "+1 519-555-0179", email = "hannah.schmidt@example.org"),
        Person(10, "James", "Whitaker", "+1 519-555-0110", work = "+1 519-555-0160", email = "james.whitaker@example.com",
            company = STUDIO, jobTitle = "Director", groups = listOf("Work")),
        Person(11, "Aisha", "Khan", "+1 519-555-0111", work = "+1 519-555-0161", email = "aisha.khan@example.org",
            company = "Hollis Property Group", jobTitle = "Development Manager"),
        Person(12, "Noah", "Bergström", "+1 519-555-0112", email = "noah.bergstrom@example.net", groups = listOf("Friends")),
        Person(13, "Elena", "Petrova", "+1 519-555-0113", work = "+1 519-555-0163", email = "reception@riverside-dental.example",
            company = "Riverside Dental", jobTitle = "Dentist", address = "210 Erb Street, Waterloo"),
        Person(14, "Marcus", "Reid", "+1 519-555-0114", email = "marcus.reid@example.net", company = "Reid Home Repairs", jobTitle = "Contractor"),
        Person(15, "Yuki", "Tanaka", "+1 519-555-0115", work = "+1 519-555-0165", email = "yuki.tanaka@example.com",
            company = STUDIO, jobTitle = "Engineering Manager", groups = listOf("Work")),
        Person(16, "Ben", "Carter", "+1 519-555-0116", email = "ben.carter@example.net", groups = listOf("Family")),
    )

    fun person(id: Long): Person = people.first { it.id == id }

    /** The person one of whose numbers is [number], or null. */
    fun byNumber(number: String): Person? = people.firstOrNull { number in it.numbers }

    private const val H = 60
    private const val DAY = 24 * H

    val threads: List<Thread> = listOf(
        Thread(1, Channel.TEXT, listOf(1), listOf(
            Message(2 * H + 10, false, "Hi Alex! Are we still on for the design review?"),
            Message(2 * H + 6, true, "Yes, see you in room 3. I'll bring the new floor plans."),
            Message(H + 40, false, "Perfect. Coffee is on me ~o)"),
            Message(6, false, "The client loved the new layout :) Can you send the final files today?", read = false),
        )),
        Thread(2, Channel.TEXT, listOf(2, 4, 8), listOf(
            Message(3 * H, false, "Weather looks perfect for Saturday!", from = 4),
            Message(2 * H + 55, false, "I'm in. I'll bring sandwiches :D", from = 8),
            Message(2 * H + 50, true, "Great, I'll drive. Pick-up at 7:30?"),
            Message(24, false, "7:30 works. Don't forget the camera ;)", from = 2, read = false),
            Message(18, false, "And the snacks! (y)", from = 4, read = false),
        )),
        Thread(3, Channel.TEXT, listOf(5), listOf(
            Message(DAY + 3 * H, false, "Sunday lunch at noon? Your brother is coming too."),
            Message(DAY + 2 * H, true, "Wouldn't miss it. I'll bring dessert :)"),
            Message(95, false, "Lovely! I found Grandma's lemon cake recipe, I'll send you a photo."),
            Message(90, true, "Yes please <3"),
        )),
        Thread(4, Channel.TEXT, listOf(3), listOf(
            Message(5 * H, false, "The build is green, the new version is ready for testing."),
            Message(4 * H + 50, true, "Brilliant, thank you Mei! I'll try it after lunch."),
            Message(4 * H + 45, false, "B-)"),
        )),
        Thread(5, Channel.TEXT, listOf(8), listOf(
            Message(DAY + 9 * H, true, "Happy birthday Sofia! Dinner on me later this week?"),
            Message(DAY + 8 * H, false, "Thank you!! Sounds perfect :)"),
            Message(DAY + 7 * H, true, "I booked a table for 7:30."),
            Message(DAY + 7 * H - 5, false, "You're the best <3"),
        )),
        Thread(6, Channel.TEXT, listOf(7), listOf(
            Message(7 * H, false, "Running club starts at 6:30 tonight, same place by the lake."),
            Message(6 * H + 40, true, "See you there. 10 km this time?"),
            Message(6 * H + 30, false, "Let's do it (y)"),
        )),
        Thread(7, Channel.TEXT, listOf(9), listOf(
            Message(2 * DAY + 4 * H, false, "Hi Alex, a parcel came for you. I'll keep it at my place."),
            Message(2 * DAY + 3 * H, true, "Thanks Hannah, I'll pick it up tonight :)"),
        )),
        Thread(8, Channel.TEXT, listOf(14), listOf(
            Message(4 * H + 20, false, "I can come by at 9 for the kitchen tap. Does that work?"),
            Message(4 * H + 10, true, "9 is great, thanks Marcus."),
        )),
        Thread(9, Channel.TEXT, listOf(2), listOf(
            Message(3 * DAY, false, "Found a new bike route along the river, about 40 km."),
            Message(3 * DAY - 20, true, "Send me the map, I'm in for next weekend!"),
        )),
        Thread(10, Channel.EMAIL, listOf(10), listOf(
            Message(34, false, "Hi Alex,\n\nAttached is the agenda for the Q4 planning meeting. Please add your project updates before we meet.\n\nThanks,\nJames", read = false),
        ), subject = "Q4 planning agenda", attachments = 1),
        Thread(11, Channel.EMAIL, listOf(11), listOf(
            Message(2 * H + 20, false, "Hi Alex,\n\n2 pm works well for us. I'll meet you at the main entrance with the site plans.\n\nBest regards,\nAisha"),
        ), subject = "Re: Site visit"),
        Thread(12, Channel.EMAIL, listOf(15), listOf(
            Message(5 * H, false, "Hello from Tokyo! Here are the photos from the workshop. The whole team says thank you for the great session.\n\nYuki", read = false),
        ), subject = "Photos from the Tokyo workshop", attachments = 4),
        Thread(13, Channel.EMAIL, listOf(6), listOf(
            Message(8 * H, false, "Hi Alex, the new icon set is ready for review. I tried two versions of the map icon, let me know which one you prefer.\n\nPriya"),
        ), subject = "New icon set for review", attachments = 2),
        Thread(14, Channel.EMAIL, listOf(13), listOf(
            Message(5 * H + 30, false, "This is a friendly reminder of your check-up tomorrow at 4:30 pm. Please call us if you need to change the time.\n\nRiverside Dental"),
        ), subject = "Appointment reminder"),
    )

    val calls: List<Call> = listOf(
        Call(5, person(5).mobile, CallType.MISSED, 75, isNew = true),
        Call(1, person(1).work!!, CallType.RECEIVED, 2 * H, seconds = 372),
        Call(2, person(2).mobile, CallType.PLACED, 3 * H + 20, seconds = 145),
        Call(14, person(14).mobile, CallType.MISSED, 4 * H + 30),
        Call(null, "+1 519-555-0187", CallType.RECEIVED, 6 * H, seconds = 48),
        Call(10, person(10).work!!, CallType.RECEIVED, DAY + 2 * H, seconds = 914),
        Call(5, person(5).home!!, CallType.PLACED, DAY + 6 * H, seconds = 1260),
        Call(13, person(13).work!!, CallType.PLACED, 2 * DAY + H, seconds = 95),
        Call(16, person(16).mobile, CallType.RECEIVED, 2 * DAY + 5 * H, seconds = 640),
        Call(11, person(11).work!!, CallType.PLACED, 3 * DAY + 2 * H, seconds = 510),
        Call(8, person(8).mobile, CallType.MISSED, 3 * DAY + 7 * H),
        Call(12, person(12).mobile, CallType.RECEIVED, 4 * DAY + 3 * H, seconds = 1805),
    )

    private fun t(h: Int, m: Int = 0): LocalTime = LocalTime.of(h, m)

    val events: List<Event> = listOf(
        Event(1, "Team stand-up", start = t(9), minutes = 30, location = "Studio", weekdays = true),
        Event(2, "Design review with Olivia", start = t(10, 30), minutes = 60, location = "Studio, room 3", reminder = true),
        Event(3, "Lunch with Sofia", start = t(12, 30), minutes = 60, location = "Riverside Café"),
        Event(4, "Call with Aisha Khan", start = t(15), minutes = 45, location = "Conference line", invitation = true, reminder = true),
        Event(5, "Running club", start = t(18, 30), minutes = 60, location = "Lakeside trail"),
        Event(6, "Dentist check-up", day = 1, start = t(16, 30), minutes = 45, location = "Riverside Dental", reminder = true),
        Event(7, "Gym", day = 1, start = t(7), minutes = 60),
        Event(8, "Q4 planning", day = 2, start = t(10), minutes = 120, location = "Boardroom", reminder = true),
        Event(9, "Site visit", day = 2, start = t(14), minutes = 90, location = "Hollis Property Group"),
        Event(10, "Sofia's birthday dinner", day = 3, start = t(19, 30), minutes = 150, location = "Trattoria Sole"),
        Event(11, "Kitchen tap repair", day = 3, start = t(9), minutes = 60, location = "Home"),
        Event(12, "Hike with Daniel, Lucas and Sofia", weekday = DayOfWeek.SATURDAY, start = t(7, 30), minutes = 480, location = "North trailhead"),
        Event(13, "Sunday lunch at Mom's", weekday = DayOfWeek.SUNDAY, start = t(12), minutes = 150, location = "Guelph"),
        Event(14, "Video call with Noah", day = -1, start = t(17), minutes = 45),
        Event(15, "Team retrospective", day = -2, start = t(15), minutes = 60, location = "Studio, room 1"),
        Event(16, "Yoga", day = -3, start = t(7), minutes = 60),
        Event(17, "Sofia's birthday", day = -1, start = null),
        Event(18, "Book club", day = 9, start = t(19), minutes = 120, location = "Library"),
        Event(19, "Quarterly review", day = 12, start = t(11), minutes = 90, location = "Boardroom"),
        Event(20, "Ben's birthday", day = 16, start = null),
        Event(21, "Concert in the park", day = -8, start = t(20), minutes = 120),
        Event(22, "Workshop with the Tokyo team", day = -12, start = t(18), minutes = 90, location = "Video call"),
        Event(23, "Weekend at the cottage", day = 19, start = null, days = 2),
        Event(24, "Car service", day = 6, start = t(8, 30), minutes = 60, location = "Garage"),
        Event(25, "Lunch with James", day = -5, start = t(12), minutes = 60),
        Event(26, "Farmers market", day = -9, start = t(9), minutes = 90),
        Event(27, "Haircut", day = 8, start = t(17, 30), minutes = 30),
        Event(28, "Parents' anniversary", day = 24, start = null),
    )

    /** Every occurrence of [events] from [from] to [to], both included, seen from [today]. */
    fun occurrences(today: LocalDate, from: LocalDate, to: LocalDate): List<Occurrence> = buildList {
        for (e in events) {
            if (e.weekdays) {
                var d = from
                while (d <= to) {
                    if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) add(Occurrence(e, d))
                    d = d.plusDays(1)
                }
                continue
            }
            val date = dateOf(e, today)
            if (date <= to && date.plusDays(e.days - 1L) >= from) add(Occurrence(e, date))
        }
    }

    /** The day [e] (not a repeating one) happens on, seen from [today]. */
    fun dateOf(e: Event, today: LocalDate): LocalDate {
        val w = e.weekday ?: return today.plusDays(e.day.toLong())
        return today.plusDays(((w.value - today.dayOfWeek.value + 7) % 7).toLong())
    }

    val tasks: List<Task> = listOf(
        Task(1, "Send final files to Olivia", TaskStatus.IN_PROGRESS, TaskPriority.HIGH, dueDay = 0, dueTime = t(18),
            notes = "Floor plans, elevations and the updated material list.", remindBefore = 30),
        Task(2, "Review Q4 budget draft", TaskStatus.NOT_STARTED, TaskPriority.HIGH, dueDay = 1, dueTime = t(12)),
        Task(3, "Book a table for Sofia's birthday", TaskStatus.COMPLETED, TaskPriority.NORMAL, dueDay = -1, dueTime = t(18)),
        Task(4, "Prepare slides for the site visit", TaskStatus.WAITING, TaskPriority.NORMAL, dueDay = 2, dueTime = t(10),
            notes = "Waiting for the site plans from Aisha."),
        Task(5, "Pick up the parcel from Hannah", TaskStatus.NOT_STARTED, TaskPriority.LOW, dueDay = 0, dueTime = t(19)),
        Task(6, "Buy snacks for the hike", TaskStatus.NOT_STARTED, TaskPriority.LOW, dueDay = 3, dueTime = t(18)),
        Task(7, "Renew passport", TaskStatus.DEFERRED, TaskPriority.NORMAL, dueDay = 30, dueTime = t(12)),
        Task(8, "Return library books", TaskStatus.NOT_STARTED, TaskPriority.NORMAL, dueDay = -2, dueTime = t(17)),
        Task(9, "Call Marcus about the kitchen tap", TaskStatus.COMPLETED, TaskPriority.NORMAL, dueDay = -1, dueTime = t(10)),
        Task(10, "Plan the cottage weekend", TaskStatus.NOT_STARTED, TaskPriority.NORMAL),
    )

    val memos: List<Memo> = listOf(
        Memo(1, "Hike packing list", "Water, two litres\nSandwiches (Sofia)\nRain jacket\nCamera and spare battery\nFirst aid kit\nTrail map", 3 * H),
        Memo(2, "Gift ideas", "Sofia: a cookbook, a pasta maker\nMom: garden gloves, a new teapot\nBen: a running watch strap", DAY + 4 * H),
        Memo(3, "Design review notes", "The client prefers the open kitchen.\nMove the stairs to the north wall.\nLarger windows in the living room.\nSend the revised plans by Friday.", 50),
        Memo(4, "Books to read", "The Quiet Harbour\nA Short History of Maps\nNotes from the Mountain\nThe Long Walk Home", 6 * DAY),
        Memo(5, "Grace's lemon cake", "3 eggs\n200 g sugar\n200 g flour\n150 g butter\nZest and juice of 2 lemons\n\nBake 40 minutes at 180 °C.", 2 * DAY),
        Memo(6, "Cottage weekend", "Leave Friday after work\nBring board games\nCheck the canoe booking\nGroceries on the way", 4 * DAY),
        Memo(7, "Running plan", "Mon: rest\nTue: 6 km easy\nThu: intervals\nSat: 12 km long run", 9 * DAY),
    )

    val alarm = Alarm(LocalTime.of(6, 45), weekdays = true)

    /** The carrier's name in place of the real one: a name no operator owns. */
    const val CARRIER = "Carrier"

    /**
     * The unread counts by the Hub's kind names ("SMS", "EMAIL", "MISSED_CALL"), as the banners and
     * the lock screen show them; the Hub's own rule gives the same (ShowcaseItemsTest).
     */
    val unread: Map<String, Int> = mapOf(
        "SMS" to threads.filter { it.channel == Channel.TEXT }.sumOf { it.unread },
        "EMAIL" to threads.count { it.channel == Channel.EMAIL && !it.last.read },
        "MISSED_CALL" to calls.count { it.type == CallType.MISSED && it.isNew },
    ).filterValues { it > 0 }

    /** The text-message thread of the video's story: Daniel Okafor's ([ShowcaseEvent]). */
    const val STORY_THREAD = 9L
    const val STORY_TEXT = "Dinner tonight? I booked a table for 7 :)"
    /** The answer media/demo/capture.sh types in the thread, one key a second. */
    const val STORY_ANSWER = "See you at 7!"
    const val STORY_REPLY = "Great, see you there (y)"

    /** [threads] as far as [event] played the story: Daniel's text arrived (MESSAGE), answered and replied to (REPLY). */
    fun threads(event: ShowcaseEvent?): List<Thread> {
        val added = when (event) {
            ShowcaseEvent.MESSAGE -> listOf(Message(0, false, STORY_TEXT, read = false))
            ShowcaseEvent.REPLY -> listOf(Message(0, false, STORY_TEXT), Message(0, true, STORY_ANSWER), Message(0, false, STORY_REPLY, read = false))
            ShowcaseEvent.ALARM, null -> return threads
        }
        return threads.map { if (it.id == STORY_THREAD) it.copy(messages = it.messages + added) else it }
    }

    /** [unread] once [event] played: one more text while the story's last one is unread. */
    fun unread(event: ShowcaseEvent?): Map<String, Int> {
        val extra = threads(event).sumOf { it.unread } - threads.sumOf { it.unread }
        return if (extra == 0) unread else unread + ("SMS" to (unread["SMS"] ?: 0) + extra)
    }
}

/**
 * The video's events (media/demo/capture.sh), played by the shell only, while the showcase is on:
 * `settings put global bold7_showcase_event message|reply|alarm`. Nothing real is read nor
 * written for them: the apps show the made-up story ([Showcase.threads]) or ring on screen only.
 */
enum class ShowcaseEvent {
    /** Daniel's text arrives: the inbox, his thread and the counters show it. */
    MESSAGE,
    /** His text, the answer and his reply: the reply arrives in the open thread. */
    REPLY,
    /** The Clock's ring screen opens for the showcase's alarm, without a sound or a real alarm. */
    ALARM;

    companion object {
        /** The setting's [value]; null for none or a name this build does not know. */
        fun of(value: String?): ShowcaseEvent? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
