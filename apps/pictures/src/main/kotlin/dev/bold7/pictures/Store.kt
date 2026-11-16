package dev.bold7.pictures

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import android.util.Log
import android.util.Size
import java.io.IOException

internal const val TAG = "Bold7/Pictures"

/**
 * MediaStore's pictures, read only here (writes are Actions.kt's). Every call reads the disk or a
 * provider: off the main thread. The made-up pictures of `--ei rows N` (tools/perf.sh) never touch
 * MediaStore: negative ids, drawn tiles.
 */
class Store(private val resolver: ContentResolver) {
    private val columns = arrayOf(
        Media._ID, Media.DISPLAY_NAME, Media.BUCKET_ID, Media.BUCKET_DISPLAY_NAME, Media.RELATIVE_PATH,
        Media.DATE_TAKEN, Media.DATE_MODIFIED, Media.WIDTH, Media.HEIGHT, Media.ORIENTATION, Media.MIME_TYPE, Media.SIZE,
    )

    /** Every picture of the phone's storage; empty when MediaStore refuses (no permission). */
    fun all(): List<Picture> = try {
        resolver.query(Media.EXTERNAL_CONTENT_URI, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    // DATE_TAKEN is empty for a picture without EXIF: its file's date (seconds) then.
                    val taken = if (c.isNull(5)) c.getLong(6) * 1000 else c.getLong(5)
                    add(Picture(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(),
                        taken, c.getInt(7), c.getInt(8), c.getInt(9), c.getString(10) ?: "image/jpeg", c.getLong(11)))
                }
            }
        }.orEmpty()
    } catch (e: SecurityException) {
        Log.w(TAG, "pictures not readable", e)
        emptyList()
    }

    /** A picture's square thumbnail, [side] px; null when it cannot be read. */
    fun thumbnail(p: Picture, side: Int): Bitmap? {
        if (p.id < 0) return madeUp(p.id, side)
        return try {
            resolver.loadThumbnail(uri(p.id), Size(side, side), null)
        } catch (e: IOException) {
            Log.w(TAG, "no thumbnail for ${p.id}: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The whole picture, its longer side at most [max] px (memory bounded), its EXIF turn applied by the decoder; null when unreadable. */
    fun picture(p: Picture, max: Int): Bitmap? {
        if (p.id < 0) return madeUp(p.id, max)
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri(p.id))) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longer = maxOf(w, h)
                if (longer > max) d.setTargetSize(w * max / longer, h * max / longer)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            Log.w(TAG, "picture ${p.id} unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    // A made-up picture: a tile of one colour, as cheap to draw as a small decode.
    private fun madeUp(id: Long, side: Int): Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(Color.HSVToColor(floatArrayOf((-id * 37 % 360).toFloat(), 0.6f, 0.8f)))
    }

    companion object {
        fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        /** [n] made-up pictures (tools/perf.sh's `--ei rows N`), nothing read. */
        fun demo(n: Int, now: Long): List<Picture> = List(n) { i ->
            Picture(-(i + 1L), "Picture %03d.jpg".format(i + 1), -1, "Demo", "Pictures/Demo/", now - i * 60_000L, 720, 720)
        }
    }
}
