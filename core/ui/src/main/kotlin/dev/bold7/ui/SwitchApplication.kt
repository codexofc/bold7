package dev.bold7.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

// Switch Application, the first row of every full menu of OS 7 (sim-04,
// sim-155, sim-182b, C): written once here, the apps only add their own action to their menu.

/**
 * [menu] with Switch Application ([action], the app's own value) alone in its group at the top of
 * a full menu; the focus follows its entry. The short menu is returned as it is (D: the apps'
 * short menus keep their own cells).
 */
fun <A> Menu<A>.withSwitchApplication(action: A): Menu<A> {
    if (short) return this
    val rows = entries.mapIndexed { i, e -> if (i == 0) e.copy(separatorAbove = true) else e }
    return copy(entries = listOf(MenuEntry(action)) + rows, highlighted = highlighted?.plus(1))
}

/** What the launcher's switcher takes from an app: its signature permission and its action. */
object SwitchApplication {
    const val PERMISSION = "dev.bold7.permission.SWITCH_APPLICATION"
    const val ACTION = "dev.bold7.launcher.SWITCH_APPLICATION"
    private const val LAUNCHER = "dev.bold7.launcher"

    /** Opens the launcher's switcher over the app. Never throws: without the launcher or the permission, nothing opens. */
    fun start(context: Context) {
        try {
            context.startActivity(Intent(ACTION).setPackage(LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("Bold7/Switch", "no switcher", e)
        } catch (e: SecurityException) {
            Log.w("Bold7/Switch", "switcher refused", e)
        }
    }
}
