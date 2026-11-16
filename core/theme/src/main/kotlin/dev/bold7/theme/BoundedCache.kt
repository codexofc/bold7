package dev.bold7.theme

/**
 * Least recently used first out, past [budget] (in the unit of [sizeOf]: bytes of the decoded
 * images). android.util.LruCache does the same, but has no JVM test. Not thread-safe: its owner
 * locks.
 */
class BoundedCache<K, V : Any>(private val budget: Long, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var size = 0L
        private set

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.remove(key)?.let { size -= sizeOf(it) }
        val s = sizeOf(value)
        if (s > budget) return
        map[key] = value
        size += s
        val it = map.entries.iterator()
        while (size > budget && it.hasNext()) {
            size -= sizeOf(it.next().value)
            it.remove()
        }
    }
}
