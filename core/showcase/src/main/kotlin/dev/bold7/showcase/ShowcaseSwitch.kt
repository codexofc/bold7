package dev.bold7.showcase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings

/**
 * The global setting that turns the showcase on (`adb shell settings put global bold7_showcase 1`),
 * readable by every app and writable only by the shell or the system: no component of Bold7
 * accepts to set it. Removed by `settings delete`, tools/uninstall.sh and the module's removal.
 */
const val SHOWCASE_SETTING = "bold7_showcase"

/** Whether the showcase is on. Android's settings cache makes it a memory read after the first. */
fun showcaseOn(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, SHOWCASE_SETTING, 0) == 1

/** The video's event now ([ShowcaseEvent], set by the shell only); null while the showcase is off or none is set. */
const val SHOWCASE_EVENT_SETTING = "bold7_showcase_event"

fun showcaseEvent(context: Context): ShowcaseEvent? =
    if (!showcaseOn(context)) null else ShowcaseEvent.of(Settings.Global.getString(context.contentResolver, SHOWCASE_EVENT_SETTING))

/** The real source, or the showcase's when [on]: the real one is never built then. */
inline fun <T> pick(on: Boolean, real: () -> T, showcase: () -> T): T = if (on) showcase() else real()

private val AVATARS = intArrayOf(
    R.drawable.showcase_avatar_01, R.drawable.showcase_avatar_02, R.drawable.showcase_avatar_03, R.drawable.showcase_avatar_04,
    R.drawable.showcase_avatar_05, R.drawable.showcase_avatar_06, R.drawable.showcase_avatar_07, R.drawable.showcase_avatar_08,
    R.drawable.showcase_avatar_09, R.drawable.showcase_avatar_10, R.drawable.showcase_avatar_11, R.drawable.showcase_avatar_12,
    R.drawable.showcase_avatar_13, R.drawable.showcase_avatar_14, R.drawable.showcase_avatar_15, R.drawable.showcase_avatar_16,
)

/** Whether [uri] is a showcase avatar's ([Person.photo]). */
fun isAvatar(uri: String?): Boolean = uri?.startsWith(AVATAR_SCHEME) == true

/**
 * The avatar [uri] names ([Person.photo]) drawn [size] px square; null for any other URI. Drawn
 * from vectors made for Bold7, no photograph of anyone. Off the main thread, as any picture.
 */
fun avatar(context: Context, uri: String?, size: Int): Bitmap? {
    if (!isAvatar(uri)) return null
    val id = uri!!.removePrefix(AVATAR_SCHEME).toIntOrNull() ?: return null
    val res = AVATARS.getOrNull(id - 1) ?: return null
    val d = context.getDrawable(res) ?: return null
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
    }
}
