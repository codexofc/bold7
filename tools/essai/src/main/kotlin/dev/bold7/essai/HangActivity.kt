package dev.bold7.essai

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView

/**
 * Blocks its main thread for 30 s, one second after it shows, so that Android finds it not
 * responding: a key or a tap during the block gives the
 * dialog 5 s later, and Wait lets the app come back once the block ends:
 * `su -c 'am start -n dev.bold7.essai/.HangActivity'`.
 */
class HangActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Blocked for 30 s in 1 s: tap the screen." })
        window.decorView.postDelayed({ SystemClock.sleep(30_000) }, 1_000)
    }
}
