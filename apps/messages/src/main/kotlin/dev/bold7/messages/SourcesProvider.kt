package dev.bold7.messages

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dev.bold7.hub.data.EXTRA_DISABLED
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.METHOD_DISABLED_SOURCES

/**
 * The sources turned off in Message Sources, for the Hub (apps/hub, HubGraph): call("disabled")
 * answers their ids in a string array, nothing else is read. Behind READ_HUB (signature), checked
 * again here since Android does not check call() itself. No rows, nothing written; a change is
 * notified on this authority by MessagesStore.update.
 */
class SourcesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context!!
        context.enforceCallingPermission(HubContract.PERMISSION, "sources")
        if (method != METHOD_DISABLED_SOURCES) return null
        val off = (context.applicationContext as MessagesApp).graph.store.disabledSources()
        return Bundle().apply { putStringArray(EXTRA_DISABLED, off.toTypedArray()) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
