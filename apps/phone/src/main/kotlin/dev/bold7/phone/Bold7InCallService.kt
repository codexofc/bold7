package dev.bold7.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import java.util.IdentityHashMap

/**
 * Bound by Telecom while dev.bold7.phone holds DIALER and a call exists (IN_CALL_SERVICE_UI): every
 * call, SIM or other, goes through here. It only mirrors Telecom into [PhoneGraph.board] and sends
 * the screen's [Command]s back, on the main thread where Telecom calls it; nothing here reads a
 * database, the network or a disk (the main thread only renders). Telecom plays the ringtone itself (no
 * IN_CALL_SERVICE_RINGING): the sound profile's ringtone and volume, as before.
 *
 * Should this process die during a call, Telecom binds it again (the call goes on) and,
 * failing that, the system dialer, Google's Phone (InCallController's EmergencyInCallServiceConnection,
 * D).
 */
class Bold7InCallService : InCallService(), Telecom {
    private val graph get() = (application as PhoneApp).graph
    private val ids = IdentityHashMap<Call, Int>()
    /** Add Wait's digits Telecom holds for each call until told (onPostDialWait). */
    private val waits = IdentityHashMap<Call, String>()
    private var nextId = 1
    private val handler = Handler(Looper.getMainLooper())
    private var proximity: PowerManager.WakeLock? = null
    private var fallbackShown = false

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
        override fun onParentChanged(call: Call, parent: Call?) = publish()
        override fun onChildrenChanged(call: Call, children: List<Call>) = publish()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: List<Call>) = publish()
        override fun onPostDialWait(call: Call, remainingPostDialSequence: String) {
            waits[call] = remainingPostDialSequence
            publish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph.telecom = this
    }

    override fun onDestroy() {
        if (graph.telecom === this) graph.telecom = null
        handler.removeCallbacksAndMessages(null)
        releaseProximity()
        cancelFallback()
        graph.board.value = graph.board.value.reduce(CallEvent.CallsChanged(emptyList()))
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        ids[call] = nextId++
        call.registerCallback(callback)
        Log.i(TAG, "call added: ${stateOf(call.details.state)}")
        chooseAccount(call)
        publish()
        showScreen()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        ids.remove(call)
        waits.remove(call)
        val missed = call.details.disconnectCause?.code == DisconnectCause.MISSED
        Log.i(TAG, "call removed${if (missed) ": missed" else ""}")
        publish()
        graph.missed = graph.missed.reduce(MissedEvent.Removed(missed, ids.size))
        // OS 7's "You have N new missed calls." (sim-19), started while Telecom still binds us;
        // locked, it waits behind the lock screen (PromptActivity).
        if (graph.missed.show) startActivity(PromptActivity.missed(this, graph.missed.count))
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        graph.board.value = graph.board.value.reduce(CallEvent.AudioChanged(audioOf(audioState)))
        updateProximity()
    }

    /** A volume key while ringing (Telecom asks the UI to silence): the Ringer Off of sim-18 is spent. */
    override fun onSilenceRinger() {
        graph.board.value = graph.board.value.reduce(CallEvent.RingerSilenced)
    }

    override fun onBringToForeground(showDialpad: Boolean) = showScreen()

    /** The call screen, over the lock screen too (showWhenLocked, turnScreenOn). */
    private fun showScreen() {
        startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun publish() {
        val infos = calls.filter { it.parent == null && ids.containsKey(it) }.map(::info)
        graph.board.value = graph.board.value.reduce(CallEvent.CallsChanged(infos))
        updateProximity()
        watchRinging()
    }

    private fun info(call: Call): CallInfo {
        val d = call.details
        val allowed = d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED
        val network = d.callerDisplayName.takeIf { d.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED }
        // Call Voice Mail's call (voicemail:, no number of its own): "Voice Mail" (D).
        val voiceMail = getString(R.string.voice_mail).takeIf { d.handle?.scheme == PhoneAccount.SCHEME_VOICEMAIL }
        return CallInfo(
            id = ids.getValue(call),
            state = stateOf(d.state),
            number = if (allowed) d.handle?.schemeSpecificPart.orEmpty() else "",
            name = voiceMail ?: d.contactDisplayName ?: network.orEmpty(),
            connectedAt = d.connectTimeMillis.coerceAtLeast(0),
            canHold = d.can(Call.Details.CAPABILITY_HOLD),
            canMute = d.can(Call.Details.CAPABILITY_MUTE),
            canMerge = d.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || call.conferenceableCalls.isNotEmpty(),
            participants = call.children.filter { ids.containsKey(it) }.map(::participant),
            postDialWait = waits[call].orEmpty(),
        )
    }

    /** A conference's call, and whether Telecom lets it be split off or dropped alone (guide p. 71). */
    private fun participant(child: Call): Participant {
        val d = child.details
        val allowed = d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED
        return Participant(
            id = ids.getValue(child),
            number = if (allowed) d.handle?.schemeSpecificPart.orEmpty() else "",
            name = d.contactDisplayName.orEmpty(),
            canSplit = d.can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE),
            canDrop = d.can(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE),
        )
    }

    /**
     * A call waiting for its SIM (SELECT_PHONE_ACCOUNT, a phone with two): the Q25 has one, and the
     * default outgoing account is set (dumpsys telecom). Should Telecom still ask, the first account
     * it offers is taken (D), rather than leaving the call hanging.
     */
    @Suppress("DEPRECATION") // Call.AVAILABLE_PHONE_ACCOUNTS: the extra Telecom still fills on Android 14.
    private fun chooseAccount(call: Call) {
        if (call.details.state != Call.STATE_SELECT_PHONE_ACCOUNT) return
        val accounts = call.details.intentExtras?.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS, PhoneAccountHandle::class.java)
        val account = accounts?.firstOrNull()
        Log.i(TAG, "call asks for its account: ${accounts?.size ?: 0} offered")
        if (account != null) call.phoneAccountSelected(account, false) else call.disconnect()
    }

    override fun run(command: Command) {
        Log.i(TAG, "command ${command.javaClass.simpleName}")
        when (command) {
            is Command.Answer -> callOf(command.id)?.answer(VideoProfile.STATE_AUDIO_ONLY)
            is Command.Reject -> callOf(command.id)?.reject(false, null)
            is Command.Disconnect -> callOf(command.id)?.disconnect()
            is Command.Hold -> callOf(command.id)?.hold()
            is Command.Unhold -> callOf(command.id)?.unhold()
            is Command.Conference -> callOf(command.with)?.let { callOf(command.id)?.conference(it) }
            is Command.Mute -> setMuted(command.on)
            is Command.AudioRoute -> setAudioRoute(
                when (command.route) {
                    Route.SPEAKER -> CallAudioState.ROUTE_SPEAKER
                    Route.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
                    // The handset, or the headset when one is plugged in.
                    Route.EARPIECE, Route.WIRED -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
                },
            )
            Command.SilenceRinger -> {
                // Allowed to the default dialer (TelecomManager.silenceRinger).
                try {
                    getSystemService(TelecomManager::class.java).silenceRinger()
                } catch (e: SecurityException) {
                    Log.w(TAG, "ringer not silenced: not the default dialer")
                }
                graph.board.value = graph.board.value.reduce(CallEvent.RingerSilenced)
            }
            is Command.PlayTone -> callOf(command.id)?.playDtmfTone(command.c)
            is Command.StopTone -> callOf(command.id)?.stopDtmfTone()
            is Command.Split -> callOf(command.id)?.splitFromConference()
            is Command.PostDial -> callOf(command.id)?.let {
                it.postDialContinue(command.go)
                waits.remove(it)
                publish()
            }
        }
    }

    private fun callOf(id: Int): Call? = ids.entries.firstOrNull { it.value == id }?.key

    // The proximity sensor: the screen off at the ear.

    private fun updateProximity() {
        if (wantsProximity(graph.board.value)) {
            val lock = proximity ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "bold7:proximity").also { proximity = it }
            // No timeout: released as soon as the call leaves the ear, or this service goes.
            if (!lock.isHeld) lock.acquire()
        } else {
            releaseProximity()
        }
    }

    private fun releaseProximity() {
        proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    // The fallback of the incoming call's screen.

    /**
     * Should the call screen not show within [FALLBACK_MS] of a ringing call (an activity start
     * refused by Android), a full-screen notification brings it, as Google's Phone does for every
     * call: one check per ringing call, no polling. Cancelled when nothing rings any more.
     */
    private fun watchRinging() {
        val ringing = graph.board.value.calls.any { it.ringing }
        if (!ringing) {
            handler.removeCallbacks(checkShown)
            cancelFallback()
        } else if (!fallbackShown && !handler.hasCallbacks(checkShown)) {
            handler.postDelayed(checkShown, FALLBACK_MS)
        }
    }

    private val checkShown = Runnable {
        if (graph.callScreenShown || graph.board.value.calls.none { it.ringing }) return@Runnable
        Log.w(TAG, "call screen not shown: full-screen notification")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, CallActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val caller = graph.board.value.calls.firstOrNull { it.ringing }?.caller.orEmpty()
        nm.notify(
            FALLBACK_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(getString(R.string.incoming_call))
                .setContentText(caller.ifEmpty { getString(R.string.unknown_number) })
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .build(),
        )
        fallbackShown = true
    }

    private fun cancelFallback() {
        if (!fallbackShown) return
        getSystemService(NotificationManager::class.java).cancel(FALLBACK_ID)
        fallbackShown = false
    }

    private companion object {
        const val CHANNEL = "calls"
        const val FALLBACK_MS = 2000L
    }
}

