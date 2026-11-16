package dev.bold7.saisie

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import dev.bold7.platform.TypingContract

/**
 * Options' way to Typing's data (TypingContract): Word Learning on or off, every learned word
 * deleted, the personal Word Substitution list read and replaced. Nothing else: no learned word ever
 * leaves the app. Behind TypingContract.PERMISSION (signature), checked again here since call()
 * has no check of its own once the provider is reached.
 */
class OptionsProvider : ContentProvider() {
    private val store get() = (context!!.applicationContext as SaisieApp).store

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        context!!.enforceCallingPermission(TypingContract.PERMISSION, "Typing's options")
        return when (method) {
            TypingContract.READ -> Bundle().apply {
                val rows = store.rows()
                putBoolean(TypingContract.LEARNING, store.learning())
                putStringArray(TypingContract.REPLACE, rows.map { it.replace }.toTypedArray())
                putStringArray(TypingContract.WITH, rows.map { it.with }.toTypedArray())
                putBooleanArray(TypingContract.SMART, rows.map { it.smart }.toBooleanArray())
            }
            TypingContract.SET_LEARNING -> Bundle.EMPTY.also { store.setLearning(arg == "1") }
            TypingContract.FORGET -> Bundle.EMPTY.also { store.forget() }
            TypingContract.SET_SUBSTITUTIONS -> {
                val replace = extras?.getStringArray(TypingContract.REPLACE) ?: return null
                val with = extras.getStringArray(TypingContract.WITH) ?: return null
                val smart = extras.getBooleanArray(TypingContract.SMART) ?: return null
                if (replace.size != with.size || with.size != smart.size) return null
                store.setRows(replace.indices.map { UserEntry(replace[it], with[it], smart[it]) })
                Bundle.EMPTY
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
