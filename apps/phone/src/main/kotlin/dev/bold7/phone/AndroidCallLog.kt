package dev.bold7.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/** At most this many calls on the Phone screen; the 9900 kept a call log 30 days (guide p. 72). */
const val LOG_MAX = 200

/**
 * Android's call log (READ_CALL_LOG) and the contacts' photos (READ_CONTACTS), both granted by the
 * DIALER role and by tools/install.sh, and placing a call through Telecom (CALL_PHONE). Blocking
 * binder calls: on the graph's io thread only. Nothing is written to these providers, except the
 * missed calls marked seen by Telecom ([markMissedSeen]) and a call deleted by Delete ([delete]).
 */
class AndroidCallLog(private val context: Context) : CallLogSource {
    override fun recent(): List<LogEntry> = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, LOG_MAX.toString()).build(),
            arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_NUMBER_TYPE,
                CallLog.Calls.CACHED_NUMBER_LABEL, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.NEW,
            ),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val type = when (c.getInt(5)) {
                        CallLog.Calls.OUTGOING_TYPE -> LogType.OUTGOING
                        // Rejected and blocked calls were missed for the user (D).
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> LogType.MISSED
                        else -> LogType.INCOMING
                    }
                    val label = if (c.isNull(3)) "" else ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                    add(LogEntry(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), label, type, c.getLong(6), c.getInt(7) != 0))
                }
            }
        }.orEmpty()
    } catch (e: SecurityException) {
        Log.w(TAG, "call log unreadable: READ_CALL_LOG not granted")
        emptyList()
    }

    /**
     * The contacts' numbers (READ_CONTACTS), sorted by name, as rows the field proposes for a name
     * typed ([LogType.CONTACT], ids negative so as never to meet a call's); empty if not allowed.
     */
    override fun contacts(): List<LogEntry> = try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            ),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    val number = c.getString(2).orEmpty()
                    if (name.isBlank() || number.isBlank()) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                    add(LogEntry(-1 - c.getLong(0), number, name, label, LogType.CONTACT, 0, isNew = false))
                }
            }
        }.orEmpty()
    } catch (e: SecurityException) {
        Log.w(TAG, "contacts unreadable: READ_CONTACTS not granted")
        emptyList()
    }

    /** The calls answered or placed, with their duration (Status); empty if not allowed. */
    override fun timed(): List<Timed> = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.TYPE} IN (${CallLog.Calls.INCOMING_TYPE}, ${CallLog.Calls.OUTGOING_TYPE})", null, null,
        )?.use { c -> buildList { while (c.moveToNext()) add(Timed(c.getLong(0), c.getLong(1))) } }.orEmpty()
    } catch (e: SecurityException) {
        Log.w(TAG, "call log unreadable: READ_CALL_LOG not granted")
        emptyList()
    }

    /** The contact's photo for [number], null if none or not allowed. */
    override fun photo(number: String): Bitmap? = try {
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val uri = context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        uri?.let { context.contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream) }
    } catch (e: SecurityException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    /** The name of the contact whose number [number] is (PhoneLookup), null if none or not allowed. */
    override fun name(number: String): String? = try {
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.ifEmpty { null } else null }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Marks the missed calls seen, as the 9900 clears its indicator when the Phone opens (sim-144):
     * TelecomManager.cancelMissedCallsNotification, allowed to the default dialer only. In AOSP 14's
     * Telecom (MissedCallNotifierImpl.clearMissedCalls), for a dialer that does not show the missed
     * calls' notification itself, as ours, it sets NEW 0 and IS_READ 1 on the missed calls of the
     * log and removes Telecom's notification; to check on the Q25's MtkTelecom.
     * Without the role, nothing (the Hub then keeps them new).
     */
    override fun markMissedSeen() {
        try {
            context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
        } catch (e: SecurityException) {
            Log.i(TAG, "missed calls not marked seen: not the default dialer")
        }
    }

    /** Delete of a call log (guide 7.1 p. 72): WRITE_CALL_LOG, granted with the DIALER role and by tools/install.sh. */
    override fun delete(e: LogEntry): Boolean = try {
        val ids = if (numberKey(e.number).isEmpty()) listOf(e.id) else context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER), null, null, null,
        )?.use { c -> buildList { while (c.moveToNext()) if (sameNumber(c.getString(1).orEmpty(), e.number)) add(c.getLong(0)) } }.orEmpty()
        ids.isNotEmpty() && context.contentResolver.delete(
            CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString(",") { "?" }})", ids.map(Long::toString).toTypedArray(),
        ) > 0
    } catch (e: SecurityException) {
        Log.w(TAG, "call not deleted: WRITE_CALL_LOG not granted")
        false
    }

    /** Places a call (CALL_PHONE): Telecom shows it in the DIALER role's InCallService, ours once given. False if refused. */
    override fun place(number: String): Boolean = try {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "call not placed: CALL_PHONE not granted")
        false
    }

    /**
     * The number and name of a phone row Select Contact returned (its data URI, read with the grant it
     * gives, or READ_CONTACTS); null if it does not read.
     */
    override fun picked(uri: String): SpeedNumber? = try {
        val phone = ContactsContract.CommonDataKinds.Phone.NUMBER
        context.contentResolver.query(Uri.parse(uri), arrayOf(phone, ContactsContract.Data.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { SpeedNumber(it, c.getString(1).orEmpty()) } else null
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "picked number unreadable")
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Call Voice Mail (guide p. 77): the SIM's voice mail number (TelephonyManager, READ_PHONE_STATE,
     * from the role's phone permissions), then a voicemail: call, which Telecom dials to that number.
     * Without the permission to read it, the call is placed all the same.
     */
    override fun voiceMail(): VoiceMailResult {
        val known = try {
            !context.getSystemService(TelephonyManager::class.java).voiceMailNumber.isNullOrBlank()
        } catch (e: SecurityException) {
            Log.w(TAG, "voice mail number unreadable: READ_PHONE_STATE not granted")
            true
        }
        if (!known) return VoiceMailResult.NO_NUMBER
        return try {
            context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), null)
            VoiceMailResult.PLACED
        } catch (e: SecurityException) {
            Log.w(TAG, "voice mail not called: CALL_PHONE not granted")
            VoiceMailResult.REFUSED
        }
    }

    /** The SIM's number (READ_PHONE_NUMBERS), empty if it does not say (the band then says "My Number:" alone). */
    override fun lineNumber(): String = try {
        @Suppress("DEPRECATION") // getLine1Number: the SIM's MSISDN, what the 9900 showed.
        context.getSystemService(TelephonyManager::class.java).line1Number.orEmpty()
    } catch (e: SecurityException) {
        ""
    }
}

enum class VoiceMailResult { PLACED, NO_NUMBER, REFUSED }

/** When Status cleared its timers, the app's own (SharedPreferences): on the io thread only. */
class TimersStore(context: Context) : Kept<CallTimersCleared> {
    private val prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE)

    override fun load(): CallTimersCleared = CallTimersCleared.decode(prefs.getString("cleared", "").orEmpty())

    override fun save(value: CallTimersCleared) {
        prefs.edit().putString("cleared", value.encode()).commit()
    }
}

/** The speed dial keys, the app's own (SharedPreferences, never Android's providers): on the io thread only. */
class SpeedDialStore(context: Context) : Kept<SpeedDial> {
    private val prefs = context.getSharedPreferences("speed_dial", Context.MODE_PRIVATE)

    override fun load(): SpeedDial = SpeedDial.decode(prefs.getString(KEY, "").orEmpty())

    override fun save(value: SpeedDial) {
        prefs.edit().putString(KEY, value.encode()).commit()
    }

    private companion object {
        const val KEY = "keys"
    }
}
