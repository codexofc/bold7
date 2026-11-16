package dev.bold7.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * A letter held on the home or in Messages (guide 7.1 p. 65): the Phone of Bold7 calls the number
 * assigned to it, the voice mail for W, or offers to assign the key. The numbers stay in the
 * Phone (SpeedDialStore): nothing is read here, only the letter is handed over, through the
 * Phone's PromptActivity, exported behind CALL_PROMPT (signature), which the caller must request.
 */
object SpeedDialKey {
    const val ACTION = "dev.bold7.phone.SPEED_DIAL"
    const val EXTRA_KEY = "dev.bold7.phone.KEY"
    private const val PHONE = "dev.bold7.phone"

    /** Never throws: without the Phone or the permission, nothing happens. */
    fun call(context: Context, letter: Char) {
        try {
            context.startActivity(Intent(ACTION).setPackage(PHONE).putExtra(EXTRA_KEY, letter.lowercaseChar().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("Bold7/SpeedDial", "no phone", e)
        } catch (e: SecurityException) {
            Log.w("Bold7/SpeedDial", "speed dial refused", e)
        }
    }
}
