package dev.bold7.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

// Help of OS 7's full menus: the row just before Close (sim-442, C), which opens the Help app on the
// app's topic (sim-443, C). Written once here, as Switch Application; the home has none (sim-441, C).

/**
 * [menu] with Help ([action], the app's own value) just before the entry [before] (its Close), in its
 * group, or last when there is none; the focus follows its entry. A short menu is returned as it is.
 */
fun <A> Menu<A>.withHelp(action: A, before: A): Menu<A> {
    if (short) return this
    val at = entries.indexOfFirst { it.action == before }.takeIf { it >= 0 } ?: entries.size
    val close = entries.getOrNull(at)
    val rows = entries.toMutableList()
    if (close != null) rows[at] = close.copy(separatorAbove = false)
    rows.add(at, MenuEntry(action, separatorAbove = close?.separatorAbove == true))
    return copy(entries = rows, highlighted = highlighted?.let { if (it >= at) it + 1 else it })
}

/** What the Help app (apps/help) takes from an app: its signature permission, its action and the topic. */
object Help {
    const val PERMISSION = "dev.bold7.permission.HELP"
    const val ACTION = "dev.bold7.help.SHOW"
    const val EXTRA_TOPIC = "dev.bold7.help.TOPIC"
    private const val PACKAGE = "dev.bold7.help"

    /** Opens Help on [topic] (null: its contents). Never throws: without the app or the permission, nothing opens. */
    fun open(context: Context, topic: String? = null) {
        try {
            context.startActivity(Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_TOPIC, topic).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("Bold7/Help", "no Help app", e)
        } catch (e: SecurityException) {
            Log.w("Bold7/Help", "Help refused", e)
        }
    }
}
