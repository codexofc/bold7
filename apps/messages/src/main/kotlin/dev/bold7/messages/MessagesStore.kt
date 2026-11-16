package dev.bold7.messages

import android.content.Context

/**
 * What Messages keeps for itself: its options and the keys of its saved messages,
 * in one private file, not backed up. The Hub's database is not touched (no migration): a saved
 * message is its Hub item named here, and the Hub never forgets an item, deleted ones included.
 * Read once on the io thread ([load], first thing the graph posts there); the main thread only
 * reads the copies, so it never waits for the disk. Every write is on io.
 */
class MessagesStore(context: Context) {
    private val prefs = context.getSharedPreferences("messages", Context.MODE_PRIVATE)

    @Volatile var options = MessagesOptions()
        private set

    @Volatile var saved: Set<String> = emptySet()
        private set

    fun load() {
        options = MessagesOptions(
            hideSent = prefs.getBoolean("hide_sent", false),
            confirmDelete = prefs.getBoolean("confirm_delete", true),
            confirmMarkPriorRead = prefs.getBoolean("confirm_mark_prior_read", true),
            showTexts = prefs.getBoolean("show_texts", true),
            hiddenAccounts = prefs.getStringSet("hidden_accounts", emptySet()).orEmpty().toSet(),
            group = prefs.getBoolean("group", true),
        )
        saved = prefs.getStringSet("saved", emptySet()).orEmpty().toSet()
    }

    fun update(o: MessagesOptions) {
        options = o
        prefs.edit()
            .putBoolean("hide_sent", o.hideSent)
            .putBoolean("confirm_delete", o.confirmDelete)
            .putBoolean("confirm_mark_prior_read", o.confirmMarkPriorRead)
            .putBoolean("show_texts", o.showTexts)
            .putBoolean("group", o.group)
            .putStringSet("hidden_accounts", o.hiddenAccounts)
            .apply()
    }

    fun save(key: String) = setSaved(saved + key)

    fun unsave(key: String) = setSaved(saved - key)

    private fun setSaved(keys: Set<String>) {
        saved = keys
        prefs.edit().putStringSet("saved", keys).apply()
    }
}
