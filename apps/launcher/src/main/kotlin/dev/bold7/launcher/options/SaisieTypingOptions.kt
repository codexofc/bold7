package dev.bold7.launcher.options

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import dev.bold7.platform.TypingContract

/**
 * Typing's provider (apps/saisie OptionsProvider) through ContentResolver.call, with Bold7's
 * signature permission TYPING_OPTIONS. Absent (Typing not installed) or refused, the reads give
 * null and the writes false, logged without any of the data. Blocks on binder: io only.
 */
internal class SaisieTypingOptions(private val context: Context) : TypingOptions {
    private val uri = Uri.parse("content://${TypingContract.AUTHORITY}")

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle? = try {
        context.contentResolver.call(uri, method, arg, extras)
    } catch (e: RuntimeException) {
        // IllegalArgumentException for an unknown authority, SecurityException without the permission.
        Log.w(TAG, "Typing's $method refused: ${e.javaClass.simpleName}")
        null
    }

    override fun read(): TypingData? {
        val b = call(TypingContract.READ) ?: return null
        val replace = b.getStringArray(TypingContract.REPLACE) ?: return null
        val with = b.getStringArray(TypingContract.WITH) ?: return null
        val smart = b.getBooleanArray(TypingContract.SMART) ?: return null
        if (replace.size != with.size || with.size != smart.size) return null
        return TypingData(b.getBoolean(TypingContract.LEARNING), replace.indices.map { Substitution(replace[it], with[it], smart[it]) })
    }

    override fun setLearning(on: Boolean) = call(TypingContract.SET_LEARNING, if (on) "1" else "0") != null

    override fun forget() = call(TypingContract.FORGET) != null

    override fun setSubstitutions(rows: List<Substitution>) = call(
        TypingContract.SET_SUBSTITUTIONS,
        extras = Bundle().apply {
            putStringArray(TypingContract.REPLACE, rows.map { it.replace }.toTypedArray())
            putStringArray(TypingContract.WITH, rows.map { it.with }.toTypedArray())
            putBooleanArray(TypingContract.SMART, rows.map { it.smart }.toBooleanArray())
        },
    ) != null

    private companion object {
        const val TAG = "Bold7/Options"
    }
}
