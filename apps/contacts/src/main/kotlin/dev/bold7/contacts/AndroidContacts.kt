package dev.bold7.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.OperationApplicationException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.RemoteException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.telecom.TelecomManager
import android.util.Log

/**
 * Android's contacts provider, where every contact stays (typically the Google account's): read
 * for the list, the card and the form, written by Save and Delete. Called on the io thread only. A
 * refused permission or a provider that fails reads as nothing and writes as false, logged under
 * Bold7/Contacts, never with a name or a number.
 */
class AndroidContacts(private val context: Context) : ContactStore {
    private val resolver get() = context.contentResolver

    /** Every contact, in the provider's sort order, with its first number and email address. */
    override fun people(): List<Person> = read(emptyList()) {
        val phones = firsts(Phone.CONTENT_URI, Phone.NUMBER)
        val emails = firsts(Email.CONTENT_URI, Email.ADDRESS)
        val out = ArrayList<Person>()
        resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI), null, null, Contacts.SORT_KEY_PRIMARY)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Person(id, c.getString(1).orEmpty(), phones[id], emails[id], c.getString(2))
            }
        }
        out
    }

    /** Each contact's first value of a data kind, the super primary one first. */
    private fun firsts(uri: Uri, column: String): Map<Long, String> {
        val out = HashMap<Long, String>()
        resolver.query(uri, arrayOf(Data.CONTACT_ID, column), null, null, "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID}")?.use { c ->
            while (c.moveToNext()) {
                val v = c.getString(1) ?: continue
                out.putIfAbsent(c.getLong(0), v)
            }
        }
        return out
    }

    /** A contact's card; null when it is gone. */
    override fun card(id: Long): Card? = read(null) {
        val contact = ContentUris.withAppendedId(Contacts.CONTENT_URI, id)
        val (name, photo) = resolver.query(contact, arrayOf(Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1) else null
        } ?: return@read null
        val details = ArrayList<Detail>()
        val phoneBases = ArrayList<PhoneBase>()
        val groups = ArrayList<Long>()
        var company = ""
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA4),
            "${Data.CONTACT_ID} = ?", arrayOf(id.toString()), "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID}",
        )?.use { c ->
            while (c.moveToNext()) {
                val dataId = c.getLong(0)
                val value = c.getString(2).orEmpty()
                when (c.getString(1)) {
                    Phone.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) {
                        details += Detail(Kind.PHONE, "", value, dataId)
                        phoneBases += phoneBase(c.getInt(3))
                    }
                    Email.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) details += Detail(Kind.EMAIL, "Email", value, dataId)
                    StructuredPostal.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) details += Detail(Kind.ADDRESS, postalLabel(c.getInt(3)), value, dataId)
                    Organization.CONTENT_ITEM_TYPE -> if (company.isEmpty()) company = value
                    GroupMembership.CONTENT_ITEM_TYPE -> c.getString(2)?.toLongOrNull()?.let { groups += it }
                }
            }
        }
        // A number's label is its OS 7 type, the second of a kind its "2" (Mobile 2).
        val types = phoneTypes(phoneBases).iterator()
        Card(id, name, company, details.map { if (it.kind == Kind.PHONE) it.copy(label = types.next().label) else it }, groupTitles(groups), photo)
    }

    /** The titles of the groups the user made; Android's own (My Contacts, Starred) left out. */
    private fun groupTitles(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        resolver.query(
            ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups._ID} IN (${ids.joinToString(",")}) AND ${ContactsContract.Groups.SYSTEM_ID} IS NULL AND ${ContactsContract.Groups.DELETED} = 0",
            null, ContactsContract.Groups.TITLE,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { out += it } }
        return out
    }

    /** What the form shows of contact [id]: each value with its Data row. */
    override fun stored(id: Long): List<Stored> = read(emptyList()) {
        val out = ArrayList<Stored>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4),
            "${Data.CONTACT_ID} = ?", arrayOf(id.toString()), "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID}",
        )?.use { c ->
            // DATA1 to DATA4 are columns 2 to 5.
            fun s(i: Int) = c.getString(i).orEmpty()
            while (c.moveToNext()) {
                val dataId = c.getLong(0)
                when (c.getString(1)) {
                    // DATA2 given name, DATA3 family name, DATA4 prefix (OS 7's Title).
                    StructuredName.CONTENT_ITEM_TYPE -> if (out.none { it.key == FieldKey.FIRST }) {
                        out += Stored(FieldKey.TITLE, dataId, s(5))
                        out += Stored(FieldKey.FIRST, dataId, s(3))
                        out += Stored(FieldKey.LAST, dataId, s(4))
                    }
                    // DATA1 company; the job title is left as it is.
                    Organization.CONTENT_ITEM_TYPE -> if (out.none { it.key == FieldKey.COMPANY }) out += Stored(FieldKey.COMPANY, dataId, s(2))
                    Phone.CONTENT_ITEM_TYPE -> out += Stored(FieldKey.PHONE, dataId, s(2), phoneBase(c.getInt(3)))
                    Website.CONTENT_ITEM_TYPE -> if (out.none { it.key == FieldKey.WEBSITE }) out += Stored(FieldKey.WEBSITE, dataId, s(2))
                    Email.CONTENT_ITEM_TYPE -> out += Stored(FieldKey.EMAIL, dataId, s(2))
                    StructuredPostal.CONTENT_ITEM_TYPE -> out += Stored(FieldKey.ADDRESS, dataId, s(2))
                    Note.CONTENT_ITEM_TYPE -> if (out.none { it.key == FieldKey.NOTES }) out += Stored(FieldKey.NOTES, dataId, s(2))
                    // DATA1 the date, DATA2 its kind; the first birthday and the first anniversary (a custom date is left as it is).
                    Event.CONTENT_ITEM_TYPE -> when (c.getInt(3)) {
                        Event.TYPE_BIRTHDAY -> if (out.none { it.key == FieldKey.BIRTHDAY }) out += Stored(FieldKey.BIRTHDAY, dataId, s(2))
                        Event.TYPE_ANNIVERSARY -> if (out.none { it.key == FieldKey.ANNIVERSARY }) out += Stored(FieldKey.ANNIVERSARY, dataId, s(2))
                    }
                }
            }
        }
        out
    }

    /**
     * Writes [changes] to contact [id], or to a new contact (null) in the account that holds most of
     * them (typically the Google account; the phone when there is none, D). Returns the
     * contact's id, null when the provider refused.
     */
    override fun save(id: Long?, changes: List<Change>): Long? {
        if (changes.isEmpty()) return id
        val ops = ArrayList<ContentProviderOperation>()
        val raw: Long? = if (id == null) null else rawContact(id) ?: return null
        if (raw == null) {
            val (type, name) = account()
            ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, type).withValue(RawContacts.ACCOUNT_NAME, name).build()
        }
        for (change in changes) ops += when (change) {
            is Change.Remove -> ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, change.dataId)).build()
            is Change.Write -> {
                val b = if (change.dataId != null) {
                    ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, change.dataId))
                } else {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mime(change.group)).also {
                        if (raw == null) it.withValueBackReference(Data.RAW_CONTACT_ID, 0) else it.withValue(Data.RAW_CONTACT_ID, raw)
                        if (change.type == null) (if (change.group == Group.DATE) dateType(change.values.keys.first()) else type(change.group))?.let { t -> it.withValue(Data.DATA2, t) }
                    }
                }
                // A number's kind when it is new or chosen anew; an updated row otherwise keeps its own.
                change.type?.let { b.withValue(Phone.TYPE, phoneType(it.base)) }
                for ((key, value) in change.values) b.withValue(column(key), value)
                b.build()
            }
        }
        return try {
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            id ?: results.firstOrNull()?.uri?.let { contactOf(ContentUris.parseId(it)) }
        } catch (e: RemoteException) {
            Log.w(TAG, "save refused: ${e.javaClass.simpleName}")
            null
        } catch (e: OperationApplicationException) {
            Log.w(TAG, "save refused: ${e.javaClass.simpleName}")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "save refused: WRITE_CONTACTS not granted")
            null
        }
    }

    /** Deletes contact [id] (all its raw contacts: the account's sync removes it there too). */
    override fun delete(id: Long): Boolean = try {
        resolver.delete(ContentUris.withAppendedId(Contacts.CONTENT_URI, id), null, null) > 0
    } catch (e: SecurityException) {
        Log.w(TAG, "delete refused: WRITE_CONTACTS not granted")
        false
    }

    /** The raw contact new rows go into: the contact's first one. */
    private fun rawContact(id: Long): Long? = read(null) {
        resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(id.toString()), RawContacts._ID)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun contactOf(raw: Long): Long? = read(null) {
        resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), arrayOf(RawContacts.CONTACT_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** The account (type, name) of most raw contacts; (null, null), the phone's own, when there is none. */
    private fun account(): Pair<String?, String?> = read(null to null) {
        val counts = HashMap<Pair<String?, String?>, Int>()
        resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED} = 0", null, null)?.use { c ->
            while (c.moveToNext()) counts.merge(c.getString(0) to c.getString(1), 1, Int::plus)
        }
        counts.maxByOrNull { it.value }?.key ?: (null to null)
    }

    /** A contact's small picture, null without one. */
    override fun thumbnail(uri: String): Bitmap? = read(null) {
        resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    }

    /** Places a call to [number] through Telecom (the DIALER holder's call screen shows it); false if refused. */
    override fun call(number: String): Boolean = try {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "call not placed: CALL_PHONE not granted")
        false
    }

    private inline fun <T> read(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "read refused: READ_CONTACTS not granted")
        fallback
    } catch (e: java.io.IOException) {
        Log.w(TAG, "read failed: ${e.javaClass.simpleName}")
        fallback
    }

    private fun mime(g: Group) = when (g) {
        Group.NAME -> StructuredName.CONTENT_ITEM_TYPE
        Group.ORGANIZATION -> Organization.CONTENT_ITEM_TYPE
        Group.EMAIL -> Email.CONTENT_ITEM_TYPE
        Group.PHONE -> Phone.CONTENT_ITEM_TYPE
        Group.ADDRESS -> StructuredPostal.CONTENT_ITEM_TYPE
        Group.NOTE -> Note.CONTENT_ITEM_TYPE
        Group.WEBSITE -> Website.CONTENT_ITEM_TYPE
        Group.DATE -> Event.CONTENT_ITEM_TYPE
    }

    private fun column(k: FieldKey) = when (k) {
        FieldKey.TITLE -> StructuredName.PREFIX
        FieldKey.FIRST -> StructuredName.GIVEN_NAME
        FieldKey.LAST -> StructuredName.FAMILY_NAME
        FieldKey.COMPANY -> Organization.COMPANY
        FieldKey.EMAIL -> Email.ADDRESS
        FieldKey.PHONE -> Phone.NUMBER
        FieldKey.ADDRESS -> StructuredPostal.FORMATTED_ADDRESS
        FieldKey.WEBSITE -> Website.URL
        FieldKey.NOTES -> Note.NOTE
        FieldKey.BIRTHDAY, FieldKey.ANNIVERSARY -> Event.START_DATE
    }

    private fun dateType(k: FieldKey) = if (k == FieldKey.BIRTHDAY) Event.TYPE_BIRTHDAY else Event.TYPE_ANNIVERSARY

    /** A new row's TYPE (DATA2) other than a number's: an email "other", an address "home", a website "homepage" (D). */
    private fun type(g: Group): Int? = when (g) {
        Group.EMAIL -> Email.TYPE_OTHER
        Group.ADDRESS -> StructuredPostal.TYPE_HOME
        Group.WEBSITE -> Website.TYPE_HOMEPAGE
        else -> null
    }

    private fun phoneType(b: PhoneBase) = when (b) {
        PhoneBase.WORK -> Phone.TYPE_WORK
        PhoneBase.HOME -> Phone.TYPE_HOME
        PhoneBase.MOBILE -> Phone.TYPE_MOBILE
        PhoneBase.PAGER -> Phone.TYPE_PAGER
        PhoneBase.FAX -> Phone.TYPE_FAX_WORK
        PhoneBase.OTHER -> Phone.TYPE_OTHER
    }

    /** Android's phone types under OS 7's kinds (Work, Home, Mobile, Pager, Fax, Other). */
    private fun phoneBase(type: Int) = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> PhoneBase.MOBILE
        Phone.TYPE_WORK, Phone.TYPE_COMPANY_MAIN -> PhoneBase.WORK
        Phone.TYPE_HOME -> PhoneBase.HOME
        Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> PhoneBase.PAGER
        Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME, Phone.TYPE_OTHER_FAX -> PhoneBase.FAX
        else -> PhoneBase.OTHER
    }

    private fun postalLabel(type: Int) = when (type) {
        StructuredPostal.TYPE_WORK -> "Work Address"
        StructuredPostal.TYPE_HOME -> "Home Address"
        else -> "Address"
    }
}
