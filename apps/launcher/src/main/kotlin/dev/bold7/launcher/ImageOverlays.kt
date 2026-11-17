package dev.bold7.launcher

import android.content.om.FabricatedOverlay
import android.content.om.OverlayManagerTransaction
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import dev.bold7.theme.ImageIndex
import dev.bold7.theme.NinePatchBorder
import dev.bold7.theme.NinePatchSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.system.exitProcess

/**
 * The images of OS 7's framework dialogs, menus and lists, put by one fabricated overlay of
 * `android` on the framework drawables that the RROs (system/overlays/android, permissioncontroller)
 * name instead of images of their own: the RROs carry no image, the images come from the folder the
 * module extracts (/product/media/bold7, laid out as assets-gen).
 *
 * Run as root by system/module's overlays.sh, never by the launcher's process (only root or the
 * shell may fabricate an overlay); the launcher is its owner because idmap2 lets only a preinstalled
 * owner overlay the framework, and its removal takes the overlay with it:
 *
 *     CLASSPATH=<launcher apk> app_process / dev.bold7.launcher.ImageOverlays register <images> <staging>
 *     CLASSPATH=<launcher apk> app_process / dev.bold7.launcher.ImageOverlays unregister
 *
 * The overlay copies each image into its .frro (/data/resource-cache), so <staging> holds them only
 * while registering. Exit status: the number of images not put (0 when all are).
 */
@Keep
object ImageOverlays {
    const val OWNER = "dev.bold7.launcher"
    const val NAME = "images"

    /**
     * Each host and its image. Hosts are public framework drawables (their ids are the SDK's on the
     * Q25, where every private drawable is one off: an RRO compiled against android.jar can name
     * only public ones) that nothing of the framework uses (no style, no drawable of framework-res
     * names them; legacy frames of API 1) and no framework <bitmap> or <nine-patch> wraps: a
     * fabricated file is read only when its resource is loaded as a drawable itself
     * (ResourcesImpl.loadDrawableForCookie), never through a <bitmap src>. Apps that name one get
     * OS 7's image instead of Android's legacy one.
     */
    val HOSTS = linkedMapOf(
        "alert_dark_frame" to "bold7_box",
        "menu_frame" to "bold7_menu",
        "editbox_dropdown_dark_frame" to "bold7_inplace",
        "menuitem_background" to "bold7_chosen",
        "btn_dialog" to "bold7_button",
        "title_bar_tall" to "bold7_button_focus",
        "checkbox_off_background" to "bold7_check_off",
        "checkbox_on_background" to "bold7_check_on",
        "button_onoff_indicator_off" to "bold7_check_off_focus",
        "button_onoff_indicator_on" to "bold7_check_on_focus",
        "radiobutton_off_background" to "bold7_radio_off",
        "radiobutton_on_background" to "bold7_radio_on",
        "status_bar_item_background" to "bold7_radio_off_focus",
        "status_bar_item_app_background" to "bold7_radio_on_focus",
        // the wait indicator's twelve frames (drawable/wait_bold7.xml of the RRO) and
        // the text selection handle. Status and menu icons of API 1 to 11 that no resource of
        // framework-res, SystemUI, Settings, PermissionController or Launcher3 names (aapt2 dump of
        // LineageOS 24's), presence_* not among those ContactsContract.StatusUpdates hands out.
        // For BenOS: no binary XML nor resource table of android-34's framework-res, of the
        // MtkSystemUI and of the Launcher3 of stubs/device holds their ids; BenOS's own
        // framework-res, Settings and PermissionController not read.
        "presence_audio_away" to "bold7_wait_00",
        "presence_audio_busy" to "bold7_wait_01",
        "presence_audio_online" to "bold7_wait_02",
        "presence_video_away" to "bold7_wait_03",
        "presence_video_busy" to "bold7_wait_04",
        "presence_video_online" to "bold7_wait_05",
        "zoom_plate" to "bold7_wait_06",
        "dialog_frame" to "bold7_wait_07",
        "editbox_dropdown_light_frame" to "bold7_wait_08",
        "stat_sys_vp_phone_call" to "bold7_wait_09",
        "stat_sys_vp_phone_call_on_hold" to "bold7_wait_10",
        "ic_menu_upload_you_tube" to "bold7_wait_11",
        "ic_menu_always_landscape_portrait" to "bold7_handle",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val failures = when {
            args.size == 3 && args[0] == "register" -> register(File(args[1]), File(args[2]))
            args.size == 1 && args[0] == "unregister" -> unregister()
            else -> 1.also { System.err.println("usage: register <images> <staging> | unregister") }
        }
        exitProcess(failures)
    }

    private fun register(images: File, staging: File): Int {
        val paths = images.listFiles()?.flatMap { block ->
            File(block, "res/drawable-nodpi").list()?.map { "${block.name}/res/drawable-nodpi/$it" }.orEmpty()
        }.orEmpty()
        val index = ImageIndex.of(paths)
        val overlay = newOverlay()
        staging.mkdirs()
        val open = ArrayList<ParcelFileDescriptor>()
        var failures = 0
        try {
            for ((host, image) in HOSTS) {
                val path = index[image]
                val bytes = path?.let { runCatching { compiled(File(images, it)) }.getOrNull() }
                if (bytes == null) {
                    failures++
                    println("! $image: ${if (path == null) "not in $images" else "unreadable"}, $host stays Android's")
                    continue
                }
                // idmap2d reads the file through its descriptor, and only one it may read.
                val file = File(staging, "$image.png").apply { writeBytes(bytes) }
                setFileContext(file.path, "u:object_r:apk_data_file:s0")
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also(open::add)
                // nodpi: drawn at its own size, as from drawable-nodpi (the Q25 is 208 dpi).
                overlay.setResourceValue("android:drawable/$host", fd, "nodpi")
            }
            if (failures == HOSTS.size) return failures
            commit(OverlayManagerTransaction.newInstance().apply { registerFabricatedOverlay(overlay) })
            println("registered $OWNER:$NAME, ${HOSTS.size - failures} images")
        } finally {
            open.forEach { it.close() }
            staging.deleteRecursively()
        }
        return failures
    }

