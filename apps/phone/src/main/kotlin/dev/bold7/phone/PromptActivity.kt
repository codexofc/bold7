package dev.bold7.phone

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import dev.bold7.design.Durations
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Phone's two dialogs of OS 7 over whatever is on the screen, a translucent window of its own
 * (docs/BBOS7-VISUEL.md §26):
 * - "You have 2 new missed calls." (sim-19, C), View focused over Close (sim-20), started by the
 *   InCallService as soon as a missed call ends, the count since it last closed (sim-201 to
 *   sim-203); View opens the missed calls ([viewMissed]), Close leaves the home's indicator (sim-21);
 * - "Call 5551234?" with the question mark, Call focused over Cancel (sim-104, C), for a missed call
 *   opened from the list of notifications or from Messages ([ACTION_CALL_PROMPT] on tel:N); Call
 *   places it through Telecom, which shows it on the call screen once the Phone holds DIALER.
 *   Call stays focused as on the 9900, but the dialog chooses nothing in its first
 *   [Durations.CALL_PROMPT_ARM_MS]: a quick double click on a missed call never calls a number
 *   not yet read (D).
 *
 * Never over the lock screen (no showWhenLocked): a dialog started while locked waits behind it,
 * and shows once the phone is unlocked. Exported for [ACTION_CALL_PROMPT] only to Bold7's apps
 * (dev.bold7.permission.CALL_PROMPT, signature); it never calls without the user's Call.
 */
class PromptActivity : Activity() {
    companion object {
        const val ACTION_CALL_PROMPT = "dev.bold7.phone.CALL_PROMPT"

        /** A letter held on the home or in Messages (guide 7.1 p. 65); [EXTRA_KEY] is the letter. */
        const val ACTION_SPEED_DIAL = "dev.bold7.phone.SPEED_DIAL"
        private const val EXTRA_KEY = "dev.bold7.phone.KEY"
        private const val EXTRA_MISSED = "dev.bold7.phone.MISSED"
        private const val MESSAGES = "dev.bold7.messages"

        fun missed(context: Context, count: Int): Intent =
            Intent(context, PromptActivity::class.java).putExtra(EXTRA_MISSED, count).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val graph get() = (application as PhoneApp).graph
    private lateinit var scope: CoroutineScope
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the home, whose banner stands in for Android's bar (FocusDialog's hideStatusBar).
        // Through decorView: this activity never calls setContentView, so the window has no decor
        // yet and window.insetsController throws (crash of 13/11, 20:14).
        window.decorView.windowInsetsController?.hide(WindowInsets.Type.statusBars())
        // OS 7's dialogs come and go without animation (sim-19, sim-104; D, as FocusDialog).
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        scope = CoroutineScope(SupervisorJob() + graph.main)
        opened(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened(intent)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    private fun opened(intent: Intent) {
        val count = intent.getIntExtra(EXTRA_MISSED, 0)
        val number = intent.data?.takeIf { intent.action == ACTION_CALL_PROMPT && it.scheme == "tel" }?.schemeSpecificPart
        val key = intent.getStringExtra(EXTRA_KEY)?.singleOrNull()?.takeIf { intent.action == ACTION_SPEED_DIAL }
        when {
            key != null -> speedDial(key)
            count > 0 -> show(resources.getQuantityString(R.plurals.missed_calls, count, count), listOf(R.string.act_view, R.string.act_close), null) { view ->
                graph.missed = graph.missed.reduce(MissedEvent.Closed)
                if (view) viewMissed()
            }
            !number.isNullOrEmpty() -> scope.launch {
                // The contact's name if the number is one's (D: sim-104's number is no contact's).
                val name = withContext(graph.io) { graph.log.name(number) }
                show(getString(R.string.call_prompt, name ?: number), listOf(R.string.act_call, R.string.act_cancel), DialogIcon.QUESTION, Durations.CALL_PROMPT_ARM_MS) { call ->
                    if (call) place(number)
                }
            }
            else -> finish()
        }
    }

    /**
     * A letter held (guide p. 64-65): the number of its key is called, W calls the voice mail, a free
     * key offers to assign it (D: as the Phone screen's own held keys, [held]). The key is the
     * user's own call, so nothing is asked first; the numbers are read here, never sent out.
     */
    private fun speedDial(key: Char) {
        scope.launch {
            when (val h = held("", key, withContext(graph.io) { graph.speedDial.load() })) {
                Held.VoiceMail -> when (withContext(graph.io) { graph.log.voiceMail() }) {
                    VoiceMailResult.PLACED -> finish()
                    VoiceMailResult.NO_NUMBER -> refused(R.string.no_voice_mail)
                    VoiceMailResult.REFUSED -> refused(R.string.call_refused)
                }
                is Held.Call -> place(h.number)
                is Held.Assign -> show(getString(R.string.assign_speed_dial, h.key.uppercaseChar().toString()), listOf(R.string.button_yes, R.string.button_no), DialogIcon.QUESTION) { yes ->
                    if (yes) startActivity(SpeedDialActivity.assigning(this@PromptActivity, h.key))
                }
                null -> finish()
            }
        }
    }

    private fun refused(message: Int) {
        status(this, getString(message))
        window.decorView.postDelayed(::finish, Durations.STATUS_MS)
    }

    /**
     * View: on the 9900, the list of missed calls in Messages' look (sim-204, C), which is Bold7's
     * Messages on its calls (dev.bold7.messages, its view extra, InboxState.entryView); without it,
     * the Phone on its log (D). Neither marks the calls seen: the Phone does, once opened.
     */
    private fun viewMissed() {
        val messages = Intent(Intent.ACTION_MAIN).setClassName(MESSAGES, "$MESSAGES.MessagesActivity")
            .putExtra("$MESSAGES.VIEW", "missed_calls").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(messages)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(this, PhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** OS 7's dialog, its first button focused; [then] gets whether that first button was chosen, before the window goes. */
    private fun show(text: String, buttons: List<Int>, icon: DialogIcon?, armMs: Long = 0, then: (Boolean) -> Unit) {
        dialog?.dismiss()
        dialog = Bold7Dialog(
            this, text, buttons.map(::getString), icon,
            onButton = {
                then(it == 0)
                if (!placing) finish()
            },
            onCancel = {
                then(false)
                finish()
            },
            hideStatusBar = true,
            armMs = armMs,
        ).apply { show() }
    }

    private var placing = false

    /** Call: Telecom places it (CALL_PHONE); refused, OS 7's status message says so (D), then the window goes. */
    private fun place(number: String) {
        placing = true
        scope.launch {
            val placed = withContext(graph.io) { graph.log.place(number) }
            if (!placed) {
                status(this@PromptActivity, getString(R.string.call_refused))
                window.decorView.postDelayed(::finish, Durations.STATUS_MS)
            } else {
                finish()
            }
        }
    }
}
