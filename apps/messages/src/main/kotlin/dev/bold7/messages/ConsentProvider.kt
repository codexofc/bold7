package dev.bold7.messages

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** The signature permission behind [ConsentProvider], asked by the launcher alone (its Setup screen). */
const val READ_TEXT_CONSENT = "dev.bold7.permission.READ_TEXT_CONSENT"

/** What call([method]) answers: [read] for "consent" only (GetUserConsentSetting, null when unreadable), nothing else read. */
fun consentAnswer(method: String, read: () -> Boolean?): Boolean? = if (method == "consent") read() else null

/**
 * Whether Google Messages' "Allow Bixby and text on Galaxy devices" is on, for the launcher's Setup
 * screen: call("consent") answers a Bundle with the boolean "on", or null when Google Messages does
 * not answer. Behind [READ_TEXT_CONSENT] (signature), checked again here since Android does not
 * check call() itself. Blocking (a binder call to Google Messages, 15 s at most), on the caller's
 * binder thread. No rows, nothing written.
 */
class ConsentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context!!
        context.enforceCallingPermission(READ_TEXT_CONSENT, "consent")
        val on = consentAnswer(method) { (context.applicationContext as MessagesApp).graph.api.consent() } ?: return null
        return Bundle().apply { putBoolean("on", on) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
