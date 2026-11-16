package dev.bold7.contacts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.status

// What the list and the card do with a contact, the same from both: each effect off the main
// thread when it touches the provider or Telecom, its failure said in OS 7's status message.

/** Messages of Bold7, a new text to a number (its Compose alias, apps/messages). */
private const val MESSAGES = "dev.bold7.messages"
private const val COMPOSE = "dev.bold7.messages.Compose"
private const val COMPOSE_EMAIL = "dev.bold7.messages.ComposeEmail"
const val EXTRA_TO = "to"
const val EXTRA_TITLE = "title"
const val EXTRA_BODY = "body"

/** Call: placed at once through Telecom (OS 7's Call from a contact asks nothing, guide p. 61, B). */
fun Activity.callNumber(number: String) {
    val g = graph
    g.io.post {
        val placed = g.store.call(number)
        if (!placed) runOnUiThread { status(this, getString(R.string.call_refused)) }
    }
}

/** Text: [number]'s conversation in Messages of Bold7, or a new message to it, [name] in its band. */
fun Activity.textNumber(number: String, name: String) {
    try {
        startActivity(Intent().setClassName(MESSAGES, COMPOSE).putExtra(EXTRA_TO, number).putExtra(EXTRA_TITLE, name))
    } catch (e: ActivityNotFoundException) {
        status(this, getString(R.string.no_messages_app))
    } catch (e: SecurityException) {
        status(this, getString(R.string.no_messages_app))
    }
}

/** Email: Compose Email of Messages of Bold7, To filled with [address] under [name] (its ComposeEmail alias). */
fun Activity.emailAddress(address: String, name: String) {
    try {
        startActivity(Intent().setClassName(MESSAGES, COMPOSE_EMAIL).putExtra(EXTRA_TO, address).putExtra(EXTRA_TITLE, name))
    } catch (e: ActivityNotFoundException) {
        status(this, getString(R.string.no_email_app))
    } catch (e: SecurityException) {
        status(this, getString(R.string.no_email_app))
    }
}

/**
 * Send Contact Card: [id]'s card as a vCard text (the provider's rows read off the main thread, the
 * text kept nowhere) in a new message or a new email of Messages of Bold7, To left to fill (D).
 */
fun Activity.sendCard(id: Long, byEmail: Boolean) {
    val g = graph
    g.io.post {
        val card = vCard(g.store.stored(id))
        runOnUiThread {
            if (card == null) return@runOnUiThread status(this, getString(R.string.card_not_sent))
            try {
                startActivity(Intent().setClassName(MESSAGES, if (byEmail) COMPOSE_EMAIL else COMPOSE).putExtra(EXTRA_BODY, card))
            } catch (e: ActivityNotFoundException) {
                status(this, getString(if (byEmail) R.string.no_email_app else R.string.no_messages_app))
            } catch (e: SecurityException) {
                status(this, getString(if (byEmail) R.string.no_email_app else R.string.no_messages_app))
            }
        }
    }
}

/** Copy: the value in the clipboard. */
fun Activity.copyText(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
}

/**
 * Delete: asked first, "Delete <name>?" with the question mark, Delete over Cancel, Cancel focused
 * (sim-199, C); [then] once the provider has removed it.
 */
fun Activity.deleteContact(id: Long, name: String, then: () -> Unit) {
    Bold7Dialog(this, getString(R.string.delete_question, name), listOf(getString(R.string.act_delete), getString(R.string.cancel)), DialogIcon.QUESTION, focused = 1, onButton = { b ->
        if (b != 0) return@Bold7Dialog
        val g = graph
        g.io.post {
            val gone = g.store.delete(id)
            runOnUiThread { if (gone) then() else status(this, getString(R.string.not_deleted)) }
        }
    }).show()
}
