package dev.bold7.showcase

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.sin

/** A made-up picture of the showcase's library: [folder] is its MediaStore bucket name, [path] its relative path. */
data class ShowcasePicture(val index: Int, val name: String, val folder: String, val path: String, val minutesAgo: Int)

/** The folders of the made-up library and their counts: Camera 12, Screenshots 5, Downloads 3, Wallpapers 8. */
val showcasePictures: List<ShowcasePicture> = buildList {
    fun add(n: Int, folder: String, path: String, name: (Int) -> String) {
        repeat(n) { add(ShowcasePicture(size, name(it + 1), folder, path, 90 + size * 1440)) }
    }
    add(12, "Camera", "DCIM/Camera/") { "IMG_%04d.jpg".format(it) }
    add(5, "Screenshots", "Pictures/Screenshots/") { "Screenshot_%02d.png".format(it) }
    add(3, "Downloads", "Download/") { "Download_%02d.jpg".format(it) }
    add(8, "Wallpapers", "Pictures/Wallpapers/") { "Wallpaper_%02d.jpg".format(it) }
}

private val SKIES = arrayOf(
    intArrayOf(0xFF1B2A5C.toInt(), 0xFFE8743B.toInt(), 0xFFF9D56E.toInt()),
    intArrayOf(0xFF2E6FB7.toInt(), 0xFF8EC5E8.toInt(), 0xFFEAF6FB.toInt()),
    intArrayOf(0xFF0B0F2B.toInt(), 0xFF2B2E6E.toInt(), 0xFF6A4C93.toInt()),
    intArrayOf(0xFF7A3E65.toInt(), 0xFFE0707A.toInt(), 0xFFFFC38B.toInt()),
    intArrayOf(0xFF1F7A8C.toInt(), 0xFF7FC8C0.toInt(), 0xFFF3E9B5.toInt()),
)
private val LAND = intArrayOf(0xFF1E3D2F.toInt(), 0xFF2F5D45.toInt(), 0xFF4F7F5A.toInt(), 0xFF8AAE6B.toInt())

/**
 * The made-up picture [index] drawn [w] by [h]: a sky gradient, a sun or a moon, three ranges of
 * hills. Pure code, no photograph, no file: the same index always gives the same picture.
 */
fun showcaseScene(index: Int, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val sky = SKIES[index % SKIES.size]
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, 0f, h * 0.75f, sky, floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    p.color = if (index % SKIES.size == 2) 0xFFF4F1DE.toInt() else 0xFFFFF3C4.toInt()
    c.drawCircle(w * (0.2f + 0.6f * ((index * 37) % 100) / 100f), h * 0.3f, h * 0.09f, p)
    for (layer in 0 until 3) {
        p.color = LAND[(layer + index / SKIES.size) % LAND.size]
        val base = h * (0.55f + 0.14f * layer)
        val amp = h * (0.12f - 0.03f * layer)
        val phase = index * 1.7f + layer * 2.3f
        val path = Path().apply {
            moveTo(0f, h.toFloat())
            var x = 0
            while (x <= w) {
                lineTo(x.toFloat(), base - amp * (0.5f + 0.5f * sin(phase + x * (3f + layer) / w)))
                x += 4
            }
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        c.drawPath(path, p)
    }
    return bmp
}