/** The incoming call's fallback notification; CallActivity cancels it once on the screen. */
internal const val FALLBACK_ID = 1

private fun Call.Details.can(capability: Int) = (callCapabilities and capability) != 0

private fun stateOf(state: Int) = when (state) {
    Call.STATE_NEW -> CallState.NEW
    Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> CallState.CONNECTING
    Call.STATE_SELECT_PHONE_ACCOUNT -> CallState.SELECT_ACCOUNT
    Call.STATE_DIALING -> CallState.DIALING
    // SIMULATED_RINGING: a call screened by a call screening app, then put through as ringing.
    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallState.RINGING
    Call.STATE_ACTIVE -> CallState.ACTIVE
    Call.STATE_HOLDING -> CallState.HOLDING
    Call.STATE_DISCONNECTING -> CallState.DISCONNECTING
    Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
    // AUDIO_PROCESSING: answered by a call screening app, not yet by the user (D).
    else -> CallState.CONNECTING
}

private fun audioOf(s: CallAudioState): Audio {
    val routes = buildSet {
        if (s.supportedRouteMask and CallAudioState.ROUTE_EARPIECE != 0) add(Route.EARPIECE)
        if (s.supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET != 0) add(Route.WIRED)
        if (s.supportedRouteMask and CallAudioState.ROUTE_SPEAKER != 0) add(Route.SPEAKER)
        if (s.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0) add(Route.BLUETOOTH)
    }
    val route = when (s.route) {
        CallAudioState.ROUTE_SPEAKER -> Route.SPEAKER
        CallAudioState.ROUTE_BLUETOOTH -> Route.BLUETOOTH
        CallAudioState.ROUTE_WIRED_HEADSET -> Route.WIRED
        else -> Route.EARPIECE
    }
    return Audio(route, routes, s.isMuted)
}
