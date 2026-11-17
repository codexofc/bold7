package dev.bold7.pictures

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentSender
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.status

/**
 * A picture's actions for the grid and the viewer, which both offer them: Delete asked first,
 * Set As Wallpaper, Assign To Contact through Select Contact, Send > Text Message. [done] runs on
 * the main thread once a picture is gone (the screen reloads). Nothing acts over the lock screen.
 * The activity passes its results through [onResult].
 */
class PictureActs(private val activity: Activity, private val done: (deleted: Boolean) -> Unit) {
    private companion object {
        const val REQUEST_DELETE = 71
        const val REQUEST_CONTACT = 72
    }

    private val graph = activity.graph
    private var pending: Picture? = null

    private fun locked() = activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun say(id: Int) = status(activity, activity.getString(id))

    /** Delete: "Delete <name>?", Cancel focused (sim-199, C, as MemoPad's and Contacts'), then MediaStore's request without Android's dialog. */
    fun delete(p: Picture) {
        if (locked()) return
        Bold7Dialog(
            activity, activity.getString(R.string.delete_question, p.name),
            listOf(activity.getString(R.string.act_delete), activity.getString(R.string.cancel)),
            DialogIcon.QUESTION, focused = 1,
            onButton = { b -> if (b == 0 && p.id >= 0) request(p) }, // made-up pictures (showcase, demo): asked, nothing deleted
        ).show()
    }

    private fun request(p: Picture) {
        try {
            activity.startIntentSenderForResult(deleteRequest(activity, p).intentSender, REQUEST_DELETE, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            say(R.string.not_deleted)
        }
    }

    fun setWallpaper(p: Picture) {
        if (locked()) return
        if (p.id < 0) return say(R.string.wallpaper_set) // made-up picture: reported, nothing written
        graph.io.post {
            val ok = writeWallpaper(activity, p)
            graph.main.post { say(if (ok) R.string.wallpaper_set else R.string.wallpaper_not_set) }
        }
    }

    fun assignToContact(p: Picture) {
        if (locked() || p.id < 0) return
        pending = p
        if (!activity.startSafely(selectContact(), REQUEST_CONTACT)) say(R.string.contact_not_set)
    }

    fun sendAsText(p: Picture) {
        if (locked() || p.id < 0) return
        if (!activity.startSafely(textIntent(p))) say(R.string.no_messages)
    }

    /** The activity's onActivityResult; true when it was one of these. */
    fun onResult(request: Int, result: Int, data: Intent?): Boolean = when (request) {
        REQUEST_DELETE -> {
            if (result == Activity.RESULT_OK) done(true) else say(R.string.not_deleted)
            true
        }
        REQUEST_CONTACT -> {
            val p = pending
            val contact = data?.data
            pending = null
            if (result == Activity.RESULT_OK && p != null && contact != null) {
                graph.io.post {
                    val ok = writeContactPhoto(activity, graph.store, p, contact)
                    graph.main.post { say(if (ok) R.string.contact_set else R.string.contact_not_set) }
                }
            }
            true
        }
        else -> false
    }
}
