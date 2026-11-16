package dev.bold7.messages

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.util.Log

/**
 * One icon per mail account on the home (gsm 020, C: an envelope with a globe per account beside
 * Messages): a dynamic shortcut of Messages per account, its id the Hub's account
 * id, which the launcher holding HOME lists through LauncherApps and draws with OS 7's icon of a
 * mail account and the star of its new mail. It opens Messages on that account (gsm 029). No icon
 * here: the launcher draws its own. Blocking binder calls: MessagesGraph's thread only.
 */
object AccountShortcuts {
    fun publish(context: Context, accounts: List<MailAccountInfo>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val now = manager.dynamicShortcuts.associate { it.id to it.shortLabel?.toString() }
        if (now == accounts.associate { it.id to it.address }) return // nothing changed: no call, no rate limit spent
        val shortcuts = accounts.map { a ->
            ShortcutInfo.Builder(context, a.id)
                .setShortLabel(a.address)
                .setLongLabel(a.address)
                .setIntent(Intent(Intent.ACTION_MAIN).setClassName(context, MessagesActivity::class.java.name).putExtra(EXTRA_MAIL_ACCOUNT, a.id))
                .build()
        }
        try {
            manager.dynamicShortcuts = shortcuts
        } catch (e: RuntimeException) {
            // Rate limited (Messages in the background) or refused: the next opening tries again.
            Log.w(TAG, "account icons not published: ${e.javaClass.name}")
        }
    }
}
