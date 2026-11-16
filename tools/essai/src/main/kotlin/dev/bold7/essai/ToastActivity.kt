package dev.bold7.essai

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Shows the text toast of extra "text" (Android's own Toast.makeText, as any app), long with
 * extra "long", then goes away:
 * `su -c 'am start -n dev.bold7.essai/.ToastActivity --es text "…" --ez long true'`.
 */
class ToastActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val long = intent.getBooleanExtra("long", false)
        Toast.makeText(this, intent.getStringExtra("text") ?: "Message saved as draft.", if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        finish()
    }
}
