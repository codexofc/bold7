package dev.bold7.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * The images of OS 7 by name: `drawable("home_banner_background")`.
 *
 * First from [root], the folder the module mounts read-only, laid out as assets-gen/ (images under
 * `<block>/res/drawable-nodpi/`, [ImageIndex]), the 9-patches read from their border
 * ([NinePatchBorder]); else, in development, from the APK's compiled resource of the same name;
 * else an empty image, said once in the log (a public APK before its images are extracted).
 *
 * Every file is listed and decoded on [io], never on the main thread: [preload] starts the decodes
 * early (an Application's onCreate), and a main thread that asks for an image not decoded yet
 * waits for it (another thread decodes it itself). Decoded images are kept in a [BoundedCache] of
 * [budget] bytes; each call hands out a new drawable over the shared bitmap, as Resources does.
 */
class ThemeImages(
    private val context: Context,
    private val root: File,
    private val io: Executor,
    budget: Long,
) {
    private class Image(val bitmap: Bitmap, val chunk: ByteArray?, val padding: Rect?)

    private val lock = Any()
    private val cache = BoundedCache<String, Image>(budget) { it.bitmap.allocationByteCount }
    private val pending = HashMap<String, FutureTask<Image?>>()
    private val missing = HashSet<String>()
    private val index = FutureTask { listIndex() }.also(io::execute)

    /** Starts decoding [names] on [io], without waiting. */
    fun preload(names: Collection<String>) = names.forEach { task(it) }

    /** A new drawable of [name]: a NinePatchDrawable for a 9-patch, a BitmapDrawable otherwise. */
    fun drawable(name: String): Drawable {
        val img = image(name) ?: return resource(name) ?: empty(name)
        val res = context.resources
        return if (img.chunk != null) NinePatchDrawable(res, img.bitmap, img.chunk, img.padding, name) else BitmapDrawable(res, img.bitmap)
    }

    /** The bitmap of [name] (sprites and icons drawn by hand), shared: never to be changed. */
    fun bitmap(name: String): Bitmap = image(name)?.bitmap
        ?: (resource(name) as? BitmapDrawable)?.bitmap
        ?: run { empty(name); Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    private fun image(name: String): Image? {
        require(ImageIndex.isName(name)) { "not an image name: $name" }
        synchronized(lock) { cache[name]?.let { return it } }
        // The listing is io's first task: waited for once, then a name the folder lacks costs a map lookup.
        if (name !in index.get()) return null
        val t = task(name)
        // The main thread only waits; any other thread (io included) decodes it itself if not started.
        if (Looper.myLooper() != Looper.getMainLooper()) t.run()
        return t.get()
    }

    private fun task(name: String): FutureTask<Image?> = synchronized(lock) {
        pending.getOrPut(name) {
            FutureTask { decode(name) }.also(io::execute)
        }
    }

    private fun decode(name: String): Image? {
        val path = index.get()[name]
        val img = path?.let { runCatching { read(File(root, it)) }.onFailure { e -> Log.w(TAG, "$it: ${e.message}") }.getOrNull() }
        synchronized(lock) {
            pending.remove(name)
            if (img != null) cache.put(name, img)
        }
        return img
    }

    private fun read(file: File): Image {
        val full = requireNotNull(BitmapFactory.decodeFile(file.path)) { "not an image" }
        if (!file.name.endsWith(".9.png")) return Image(full.apply { density = Bitmap.DENSITY_NONE }, null, null)
        val px = IntArray(full.width * full.height)
        full.getPixels(px, 0, full.width, 0, 0, full.width, full.height)
        val spec = requireNotNull(NinePatchBorder.read(px, full.width, full.height)) { "9-patch without marks" }
        val inner = Bitmap.createBitmap(full, 1, 1, full.width - 2, full.height - 2).apply { density = Bitmap.DENSITY_NONE }
        full.recycle()
        val (l, t, r, b) = spec.padding.toList()
        return Image(inner, NinePatchBorder.chunk(spec), Rect(l, t, r, b))
    }

    private fun listIndex(): Map<String, String> {
        val paths = root.listFiles()?.flatMap { block ->
            File(block, "res/drawable-nodpi").list()?.map { "${block.name}/res/drawable-nodpi/$it" }.orEmpty()
        }.orEmpty()
        return ImageIndex.of(paths).also { Log.i(TAG, "${it.size} images in $root") }
    }

    /** The APK's own compiled image of [name] (development), read by Resources. */
    private fun resource(name: String): Drawable? {
        // Names are the resources' own; the lookup by name is the point (the R field is not compiled in a public APK).
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id == 0) null else context.getDrawable(id)
    }

    private fun empty(name: String): Drawable {
        if (synchronized(lock) { missing.add(name) }) Log.w(TAG, "no image $name in $root nor in the APK")
        return ColorDrawable(Color.TRANSPARENT)
    }

    companion object {
        private const val TAG = "Bold7Theme"

        /** Where the module mounts the extracted images; the module mounts it. */
        val ROOT = File("/product/media/bold7")

        @Volatile private var shared: ThemeImages? = null

        /**
         * The process's store: one thread of its own, an eighth of the heap at most.
         * Simplification: a process singleton, because the components of core/ui open their images from
         * a Context alone; an app graph that needs another executor builds its own [ThemeImages].
         */
        fun of(context: Context): ThemeImages = shared ?: synchronized(this) {
            shared ?: ThemeImages(
                context.applicationContext ?: context,
                ROOT,
                Executors.newSingleThreadExecutor { r -> Thread(r, "bold7-theme").apply { isDaemon = true } },
                Runtime.getRuntime().maxMemory() / 8,
            ).also { shared = it }
        }
    }
}
