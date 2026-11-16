package dev.bold7.contacts

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import dev.bold7.showcase.showcaseOn

/**
 * ACTION_VIEW and ACTION_EDIT of a contact from another app, exported with strict
 * filters: no screen of its own, it resolves the contact (a lookup URI off the main thread) and
 * starts Bold7's card or form, which show it and write nothing before the user's Save. A contact
 * that is gone, or a URI that is not one, is only closed.
 */
class OpenContactActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = Request.parse(intent.action, intent.dataString, intent.type, emptyMap())
        val ref = (r as? Request.View)?.target ?: (r as? Request.Edit)?.target
        if (ref == null) return finish()
        val edit = r is Request.Edit
        val g = graph
        g.io.post {
            // The showcase opens nothing another app names: it would be a real contact.
            if (showcaseOn(this)) return@post runOnUiThread { finish() }
            val id = when (ref) {
                is ContactRef.Id -> ref.id
                is ContactRef.Lookup -> try {
                    ContactsContract.Contacts.lookupContact(contentResolver, Uri.parse(ref.uri))?.let(ContentUris::parseId)
                } catch (e: SecurityException) {
                    null
                }
            }
            runOnUiThread {
                if (id != null) startActivity(Intent(this, if (edit) EditActivity::class.java else CardActivity::class.java).putExtra(CardActivity.EXTRA_ID, id))
                finish()
            }
        }
    }
}
