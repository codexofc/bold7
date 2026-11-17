package dev.bold7.launcher.options

import android.app.Activity
import android.app.BroadcastOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What Options > Security > Password and Display > Message on Lock Screen read and write. Android
 * keeps both in LockSettings, which wants ACCESS_KEYGUARD_SECURE_STORAGE (signature|setup): no
 * allowlist grants it to the launcher, SystemUI holds it. So dev.bold7.sysui answers in SystemUI's
 * process ([SystemLockSettings]); a fake in the tests.
 */
interface LockSettings {
    /** Whether a password locks the phone. */
    fun passwordSet(): Boolean

    /** The owner's name and information shown on the lock screen, null when SystemUI does not answer. */
    fun lockMessage(): Pair<String, String>?

    /** Writes them; false when SystemUI does not answer. */
    fun setLockMessage(name: String, info: String): Boolean
}

/**
 * [LockSettings] through dev.bold7.sysui (PasswordScreen there): broadcasts to SystemUI that its
 * receivers take only from a holder of LOCK_SETTINGS (signature, this app's), this app's
 * identity shared. Without Bold7's SystemUI module nobody answers. Blocks on the answer: io only.
 */
internal class SystemLockSettings(private val context: Context) : LockSettings {
    override fun passwordSet() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun lockMessage() = ask(Intent(LOCK_MESSAGE_ACTION))

    override fun setLockMessage(name: String, info: String) =
        ask(Intent(LOCK_MESSAGE_ACTION).putExtra(OWNER_NAME_EXTRA, name).putExtra(OWNER_INFO_EXTRA, info)) != null

    private fun ask(intent: Intent): Pair<String, String>? {
        val done = CountDownLatch(1)
        var answer: Pair<String, String>? = null
        context.sendOrderedBroadcast(intent.setPackage(SYSTEMUI), null, identity(), object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val extras = getResultExtras(false)
                if (resultCode == Activity.RESULT_OK && extras != null) {
                    answer = extras.getString(OWNER_NAME_EXTRA).orEmpty() to extras.getString(OWNER_INFO_EXTRA).orEmpty()
                }
                done.countDown()
            }
        }, answers, Activity.RESULT_CANCELED, null, null)
        // Simplification: 2 s and nobody answered (module off): read as absent.
        done.await(2, TimeUnit.SECONDS)
        return answer
    }

    companion object {
        // dev.bold7.sysui's Password.kt: its actions, extras and tasks.
        private const val SYSTEMUI = "com.android.systemui"
        private const val PASSWORD_ACTION = "dev.bold7.action.DEVICE_PASSWORD"
        private const val PASSWORD_TASK_EXTRA = "task"
        private const val LOCK_MESSAGE_ACTION = "dev.bold7.action.LOCK_MESSAGE"
        private const val OWNER_NAME_EXTRA = "name"
        private const val OWNER_INFO_EXTRA = "info"
        const val SET = "SET"
        const val CHANGE = "CHANGE"
        const val REMOVE = "REMOVE"

        // The answers arrive here, never on the io thread that waits for them.
        private val answers by lazy { Handler(HandlerThread("bold7-lock-settings").apply { start() }.looper) }

        private fun identity() = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()

        /**
         * Set Password, Change Password, Enable: SystemUI asks the passwords in OS 7's boxes over
         * Options and hands them to Android itself; nothing typed passes through the launcher.
         */
        fun password(context: Context, task: String) =
            context.sendBroadcast(Intent(PASSWORD_ACTION).setPackage(SYSTEMUI).putExtra(PASSWORD_TASK_EXTRA, task), null, identity())
    }
}
