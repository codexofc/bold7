package dev.bold7.messages

import android.content.Context
import android.net.Uri
import dev.bold7.hub.data.DEFAULT_DISABLED_SOURCES
import dev.bold7.hub.data.SOURCES_AUTHORITY

/**
 * What Messages keeps for itself: its options and the keys of its saved messages,
 * in one private file, not backed up. The Hub's database is not touched (no migration): a saved
 * message is its Hub item named here, and the Hub never forgets an item, deleted ones included.
 * Read once on the io thread ([load], first thing the graph posts there); the main thread only
 * reads the copies, so it never waits for the disk. Every write is on io.
 */
class MessagesStore(private val context: Context) {
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
            hiddenApps = prefs.getStringSet("hidden_apps", emptySet()).orEmpty().toSet(),
            disabledSources = disabledSources(),
        )
        saved = prefs.getStringSet("saved", emptySet()).orEmpty().toSet()
    }

    /**
     * The sources turned off, read from the file itself: SourcesProvider asks on a binder thread,
     * maybe before [load]. Never saved yet: Signal alone ([DEFAULT_DISABLED_SOURCES]).
     */
    fun disabledSources(): Set<String> = prefs.getStringSet("disabled_sources", null)?.toSet() ?: DEFAULT_DISABLED_SOURCES

    fun update(o: MessagesOptions) {
        val sourcesChanged = o.disabledSources != options.disabledSources
        options = o
        prefs.edit()
            .putBoolean("hide_sent", o.hideSent)
            .putBoolean("confirm_delete", o.confirmDelete)
            .putBoolean("confirm_mark_prior_read", o.confirmMarkPriorRead)
            .putBoolean("show_texts", o.showTexts)
            .putBoolean("group", o.group)
            .putStringSet("hidden_accounts", o.hiddenAccounts)
            .putStringSet("hidden_apps", o.hiddenApps)
            .putStringSet("disabled_sources", o.disabledSources)
            .commit()
        // The Hub reads them again (HubGraph.watchSourceChoice), once they are on disk.
        if (sourcesChanged) context.contentResolver.notifyChange(Uri.parse("content://$SOURCES_AUTHORITY"), null)
    }

    fun save(key: String) = setSaved(saved + key)

    fun unsave(key: String) = setSaved(saved - key)

    private fun setSaved(keys: Set<String>) {
        saved = keys
        prefs.edit().putStringSet("saved", keys).apply()
    }
}
