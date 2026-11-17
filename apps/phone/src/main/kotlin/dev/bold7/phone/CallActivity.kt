package dev.bold7.phone

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.setContentUnderBar
import dev.bold7.ui.status
import dev.bold7.ui.themeBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The call screen (docs/BBOS7-VISUEL.md §26), over the lock screen too (showWhenLocked,
 * turnScreenOn in the manifest): it renders [PhoneGraph.board] and sends what the user asks to
 * Telecom through the InCallService; it decides nothing. Keys of the 9900 (guide 7.1 pp. 61-63):
 * Send answers, End ignores or ends, the keyboard's digits are DTMF tones, Volume sets the call's
 * volume shown at the top right (sim-109); Menu opens the full menu (sim-130, sim-133); the bar's
 * buttons are touched, never focused by the trackpad, whose click is Ringer Off on a ringing call
 * and nothing during one (sim-131, sim-135). A long touch opens the short menu (D: the Q25's touch
 * way to Answer and End Call). Closes when no call is left.
 */
class CallActivity : Activity() {
    private companion object {
        const val EXTRA_DEMO = "dev.bold7.phone.DEMO"
    }

    private val graph get() = (application as PhoneApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var view: CallView
    private lateinit var menuView: MenuView<Action>

    private var screen: CallScreen? = null
    private var menu: Menu<Action>? = null
    private var tone: Int? = null
    private var myNumber = ""
    private var photoFor: String? = null
    private val glyphs = HashMap<Action, Bitmap?>()

    /** Split Call's and Drop Call's choice, or Add Wait's question; [waitAsked]: the call and digits it asks for. */
    private var dialog: Bold7Dialog? = null
    private var waitAsked: Pair<Int, String>? = null

    /**
     * A test only: `am start -n dev.bold7.phone/.CallActivity --es dev.bold7.phone.DEMO
     * incoming|call|muted` (root: the activity is not exported) shows a made-up call to look at the
     * screen without calling. Only while Telecom has no call, and only on that extra; nothing is
     * sent anywhere (no call is bound, so [PhoneGraph.telecom] is null).
     */
    private val demo: CallBoard? by lazy {
        val now = System.currentTimeMillis()
        val showcase = showcaseOn(this)
        val (number, name) = demoCaller(showcase)
        when (intent.getStringExtra(EXTRA_DEMO)) {
            "incoming" -> CallBoard(listOf(CallInfo(1, CallState.RINGING, number, if (showcase) name else "")))
            "call" -> CallBoard(listOf(CallInfo(1, CallState.ACTIVE, number, name, connectedAt = now - 6000, canHold = true)))
            "muted" -> CallBoard(listOf(CallInfo(1, CallState.ACTIVE, number, connectedAt = now - 6000, canHold = true)), Audio(muted = true))
            else -> null
        }
    }

    private fun board(): CallBoard = graph.board.value.takeIf { it.calls.isNotEmpty() || graph.telecom != null } ?: demo ?: graph.board.value

    private val tick = object : Runnable {
        override fun run() {
            val s = screen ?: return
            if (s.kind == Kind.CALL && s.call.connectedAt > 0 && (s.status == Status.CONNECTED || s.status == Status.ON_HOLD)) {
                view.setTimer(elapsed(System.currentTimeMillis() - s.call.connectedAt))
                view.postDelayed(this, 1000)
            } else {
                view.setTimer("")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = CallView(this, onButton = ::pressed, onHold = ::shortMenu)
        menuView = MenuView(
            this,
            glyph = { glyphs[it] },
            label = ::menuLabel,
            onEntry = ::chosen,
            onExpand = { menu?.let { show(it.expand()) } },
            onClose = { show(null) },
        )
        setContentUnderBar(FrameLayout(this).apply {
            addView(view)
            addView(menuView)
        })
        scope = CoroutineScope(SupervisorJob() + graph.main)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.board.collect { render() } }
        loadImages()
        scope.launch { myNumber = withContext(graph.io) { graph.log.lineNumber() }; render() }
    }

    override fun onResume() {
        super.onResume()
        graph.callScreenShown = true
        // OS 7's screen is up: the fallback notification, if it came, goes with its card of Android
        // (kept for calls), as the Clock's ring does. Not a
        // foreground service's: cancelled, not posted again quiet. A no-op when none was posted.
        graph.ioThread.post { getSystemService(NotificationManager::class.java).cancel(FALLBACK_ID) }
        loadVolume()
    }

    override fun onPause() {
        graph.callScreenShown = false
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        view.removeCallbacks(tick)
        scope.cancel()
        super.onDestroy()
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun render() {
        val s = board().screen(locked())
        if (s == null) {
            finishAndRemoveTask()
            return
        }
        val old = screen
        screen = s
        if (menu != null && (old?.menu != s.menu)) show(null)
        val c = s.call
        val unknown = getString(R.string.unknown_number)
        view.bind(
            s,
            title = getString(R.string.incoming_call),
            status = getString(statusText(s.status)),
            caller = c.caller.ifEmpty { unknown },
            line2 = if (c.name.isNotEmpty()) c.number else "",
            labels = s.buttons.map { getString(buttonText(it.action)) },
            tones = board().tones,
            myNumber = getString(R.string.my_number, myNumber),
            receiver = receiverFrame(board().audio),
        )
        view.removeCallbacks(tick)
        tick.run()
        if (c.number != photoFor) loadPhoto(c.number)
        askWait(s)
    }

    /**
     * Add Wait's digits (guide 7.1 p. 191: the phone "prompts you to type them"): "Send 1234?",
     * Yes sends them, No or Escape drops them (D: no capture of the 9900's prompt). Gone when
     * Telecom no longer waits.
     */
    private fun askWait(s: CallScreen) {
        val asked = s.postDial?.let { s.call.id to it }
        if (asked == waitAsked) return
        if (waitAsked != null) dialog?.dismiss()
        waitAsked = asked
        if (asked == null) return
        dialog?.dismiss()
        val (id, digits) = asked
        val answer = { go: Boolean -> if (waitAsked == asked) graph.telecom?.run(Command.PostDial(id, go)) }
        dialog = Bold7Dialog(
            this, getString(R.string.send_digits, digits), listOf(getString(R.string.button_yes), getString(R.string.button_no)), DialogIcon.QUESTION,
            onButton = { answer(it == 0) }, onCancel = { answer(false) },
        ).also { it.show() }
    }

    /** Split Call, Drop Call: which participant (guide p. 71, "Click a contact"), a button each (D). */
    private fun chooseParticipant(action: Action, s: CallScreen) {
        val ps = s.participants(action)
        if (ps.isEmpty()) return
        val unknown = getString(R.string.unknown_number)
        waitAsked = null
        dialog?.dismiss()
        dialog = Bold7Dialog(this, menuLabel(action), ps.map { it.caller.ifEmpty { unknown } }, onButton = { i ->
            participantCommand(action, ps[i])?.let { graph.telecom?.run(it) }
        }).also { it.show() }
    }

    // Actions

    private fun act(action: Action) {
        val s = screen ?: return
        when (action) {
            // Add Participant (guide p. 71), New Call: the Phone screen places the second call; Telecom holds this one.
            Action.ADD, Action.NEW_CALL -> {
                if (!locked()) startActivity(Intent(this, PhoneActivity::class.java).putExtra(PhoneActivity.EXTRA_ADD, true))
                return
            }
            // Call Voice Mail (sim-133): a second call, Telecom holds this one (D: as New Call).
            Action.VOICE_MAIL -> {
                if (!locked()) callVoiceMail()
                return
            }
            Action.SPLIT, Action.DROP -> {
                chooseParticipant(action, s)
                return
            }
            // Switch Application (sim-133): the call goes on behind; the switcher stays shut while locked.
            Action.SWITCH -> {
                SwitchApplication.start(this)
                return
            }
            // Home Screen (sim-135): the call goes on behind.
            Action.HOME -> {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            else -> Unit
        }
        val telecom = graph.telecom
        if (telecom == null) {
            Log.w(TAG, "no call service bound: ${action.name} dropped")
            return
        }
        perform(action, s, telecom)
    }

    private fun callVoiceMail() = scope.launch {
        when (withContext(graph.io) { graph.log.voiceMail() }) {
            VoiceMailResult.PLACED -> Unit
            VoiceMailResult.NO_NUMBER -> status(this@CallActivity, getString(R.string.no_voice_mail))
            VoiceMailResult.REFUSED -> status(this@CallActivity, getString(R.string.call_refused))
        }
    }

    /** A button of the bar, touched: what it shows, if not greyed. */
    private fun pressed(index: Int) {
        val b = screen?.buttons?.getOrNull(index) ?: return
        if (b.enabled) act(b.action)
    }

    private fun show(m: Menu<Action>?) {
        menu = m
        menuView.bind(m)
    }

    /** The screen's menu, a line between its groups (sim-133, sim-135). */
    private fun entries(s: CallScreen) = s.menu.mapIndexed { i, a -> MenuEntry(a, separatorAbove = i > 0 && group(a) != group(s.menu[i - 1])) }

    private fun fullMenu() {
        val s = screen?.takeIf { it.menu.isNotEmpty() } ?: return
        show(Menu(entries(s), highlighted = s.highlight))
    }

    /** The long touch's short menu (D): End Call first, so that a touch always ends a call; Full Menu has the rest. */
    private fun shortMenu() {
        val s = screen?.takeIf { it.menu.isNotEmpty() } ?: return
        val first = s.menu.filter { it == Action.END } + s.menu.filter { it != Action.END }
        show(Menu(first.map { MenuEntry(it) }, line = 0, highlighted = 0))
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        act(a)
    }

    private fun onMenuKey(m: Menu<Action>, key: MenuKey) {
        when (val r = m.key(key)) {
            is MenuResult.Show -> show(r.menu)
            is MenuResult.Choose -> chosen(r.index)
            MenuResult.Close -> show(null)
        }
    }

    private fun outcome(o: KeyOutcome) {
        when (o) {
            is KeyOutcome.Do -> act(o.action)
            KeyOutcome.Menu -> fullMenu()
            KeyOutcome.None -> Unit
        }
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val s = screen ?: return super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            when {
                click -> {
                    if (menu == null) clickAction(s)?.let(::act)
                    return true
                }
                tone != null && e.keyCode == toneKey -> {
                    graph.telecom?.run(Command.StopTone(tone!!))
                    tone = null
                    return true
                }
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        // The 9900's two call keys, before any menu (guide p. 61). Neither reaches here while a call
        // rings, nor End ever: PhoneWindowManager answers on Call's down
        // (acceptRingingCall) and ends on End's (TelecomManager.endCall), and keeps both from apps.
        when (code) {
            KeyEvent.KEYCODE_CALL -> {
                if (e.repeatCount == 0) {
                    show(null)
                    outcome(callKey(s))
                }
                return true
            }
            KeyEvent.KEYCODE_ENDCALL -> {
                if (e.repeatCount == 0) {
                    show(null)
                    outcome(endKey(s))
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Ringing: Android silences the ringer (PhoneWindowManager). Otherwise the call's
                // volume, shown in the screen's own bar and not in a volume window (sim-109).
                if (s.kind == Kind.INCOMING) return super.dispatchKeyEvent(e)
                adjustVolume(if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                return true
            }
        }
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            onMenuKey(m, when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            })
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_MENU -> fullMenu()
            // A ringing call stays on the screen; otherwise the call goes on behind (D).
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (s.kind != Kind.INCOMING) moveTaskToBack(true)
            else -> when {
                // Held, the click opens nothing (sim-132, sim-135): it acts on its release.
                click -> Unit
                else -> return typeTone(e, s) || super.dispatchKeyEvent(e)
            }
        }
        return true
    }

    private var toneKey = 0

    /** The keyboard's digits, * and # during a connected call: DTMF tones, held as long as the key (D). */
    private fun typeTone(e: KeyEvent, s: CallScreen): Boolean {
        if (!sendsTones(s) || e.repeatCount > 0) return e.repeatCount > 0 && tone != null
        val c = dialChar(e.number, e.getUnicodeChar(0).toChar(), e.isAltPressed)?.takeIf { it.isDigit() || it == '*' || it == '#' } ?: return false
        val telecom = graph.telecom ?: return false
        tone?.let { telecom.run(Command.StopTone(it)) }
        telecom.run(Command.PlayTone(s.call.id, c))
        tone = s.call.id
        toneKey = e.keyCode
        graph.board.value = graph.board.value.reduce(CallEvent.Tone(c))
        return true
    }

    // Off the main thread

    private fun loadImages() = scope.launch {
        val sm = mapOf(
            Action.ANSWER to "ph_sm_call", Action.ANSWER_HOLD to "ph_sm_call", Action.ANSWER_DROP to "ph_sm_call",
            Action.IGNORE to "ph_sm_drop_call", Action.END to "ph_sm_drop_call",
            Action.MUTE to "ph_sm_mute", Action.UNMUTE to "ph_sm_unmute",
            Action.SPEAKER to "ph_sm_speaker_on", Action.HANDSET to "ph_sm_speaker_off",
            Action.ADD to "ph_sm_new_call",
        )
        val buttons = mapOf(
            Action.SPEAKER to "ph_speaker", Action.HANDSET to "ph_handset",
            Action.MUTE to "ph_mute", Action.UNMUTE to "ph_unmute",
            Action.HOLD to "ph_hold", Action.RESUME to "ph_resume",
            Action.ADD to "ph_add", Action.RINGER_OFF to "ph_ringer_off",
        )
        val (images, menuImages) = withContext(graph.io) {
            CallImages(
                background = decode("ph_background"),
                avatar = decode("ph_avatar"),
                bar = decode("ph_bar"),
                barFocus = decode("ph_bar_focus"),
                buttons = buttons.mapValues { decode(it.value) },
                volumeIcon = decode("ph_volume_icon"),
                receiver = decode("ph_receiver"),
                muteOn = decode("ph_mute_on"),
                volume = decode("ph_volume"),
            ) to sm.mapValues { decode(it.value) }
        }
        glyphs.putAll(menuImages)
        view.setImages(images)
        menuView.invalidate()
    }

    private fun decode(name: String): Bitmap = themeBitmap(applicationContext, name)


    private fun loadVolume() = scope.launch {
        val level = withContext(graph.io) {
            val am = getSystemService(AudioManager::class.java)
            val stream = AudioManager.STREAM_VOICE_CALL
            volumeFrame(am.getStreamVolume(stream), am.getStreamMinVolume(stream), am.getStreamMaxVolume(stream))
        }
        view.setVolume(level)
    }

    private fun adjustVolume(direction: Int) = scope.launch {
        withContext(graph.io) {
            // No flag: no volume window of Android (sim-109 shows none).
            getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
        }
        loadVolume()
    }

    private fun loadPhoto(number: String) {
        photoFor = number
        scope.launch {
            val photo = if (number.isEmpty()) null else withContext(graph.io) { graph.log.photo(number) }
            if (photoFor == number) view.setPhoto(photo)
        }
    }

    // Labels: the guide's (B), the captures' (C), else mine (D), res/values/strings.xml.

    private fun statusText(s: Status) = when (s) {
        Status.INCOMING -> R.string.incoming_call
        Status.CALLING -> R.string.status_calling
        Status.CONNECTED -> R.string.status_connected
        Status.MUTED -> R.string.status_muted
        Status.ON_HOLD -> R.string.status_on_hold
        Status.DISCONNECTED -> R.string.status_disconnected
    }

    private fun buttonText(a: Action) = when (a) {
        Action.SPEAKER -> R.string.button_speaker
        Action.HANDSET -> R.string.button_handset
        Action.MUTE -> R.string.button_mute
        Action.UNMUTE -> R.string.button_mute_off
        Action.HOLD -> R.string.button_hold
        Action.RESUME -> R.string.button_resume
        Action.ADD -> R.string.act_add
        else -> R.string.button_ringer_off
    }

    private fun menuLabel(a: Action) = getString(
        when (a) {
            Action.ANSWER -> R.string.act_answer
            Action.ANSWER_HOLD -> R.string.act_answer_hold
            Action.ANSWER_DROP -> R.string.act_answer_drop
            Action.IGNORE -> R.string.act_ignore
            Action.RINGER_OFF -> R.string.button_ringer_off
            Action.END -> R.string.act_end
            Action.SPEAKER -> R.string.act_speaker
            Action.HANDSET -> R.string.act_handset
            Action.BLUETOOTH -> R.string.act_bluetooth
            Action.MUTE -> R.string.button_mute
            Action.UNMUTE -> R.string.act_turn_mute_off
            Action.HOLD -> R.string.button_hold
            Action.RESUME -> R.string.button_resume
            Action.ADD -> R.string.act_add
            Action.NEW_CALL -> R.string.act_new_call
            Action.HOME -> R.string.act_home
            Action.SWAP -> R.string.act_swap
            Action.JOIN -> R.string.act_join
            Action.VOICE_MAIL -> R.string.act_call_voice_mail
            Action.SPLIT -> R.string.act_split
            Action.DROP -> R.string.act_drop
            Action.SWITCH -> R.string.act_switch_application
        },
    )
}
