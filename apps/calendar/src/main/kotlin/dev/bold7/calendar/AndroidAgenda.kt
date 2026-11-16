package dev.bold7.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.util.Log

/**
 * Android's calendar provider (READ_CALENDAR, WRITE_CALENDAR, granted by tools/install.sh): the
 * occurrences of the views, one event with its reminder and attendees, the reminders of an instant,
 * and the writes of New, Save, Delete and an invitation's answer. Nothing is copied: the agenda is
 * the provider's, synchronised by the Google account. Blocking binder calls: the graph's io thread
 * only. A refused permission reads as empty and writes nothing, logged.
 */
class AndroidAgenda(private val context: Context) : AgendaSource {
    private val resolver get() = context.contentResolver

    /** The occurrences between [from] and [to] (ms) of the visible calendars, deleted and declined ones left out. */
    override fun occurrences(from: Long, to: Long): List<Occurrence> = guarded(emptyList()) {
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        resolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION,
                Instances.RRULE, Instances.HAS_ALARM, Instances.IS_ORGANIZER, Instances.HAS_ATTENDEE_DATA, Instances.DISPLAY_COLOR,
                Instances.SELF_ATTENDEE_STATUS,
            ),
            "${Instances.VISIBLE}=1 AND ${Instances.SELF_ATTENDEE_STATUS}!=${Attendees.ATTENDEE_STATUS_DECLINED}",
            null, "${Instances.BEGIN} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Occurrence(
                        eventId = c.getLong(0), title = c.getString(1).orEmpty(), begin = c.getLong(2), end = c.getLong(3),
                        allDay = c.getInt(4) != 0, location = c.getString(5).orEmpty(), recurring = !c.getString(6).isNullOrEmpty(),
                        reminder = c.getInt(7) != 0, invitation = c.getString(8) == "0" && c.getInt(9) != 0 && c.getInt(11) == Attendees.ATTENDEE_STATUS_INVITED,
                        color = c.getInt(10),
                    ),
                )
            }
        }.orEmpty()
    }

    /** The event [id] with its first reminder and its attendees, or null. */
    override fun event(id: Long): Stored? = guarded(null) {
        val e = resolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, id),
            arrayOf(
                Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.ALL_DAY, Events.DTSTART,
                Events.DTEND, Events.DURATION, Events.RRULE, Events.ORGANIZER, Events.IS_ORGANIZER, Events.SELF_ATTENDEE_STATUS,
                Events.CALENDAR_ACCESS_LEVEL, Events.EVENT_TIMEZONE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val begin = c.getLong(5)
            val end = if (c.isNull(6)) begin + (durationMs(c.getString(7)) ?: 0) else c.getLong(6)
            Stored(
                id, c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getInt(4) != 0,
                begin, end, c.getString(8)?.takeIf { it.isNotBlank() }, null, c.getString(9).orEmpty(), c.getString(10) != "0",
                c.getInt(11), readOnly = c.getInt(12) < Calendars.CAL_ACCESS_CONTRIBUTOR, timezone = c.getString(13),
            )
        } ?: return@guarded null
        val reminder = resolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID}=?", arrayOf("$id"), "${Reminders.MINUTES} ASC",
        )?.use { c -> if (c.moveToFirst()) c.getInt(0).let { if (it < 0) DEFAULT_REMINDER else it } else null }
        val attendees = resolver.query(
            Attendees.CONTENT_URI, arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP),
            "${Attendees.EVENT_ID}=?", arrayOf("$id"), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) if (c.getInt(3) != Attendees.RELATIONSHIP_ORGANIZER) add(Attendee(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2)))
            }
        }.orEmpty()
        e.copy(reminder = reminder, attendees = attendees)
    }

    /** The calendar a new appointment goes to: the primary writable one of the visible calendars, else the first (D). */
    override fun defaultCalendar(): Long? = guarded(null) {
        resolver.query(
            Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.IS_PRIMARY),
            "${Calendars.VISIBLE}=1 AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=${Calendars.CAL_ACCESS_CONTRIBUTOR}", null, "${Calendars.IS_PRIMARY} DESC, ${Calendars._ID} ASC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** The reminders the provider holds for [alarmTime] (CalendarAlerts, joined with its events by the provider). */
    override fun alerts(alarmTime: Long): List<Alert> = guarded(emptyList()) {
        resolver.query(
            CalendarAlerts.CONTENT_URI,
            arrayOf(CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN, CalendarAlerts.END, CalendarAlerts.ALARM_TIME, CalendarAlerts.TITLE, CalendarAlerts.EVENT_LOCATION, CalendarAlerts.ALL_DAY),
            "${CalendarAlerts.ALARM_TIME}=?", arrayOf("$alarmTime"), null,
        )?.use { c ->
            buildList { while (c.moveToNext()) add(Alert(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6) != 0)) }
        }.orEmpty()
    }

    /** Writes [draft] (a new event, or the one it edits) and its reminder; the event's id, or null if refused. */
    override fun save(draft: Draft): Long? = guarded(null) {
        val values = ContentValues().apply {
            for ((k, v) in draft.columns()) when (v) {
                null -> putNull(k)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is String -> put(k, v)
            }
        }
        val id = draft.id?.also {
            values.remove(Events.CALENDAR_ID)
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, it), values, null, null)
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf("$it"))
        } ?: resolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId) ?: return@guarded null
        draft.reminder?.let {
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, it)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        id
    }

    /** Deletes the event [id] (all its occurrences): the provider marks it, the sync adapter removes it from the account. */
    override fun delete(id: Long): Boolean = guarded(false) { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0 }

    /**
     * Deletes one occurrence of a recurring event: an exception cancelled at [begin], as Android's
     * own calendar does (CONTENT_EXCEPTION_URI).
     */
    override fun deleteOccurrence(id: Long, begin: Long): Boolean = guarded(false) {
        val uri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id)
        resolver.insert(uri, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, begin)
            put(Events.STATUS, Events.STATUS_CANCELED)
        }) != null
    }

    /** Accept, Tentative or Decline (guide p. 183): Bold7's user's status in the event's attendees, which the sync adapter sends. */
    override fun answer(id: Long, status: Int): Boolean = guarded(false) {
        val email = resolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.CALENDAR_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }?.let { cal ->
            resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, cal), arrayOf(Calendars.OWNER_ACCOUNT), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } ?: return@guarded false
        resolver.update(
            Attendees.CONTENT_URI, ContentValues().apply { put(Attendees.ATTENDEE_STATUS, status) },
            "${Attendees.EVENT_ID}=? AND ${Attendees.ATTENDEE_EMAIL}=?", arrayOf("$id", email),
        ) > 0
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "calendar provider refused: READ_CALENDAR or WRITE_CALENDAR not granted (tools/install.sh)")
        fallback
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "calendar provider refused the request", e)
        fallback
    }
}

/** The provider's authority, the reminders' broadcast data (content://com.android.calendar/<instant>). */
const val AUTHORITY = CalendarContract.AUTHORITY