    private fun unregister(): Int {
        commit(OverlayManagerTransaction.newInstance().apply { unregisterFabricatedOverlay(newOverlay().identifier) })
        println("unregistered $OWNER:$NAME")
        return 0
    }

    /** The file as the overlay holds it: a .9.png as aapt2 compiles it (border cut, npTc and npLb chunks), any other as is. */
    private fun compiled(file: File): ByteArray {
        if (!file.name.endsWith(".9.png")) return file.readBytes()
        // Unpremultiplied all the way, so the image is written back with its own pixels.
        val full = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPremultiplied = false }))
        val w = full.width
        val h = full.height
        val px = IntArray(w * h).also { full.getPixels(it, 0, w, 0, 0, w, h) }
        val spec = requireNotNull(NinePatchBorder.read(px, w, h)) { "9-patch without marks" }
        val inner = Bitmap.createBitmap(w - 2, h - 2, Bitmap.Config.ARGB_8888).apply {
            isPremultiplied = false
            setPixels(px, w + 1, w, 0, 0, w - 2, h - 2)
        }
        val png = ByteArrayOutputStream().also { inner.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val chunks = listOfNotNull("npTc" to ninePatchChunk(spec), layoutBounds(px, w, h)?.let { "npLb" to it })
        return withChunks(png, chunks)
    }

    /** Res_png_9patch as a PNG file holds it (big-endian; the offsets are recomputed on reading), without region colors. */
    fun ninePatchChunk(spec: NinePatchSpec): ByteArray {
        val nx = spec.xDivs.size
        val ny = spec.yDivs.size
        val b = ByteBuffer.allocate(32 + 4 * (nx + ny))
        b.put(0).put(nx.toByte()).put(ny.toByte()).put(0)
        b.putInt(0).putInt(0)
        val (l, t, r, bottom) = spec.padding.toList()
        b.putInt(l).putInt(r).putInt(t).putInt(bottom)
        b.putInt(0)
        spec.xDivs.forEach { b.putInt(it) }
        spec.yDivs.forEach { b.putInt(it) }
        return b.array()
    }

    /**
     * The optical insets of a source .9.png of [width] x [height], border included (aapt2's layout
     * bounds: opaque red ticks from the ends of the bottom and right lines), as the npLb chunk
     * holds them (left, top, right, bottom, little-endian: aapt2 writes them as they are in
     * memory, and the decoder reads them so; big-endian ones came out byte-swapped on the Q25);
     * null without any.
     */
    fun layoutBounds(pixels: IntArray, width: Int, height: Int): ByteArray? {
        val red = 0xFFFF0000.toInt()
        fun ticks(n: Int, at: (Int) -> Int) = (0 until n).takeWhile { pixels[at(it)] == red }.count()
        val w = width - 2
        val h = height - 2
        val bottom = (height - 1) * width + 1
        val left = ticks(w) { bottom + it }
        val right = ticks(w) { bottom + w - 1 - it }
        val top = ticks(h) { (1 + it) * width + width - 1 }
        val under = ticks(h) { (h - it) * width + width - 1 }
        if (left == 0 && right == 0 && top == 0 && under == 0) return null
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(left).putInt(top).putInt(right).putInt(under).array()
    }

    /** [png] with [chunks] (type to data) inserted after its IHDR chunk. */
    fun withChunks(png: ByteArray, chunks: List<Pair<String, ByteArray>>): ByteArray {
        val afterHeader = 8 + 4 + 4 + 13 + 4 // signature, then IHDR: length, type, data, CRC
        val out = ByteArrayOutputStream()
        out.write(png, 0, afterHeader)
        for ((type, data) in chunks) {
            val body = type.toByteArray(Charsets.US_ASCII) + data
            val crc = CRC32().apply { update(body) }.value.toInt()
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            out.write(body)
            out.write(ByteBuffer.allocate(4).putInt(crc).array())
        }
        out.write(png, afterHeader, png.size - afterHeader)
        return out.toByteArray()
    }

    // Hidden framework members, by reflection: this module does not compile against stubs/framework.jar.

    private fun newOverlay(): FabricatedOverlay {
        val builder = Class.forName("android.content.om.FabricatedOverlay\$Builder")
            .getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(OWNER, NAME, "android")
        return builder.javaClass.getMethod("build").invoke(builder) as FabricatedOverlay
    }

    private fun commit(tx: OverlayManagerTransaction) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "overlay")
        val om = Class.forName("android.content.om.IOverlayManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        Class.forName("android.content.om.IOverlayManager").getMethod("commit", OverlayManagerTransaction::class.java).invoke(om, tx)
    }

    private fun setFileContext(path: String, context: String) {
        val ok = Class.forName("android.os.SELinux").getMethod("setFileContext", String::class.java, String::class.java).invoke(null, path, context)
        check(ok == true) { "cannot label $path $context" }
    }
}
