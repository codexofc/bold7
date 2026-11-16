package dev.bold7.saisie

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * What Typing keeps, and nothing else: the learned
 * words and their counts ([Learned]), Word Learning off or on, the user's own Word Substitution list.
 * Three files of the app's private credential-encrypted storage ([dir]: filesDir; Typing is never
 * directBootAware), no backup nor transfer (the manifest), never logged.
 *
 * Read and written on its own thread, never the typing one: the typing thread only reads [words]
 * and [user] and adds to [words], whose writing out waits [SAVE_DELAY_MS] for the next words. Options'
 * calls (OptionsProvider, binder threads) and the file work hold this object's lock.
 */
class TypingStore(private val dir: () -> File) {
    private val handler = Handler(HandlerThread("bold7-saisie-store", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    private var loaded = false
    private var rows = emptyList<UserEntry>()

    /** The learned words while Word Learning is on and read; null otherwise (nothing learned nor read). */
    @Volatile var words: Learned? = null
        private set

    /** The user's own list. */
    @Volatile var user: Substitutions = Substitutions.NONE
        private set

    private val save = Runnable { synchronized(this) { words?.let { write(LEARNED, it.text()) } } }

    init {
        handler.post { synchronized(this) { load() } }
    }

    /** From the typing thread: one more use of [word], written out a little later. */
    fun learn(word: String) {
        val w = words ?: return
        if (!w.learn(word)) return
        handler.removeCallbacks(save)
        handler.postDelayed(save, SAVE_DELAY_MS)
    }

    @Synchronized
    fun learning(): Boolean {
        load()
        return !file(OFF).exists()
    }

    /** Off: the words leave memory (kept in the file, as OS 7 keeps its custom dictionary); on: read again. */
    @Synchronized
    fun setLearning(on: Boolean) {
        load()
        if (on) {
            file(OFF).delete()
            if (words == null) words = Learned.parse(read(LEARNED))
        } else {
            flush()
            file(OFF).createNewFile()
            words = null
        }
    }

    /** Every learned word gone, from memory and from the file. */
    @Synchronized
    fun forget() {
        load()
        handler.removeCallbacks(save)
        AtomicFile(file(LEARNED)).delete()
        words?.clear()
    }

    @Synchronized
    fun rows(): List<UserEntry> {
        load()
        return rows
    }

    @Synchronized
    fun setRows(list: List<UserEntry>) {
        load()
        val text = UserEntry.text(list)
        write(USER, text)
        rows = UserEntry.rows(text)
        user = Substitutions.parse(text)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = read(USER)
        rows = UserEntry.rows(text)
        user = Substitutions.parse(text)
        if (!file(OFF).exists()) words = Learned.parse(read(LEARNED))
    }

    private fun flush() {
        if (handler.hasCallbacks(save)) {
            handler.removeCallbacks(save)
            words?.let { write(LEARNED, it.text()) }
        }
    }

    private fun file(name: String) = File(dir(), name)

    private fun read(name: String) = try {
        String(AtomicFile(file(name)).readFully(), Charsets.UTF_8)
    } catch (e: IOException) {
        "" // absent: nothing kept yet
    }

    private fun write(name: String, text: String) {
        val f = AtomicFile(file(name))
        val out = try { f.startWrite() } catch (e: IOException) { return failed(name, e) }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: IOException) {
            f.failWrite(out)
            failed(name, e)
        }
    }

    /** The file's name and the error only, never what was in it. */
    private fun failed(name: String, e: IOException) {
        Log.w(TAG, "$name not written: ${e.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "Bold7/Typing"
        const val LEARNED = "learned.tsv"
        const val USER = "substitution.tsv"
        /** Present: Word Learning off (sim-92a's Typing box cleared). */
        const val OFF = "learning-off"
        const val SAVE_DELAY_MS = 5_000L
    }
}
