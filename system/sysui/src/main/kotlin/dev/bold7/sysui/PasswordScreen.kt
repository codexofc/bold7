package dev.bold7.sysui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.PasswordDialog
import java.nio.CharBuffer
import java.util.concurrent.Executors

/**
 * The device password's boxes of OS 7 (Password.kt), in SystemUI's process: Options > Security >
 * Password asks for a task by a broadcast (PASSWORD_ACTION, behind LOCK_SETTINGS_PERMISSION, from
 * the launcher alone), and the boxes come over Options, in a window of SystemUI. What is typed goes
 * from the box to LockPatternUtils.setLockCredential, on a thread of its own (the call derives
 * keys and talks to Gatekeeper: never on SystemUI's main thread), then every copy is zeroed: no
 * String of it, no log, nothing kept. Checking the current password, storing the new one, the
 * count of attempts and the delays stay Android's (LockSettingsService, Gatekeeper, the TEE).
 * Options learns the outcome by reading KeyguardManager.isDeviceSecure when it has the focus back.
 */
internal class PasswordScreen(
    private val images: (Context) -> Images?,
    private val fail: (String, Exception) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bold7-password") }
    private val main = Handler(Looper.getMainLooper())
    /** The flow on screen, if any: one at a time. */
    private var flow: Flow? = null

    /** Once, with SystemUI's Application: the receiver, on the main thread. */
    fun register(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val sender = sentFromPackage
                val task = passwordTaskFor(sender, intent.getStringExtra(PASSWORD_TASK_EXTRA))
                if (task == null) {
                    Log.w(TAG, "password: refused to $sender")
                    return
                }
                start(app, task)
            }
        }
        app.registerReceiver(receiver, IntentFilter(PASSWORD_ACTION), LOCK_SETTINGS_PERMISSION, null, Context.RECEIVER_EXPORTED)
        app.registerReceiver(lockMessage(app), IntentFilter(LOCK_MESSAGE_ACTION), LOCK_SETTINGS_PERMISSION, null, Context.RECEIVER_EXPORTED)
        // Nothing of the flow outlives the screen: no box over the lock screen, nothing typed kept.
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                flow?.abort()
            }
        }
        app.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "password: offered behind $LOCK_SETTINGS_PERMISSION")
    }

    /**
     * Message on Lock Screen: Android keeps the owner's information in LockSettings, which only a
     * holder of ACCESS_KEYGUARD_SECURE_STORAGE reads or writes. Written when the launcher's
     * request carries the two fields, then read back into the result, on [worker] (binder).
     */
    private fun lockMessage(app: Context) = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (sentFromPackage != LAUNCHER_PACKAGE) {
                Log.w(TAG, "lock message: refused to $sentFromPackage")
                return
            }
            val name = intent.getStringExtra(OWNER_NAME_EXTRA)
            val info = intent.getStringExtra(OWNER_INFO_EXTRA)
            val pending = goAsync()
            worker.execute {
                try {
                    val utils = LockPatternUtils(app)
                    val user = Process.myUid() / 100_000
                    if (name != null && info != null) {
                        val text = ownerText(name, info)
                        utils.setOwnerInfo(text, user)
                        utils.setOwnerInfoEnabled(text.isNotEmpty(), user)
                        Log.i(TAG, "lock message: written")
                    }
                    val lines = ownerLines(if (utils.isOwnerInfoEnabled(user)) utils.getOwnerInfo(user) else null)
                    pending.setResultExtras(android.os.Bundle().apply {
                        putString(OWNER_NAME_EXTRA, lines.name.orEmpty())
                        putString(OWNER_INFO_EXTRA, lines.info.orEmpty())
                    })
                    pending.resultCode = android.app.Activity.RESULT_OK
                } catch (e: RuntimeException) {
                    fail("lock message: not read or written", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun start(app: Context, task: PasswordTask) {
        if (flow != null) return
        // Only from Options in front of an unlocked phone (the launcher cannot be in front otherwise).
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            Log.w(TAG, "password: $task refused, locked")
            return
        }
        val images = images(app) ?: return
        Log.i(TAG, "password: $task")
        Flow(windowContext(app, images.res), images, task).also { flow = it }.ask(0)
    }

    /**
     * A context for SystemUI's own windows over the app in front (TYPE_STATUS_BAR_SUB_PANEL, as
     * SystemUI's dialogs), with this module's resources for core/ui's boxes.
     */
    private fun windowContext(app: Context, res: Resources): Context {
        val window = app.createWindowContext(TYPE_STATUS_BAR_SUB_PANEL, null)
        return object : ContextWrapper(window) {
            override fun getResources(): Resources = res
        }
    }

    private inner class Flow(private val context: Context, private val images: Images, private val task: PasswordTask) {
        private val prompts = passwordPrompts(task)
        // By PasswordPrompt's ordinal; zeroed and dropped as soon as they are of no more use.
        private val typed = arrayOfNulls<CharArray>(PasswordPrompt.entries.size)
        private var shown: android.app.Dialog? = null
        private var over = false

        fun ask(i: Int) {
            if (over) return
            if (i == prompts.size) return commit()
            val prompt = prompts[i]
            val box = PasswordDialog(context, images.text(label(prompt)), secure = true) { chars ->
                typed[prompt.ordinal] = chars
                val problem = when (prompt) {
                    PasswordPrompt.CURRENT -> null
                    PasswordPrompt.NEW -> passwordProblem(chars)
                    PasswordPrompt.CONFIRM -> confirmProblem(typed[PasswordPrompt.NEW.ordinal]!!, chars)
                }
                if (problem == null) return@PasswordDialog ask(i + 1)
                if (!problem.said) {
                    // OS 7 starts over, without a word (simulator, C).
                    forget(*PasswordPrompt.entries.toTypedArray())
                    return@PasswordDialog ask(0)
                }
                // The message, then the new password again, the current one kept (simulator, C).
                forget(PasswordPrompt.NEW, PasswordPrompt.CONFIRM)
                error(message(problem)) { ask(prompts.indexOf(PasswordPrompt.NEW)) }
            }
            box.setOnCancelListener { end() }
            show(box)
        }

        private fun commit() {
            val new = typed[PasswordPrompt.NEW.ordinal]
            val current = typed[PasswordPrompt.CURRENT.ordinal]
            worker.execute {
                val ok = try {
                    set(new, current)
                } catch (e: RuntimeException) {
                    // Android's own refusals (a policy, the lock screen feature off) land here.
                    fail("password: $task refused by Android", e)
                    false
                } finally {
                    forget(*PasswordPrompt.entries.toTypedArray())
                }
                main.post {
                    Log.i(TAG, "password: $task ${if (ok) "done" else "not done"}")
                    if (ok || over) end() else error(images.text(if (current != null) R.string.lock_incorrect_password else R.string.password_not_set)) { end() }
                }
            }
        }

        // Android's credentials copy the characters (bytes of their own, zeroed by close()).
        private fun set(new: CharArray?, current: CharArray?): Boolean {
            val newCredential = if (new == null) LockscreenCredential.createNone() else LockscreenCredential.createPassword(CharBuffer.wrap(new))
            val currentCredential = if (current == null) LockscreenCredential.createNone() else LockscreenCredential.createPassword(CharBuffer.wrap(current))
            try {
                // Simplification: SystemUI's user, the only one on this phone (as LockFace).
                return LockPatternUtils(context).setLockCredential(newCredential, currentCredential, Process.myUid() / 100_000)
            } finally {
                newCredential.close()
                currentCredential.close()
            }
        }

        private fun forget(vararg prompts: PasswordPrompt) {
            for (p in prompts) {
                typed[p.ordinal]?.fill('\u0000')
                typed[p.ordinal] = null
            }
        }

        /** The screen went off: whatever box is up goes, and with it what was typed. */
        fun abort() {
            if (over) return
            over = true
            shown?.dismiss()
            end()
        }

        private fun end() {
            over = true
            forget(*PasswordPrompt.entries.toTypedArray())
            if (flow === this) flow = null
        }

        /** OS 7's message with the exclamation mark and OK (sim-62, C); [then] once it closes. */
        private fun error(text: String, then: () -> Unit) =
            show(Bold7Dialog(context, text, listOf(images.text(R.string.password_ok)), DialogIcon.EXCLAMATION, onButton = { then() }, onCancel = then))

        private fun message(problem: PasswordProblem): String =
            images.text(if (problem == PasswordProblem.SEQUENCE) R.string.password_sequence else R.string.password_mismatch)

        private fun show(dialog: android.app.Dialog) {
            if (over) return
            dialog.window?.setType(TYPE_STATUS_BAR_SUB_PANEL)
            shown = dialog
            dialog.show()
        }
    }

    private companion object {
        /** WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL, hidden from the SDK: SystemUI's dialogs' type. */
        const val TYPE_STATUS_BAR_SUB_PANEL = 2017
    }

    private fun label(prompt: PasswordPrompt) = when (prompt) {
        PasswordPrompt.CURRENT -> R.string.password_current
        PasswordPrompt.NEW -> R.string.password_new
        PasswordPrompt.CONFIRM -> R.string.password_confirm
    }
}
