package dev.bold7.pictures

import android.app.Activity
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

// What the menus do to a picture, at the edge of Android. Blocking calls: off the main thread,
// except the intents, which the activity starts.

/** Delete: MediaStore's request, which MANAGE_MEDIA's app-op grants without Android's dialog; OS 7's question comes first. */
fun deleteRequest(context: Context, p: Picture): PendingIntent =
    MediaStore.createDeleteRequest(context.contentResolver, listOf(Store.uri(p.id)))

/** Set As Wallpaper (guide 7.1 p. 153, B): the picture as the home's and the lock screen's wallpaper; false when it could not be. */
fun writeWallpaper(context: Context, p: Picture): Boolean = try {
    context.contentResolver.openInputStream(Store.uri(p.id))?.use { WallpaperManager.getInstance(context).setStream(it) } != null
} catch (e: IOException) {
    Log.w(TAG, "wallpaper not set: ${e.javaClass.simpleName}")
    false
} catch (e: SecurityException) {
    Log.w(TAG, "wallpaper not set", e)
    false
}

/** Select Contact of Bold7's Contacts (SELECT_CONTACT, signature): its result is the contact's URI. */
fun selectContact(): Intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI).setClassName("dev.bold7.contacts", "dev.bold7.contacts.SelectContactActivity")

/**
 * Assign To Contact (ps 33262, sim-370, C): the picture, its centre square (D: OS 7 lets a crop box
 * move, guide p. 193), becomes the photo of every raw contact of [contact]; false when nothing was written.
 */
fun writeContactPhoto(context: Context, store: Store, p: Picture, contact: Uri): Boolean {
    val resolver = context.contentResolver
    val id = runCatching { ContentUris.parseId(contact) }.getOrNull() ?: return false
    val raws = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ?", arrayOf(id.toString()), null)
        ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
    if (raws.isEmpty()) return false
    // The provider keeps up to 720 px (its display photo); a square of that side at most.
    val full = store.picture(p, 1440) ?: return false
    val side = minOf(full.width, full.height)
    val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
    val photo = if (side > 720) Bitmap.createScaledBitmap(square, 720, 720, true) else square
    return try {
        raws.all { raw ->
            val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
            resolver.openAssetFileDescriptor(uri, "rw")?.use { fd -> fd.createOutputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) } } == true
        }
    } catch (e: IOException) {
        Log.w(TAG, "contact picture not written: ${e.javaClass.simpleName}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "contact picture not written", e)
        false
    }
}

/** Send > Text Message: a new text of Bold7's Messages with the picture attached (its Compose, COMPOSE_TEXT, signature), the picture readable by it. */
fun textIntent(p: Picture): Intent = Intent(Intent.ACTION_SEND)
    .setClassName("dev.bold7.messages", "dev.bold7.messages.Compose")
    .setType(p.mime)
    .putExtra(Intent.EXTRA_STREAM, Store.uri(p.id))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/**
 * The Camera row: the phone's camera app (D: the Q25's, which OS 7's Camera stands for), Bold7's
 * Camera first ([BOLD7_CAMERA]): with Aperture and BenOS Camera beside it, the bare intent asks
 * which one.
 */
fun camera(pkg: String? = null): Intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage(pkg)

/** Bold7's fork of BenOS Camera, which the module installs. */
const val BOLD7_CAMERA = "dev.bold7.camera.benos"

/** Starts [intent], false when no app takes it or it is refused. */
fun Activity.startSafely(intent: Intent, request: Int? = null): Boolean = try {
    if (request == null) startActivity(intent) else startActivityForResult(intent, request)
    true
} catch (e: android.content.ActivityNotFoundException) {
    Log.w(TAG, "nothing for ${intent.action}")
    false
} catch (e: SecurityException) {
    Log.w(TAG, "refused: ${intent.component}", e)
    false
}
