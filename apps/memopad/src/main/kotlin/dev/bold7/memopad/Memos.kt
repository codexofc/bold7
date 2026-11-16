package dev.bold7.memopad

import dev.bold7.showcase.Showcase
import dev.bold7.showcase.pick

/** The memos the screens read and write: the app's database ([MemoStore]) or the showcase's. Off the main thread. */
interface Memos {
    fun all(): List<Memo>

    fun get(id: Long): Memo?

    /** Inserts a memo ([id] null) or rewrites it; the id, or null when refused. */
    fun save(id: Long?, title: String, body: String): Long?

    fun delete(id: Long): Boolean
}

/** The showcase's memos ([showcase] on) or the [real] ones; the real source is not even built then. */
fun memosFor(showcase: Boolean, now: Long = System.currentTimeMillis(), real: () -> Memos): Memos =
    pick(showcase, real) { ShowcaseMemos(now) }

/** The made-up memos of the showcase, in memory: a save of a shown memo pretends, nothing is ever written. */
class ShowcaseMemos(now: Long) : Memos {
    private val memos = Showcase.memos.map { Memo(it.id, it.title, it.body, now - it.minutesAgo * 60_000L) }

    override fun all() = memos

    override fun get(id: Long) = memos.firstOrNull { it.id == id }

    override fun save(id: Long?, title: String, body: String) = id

    override fun delete(id: Long) = false
}
