package dev.bold7.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * The files picked for Attach copied into Messages' cache, served to Google
 * Messages by AttachmentProvider: the picker's grant ends with the activity, and a picture too
 * large for the carrier is shrunk here. Blocking: on MessagesGraph's thread only. Nothing read is
 * logged, not even a name.
 */
class AndroidAttachmentStore(private val context: Context) : AttachmentStore {
    private val resolver = context.contentResolver
    private val root = AttachmentProvider.root(context)

    /** A copy left by a process gone with its field: nothing shows it any more. */
    fun clear() {
        root.deleteRecursively()
    }

    /** The default SMS subscription's carrier limits (SmsManager's carrier config, read only); AOSP's when unreadable. */
    fun limits(): MmsLimits = try {
        val sms = context.getSystemService(SmsManager::class.java).createForSubscriptionId(SubscriptionManager.getDefaultSmsSubscriptionId())
        val c = sms.carrierConfigValues
        val d = MmsLimits.DEFAULT
        MmsLimits(
            c.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE).takeIf { it > 0 } ?: d.bytes,
            c.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH).takeIf { it > 0 } ?: d.width,
            c.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT).takeIf { it > 0 } ?: d.height,
        )
    } catch (e: RuntimeException) {
        Log.w(TAG, "carrier config unreadable: ${e.javaClass.name}")
        MmsLimits.DEFAULT
    }

    override fun take(source: String): Taken {
        val src = Uri.parse(source)
        val dir = File(root, UUID.randomUUID().toString())
        return try {
            val type = resolver.getType(src)?.lowercase()
            if (type == null || !attachable(type)) return Taken.Refused(Notice.NOT_ATTACHABLE)
            val (name, size) = described(src)
            val limits = limits()
            if (!dir.mkdirs()) throw IOException("no folder")
            val image = type.startsWith("image/")
            val bounds = if (image) bounds(src) else null
            val decision = fit(type, size, bounds?.first ?: 0, bounds?.second ?: 0, limits)
            val file = when {
                decision == Fit.TOO_LARGE -> null
                decision == Fit.SHRINK -> bounds?.let { shrink(src, it, limits, File(dir, safeName(name.substringBeforeLast('.')) + ".jpg")) }
                else -> File(dir, safeName(name)).takeIf { copy(src, it, limits.budget.toLong()) }
            }
            if (file == null) {
                dir.deleteRecursively()
                return Taken.Refused(if (image && bounds == null) Notice.NOT_ATTACHED else Notice.TOO_LARGE)
            }
            val out = if (decision == Fit.SHRINK) "image/jpeg" else type
            Taken.Ok(Attachment(AttachmentProvider.uri(dir.name, file.name).toString(), out, name, file.length()))
        } catch (e: IOException) {
            fail(dir, e)
        } catch (e: RuntimeException) {
            // Refused (a grant gone), provider gone, a picture too big to decode.
            fail(dir, e)
        } catch (e: OutOfMemoryError) {
            fail(dir, e)
        }
    }

    private fun fail(dir: File, e: Throwable): Taken {
        Log.w(TAG, "attachment not copied: ${e.javaClass.name}")
        dir.deleteRecursively()
        return Taken.Refused(Notice.NOT_ATTACHED)
    }

    override fun drop(attachment: Attachment) {
        AttachmentProvider.file(context, Uri.parse(attachment.uri))?.parentFile?.deleteRecursively()
    }

    /** Its name and size (-1: unknown), as the picker's provider gives them. */
    private fun described(src: Uri): Pair<String, Long> =
        resolver.query(src, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "attachment"
            name to (if (c.isNull(1)) -1L else c.getLong(1))
        } ?: ("attachment" to -1L)

    private fun open(src: Uri): InputStream = resolver.openInputStream(src) ?: throw IOException("no stream")

    /** A picture's sides as it shows (its EXIF orientation applied); null: not a picture Android decodes. */
    private fun bounds(src: Uri): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(src).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return if (rotation(src) % 180 == 0) o.outWidth to o.outHeight else o.outHeight to o.outWidth
    }

    /** The EXIF orientation's turn (a mirrored one taken as its turn, D). */
    private fun rotation(src: Uri): Int = try {
        when (open(src).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    /** At most [cap] bytes copied to [to]; more, nothing kept and false. */
    private fun copy(src: Uri, to: File, cap: Long): Boolean {
        var total = 0L
        open(src).use { input ->
            to.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > cap) return false
                    out.write(buffer, 0, n)
                }
            }
        }
        return true
    }

    /**
     * The picture in JPEG at the first of shrinkSteps that fits the budget, its orientation applied
     * (quality 85, D); null when even the smallest does not.
     */
    private fun shrink(src: Uri, sides: Pair<Int, Int>, limits: MmsLimits, to: File): File? {
        val steps = shrinkSteps(sides.first, sides.second, limits)
        val first = steps.first()
        // Decoded a power of two smaller, still at least the first step's size.
        var sample = 1
        while (sides.first / (sample * 2) >= first.first && sides.second / (sample * 2) >= first.second) sample *= 2
        val decoded = open(src).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val degrees = rotation(src)
        val upright = if (degrees == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (upright !== decoded) decoded.recycle()
        val bytes = ByteArrayOutputStream()
        try {
            for ((w, h) in steps) {
                val scaled = Bitmap.createScaledBitmap(upright, w, h, true)
                bytes.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
                if (scaled !== upright) scaled.recycle()
                if (bytes.size() <= limits.budget) {
                    to.outputStream().use { bytes.writeTo(it) }
                    return to
                }
            }
            return null
        } finally {
            upright.recycle()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
