package dev.bold7.messages

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import dev.bold7.design.ListColors
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.status
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.themeBitmap
import java.io.IOException

/** What the picker lists, read through MediaStore (READ_MEDIA_*, granted by tools/install.sh). */
object MediaReader {
    fun permission(kind: AttachKind): String? = when (kind) {
        AttachKind.PICTURE -> Manifest.permission.READ_MEDIA_IMAGES
        AttachKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
        AttachKind.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
        AttachKind.FILE -> null
    }

    private fun collection(kind: AttachKind): Uri? = when (kind) {
        AttachKind.PICTURE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        AttachKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        AttachKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        AttachKind.FILE -> null
    }

    /** Whether the picker can read [kind]: it is granted, else the system's picker is the way (File too: a text file is not in MediaStore). */
    fun readable(context: Context, kind: AttachKind): Boolean =
        permission(kind)?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: false

    /** Every item of [kind], off the main thread. */
    fun read(context: Context, kind: AttachKind): List<MediaItem> {
        val base = collection(kind) ?: return emptyList()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH)
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(base, cols, "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += MediaItem(ContentUris.withAppendedId(base, c.getLong(0)).toString(), name, c.getString(2).orEmpty(), c.getLong(3), c.getString(4).orEmpty().trim('/'))
            }
        }
        return out
    }
}

/**
 * Attach's file picker: Picture, Video and Audio of Attach open the phone's media
 * as folders and files in OS 7's list (Options' rows, sim-57, C: the theme's icons, the trackpad's
 * focus), a picture's or a video's own thumbnail in place of its icon; the click on a file
 * answers its MediaStore URI, the click on a folder goes in, Escape goes up and, at the root,
 * cancels (D: no capture of OS 7's file explorer). The listing and the thumbnails are read off the
 * main thread, the thumbnails in a cache of 48. Not exported, never over the lock screen.
 */
class PickerActivity : Activity() {
    companion object {
        private const val EXTRA_KIND = "kind"
        private const val THUMB = 52

        fun intent(context: Context, kind: AttachKind): Intent = Intent(context, PickerActivity::class.java).putExtra(EXTRA_KIND, kind.name)
    }

    private enum class Act { SWITCH, CLOSE }

    private val graph get() = (application as MessagesApp).graph
    private lateinit var kind: AttachKind
    private var items: List<MediaItem>? = null
    private var rows: List<PickerRow> = emptyList()
    private var nav = PickerNav()
    private var menu: Menu<Act>? = null
    private val thumbs = LruCache<String, Bitmap>(48)
    private val asked = HashSet<String>()
    private var gone = false
    private lateinit var folder: Bitmap
    private val kindIcons = HashMap<AttachIcon, Bitmap>()

    private lateinit var title: TitleBar
    private lateinit var list: ListView
    private lateinit var menuView: MenuView<Act>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { AttachKind.valueOf(it) }.getOrNull() } ?: return finish()
        if (locked() || !MediaReader.readable(this, kind)) return finish()
        title = TitleBar(this, kindLabel())
        list = bb10List(ListView(this), 0).apply {
            adapter = Rows()
            isFocusable = false
            setOnItemClickListener { _, _, position, _ -> nav = nav.focused(position); clicked() }
        }
        menuView = MenuView(
            this, glyph = { null },
            label = { getString(if (it == Act.SWITCH) R.string.act_switch_application else R.string.mail_close) },
            onEntry = ::chosen, onExpand = {}, onClose = { show(null) },
        )
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@PickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(list, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        graph.ioThread.post {
            val read = MediaReader.read(this, kind)
            folder = themeBitmap(applicationContext, "msg_folder")
            for ((icon, res) in ICONS) kindIcons[icon] = themeBitmap(applicationContext, res)
            runOnUiThread {
                if (gone) return@runOnUiThread
                items = read
                render()
                if (read.isEmpty()) status(this, getString(R.string.picker_empty))
            }
        }
    }

    override fun onDestroy() {
        gone = true
        super.onDestroy()
    }

    private fun kindLabel() = getString(when (kind) {
        AttachKind.PICTURE -> R.string.attach_picture
        AttachKind.VIDEO -> R.string.attach_video
        AttachKind.AUDIO -> R.string.attach_audio
        AttachKind.FILE -> R.string.attach_file
    })

    private fun render() {
        rows = browse(items.orEmpty(), nav.dir)
        title.bind(pickerTitle(kindLabel(), nav.dir))
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        list.setSelection(nav.focus)
    }

    private fun clicked() {
        if (locked()) return finish()
        when (val r = rows.getOrNull(nav.focus)) {
            is PickerRow.Folder -> { nav = nav.into(r.path); render() }
            is PickerRow.File -> {
                setResult(RESULT_OK, Intent().setData(Uri.parse(r.item.uri)))
                finish()
            }
            null -> Unit
        }
    }

    /** Escape: one folder up, at the root the picker closes. */
    private fun back() {
        val up = nav.up() ?: return finish()
        nav = up
        render()
    }

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        if (act == Act.SWITCH) SwitchApplication.start(this) else finish()
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // A thumbnail not yet read is asked once, off the main thread; the list redraws when it is there.
    private fun want(item: MediaItem) {
        if (kind == AttachKind.AUDIO || !asked.add(item.uri)) return
        graph.ioThread.post {
            val b = try {
                contentResolver.loadThumbnail(Uri.parse(item.uri), Size(THUMB, THUMB), null)
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            if (b != null) runOnUiThread { if (!gone) { thumbs.put(item.uri, b); (list.adapter as BaseAdapter).notifyDataSetChanged() } }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return if (menu != null) true else super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val esc = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        if (click && e.repeatCount > 0) return true
        val m = menu
        if (m != null) {
            val key = when {
                code == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                code == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                click -> MenuKey.CLICK
                esc -> MenuKey.BACK
                code == KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        when {
            esc -> back()
            code == KeyEvent.KEYCODE_MENU -> show(Menu(listOf(MenuEntry(Act.CLOSE)), highlighted = 0).withSwitchApplication(Act.SWITCH))
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                nav = nav.focused((nav.focus + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                list.setSelection(nav.focus)
            }
            click -> clicked()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? ListItemView) ?: ListItemView(this@PickerActivity)).apply {
                val lit = position == nav.focus && menu == null
                when (val r = rows[position]) {
                    is PickerRow.Folder -> bind(r.name, resources.getQuantityString(R.plurals.picker_items, r.count, r.count), lit, folder)
                    is PickerRow.File -> {
                        val i = r.item
                        val thumb = thumbs.get(i.uri) ?: kindIcons[iconOf(i.type)].also { want(i) }
                        bind(i.name, sizeText(i.size), lit, thumb)
                    }
                }
            }
    }

    private val ICONS = mapOf(
        AttachIcon.PICTURE to "msg_attachment_generic_picture",
        AttachIcon.VIDEO to "msg_attachment_generic_video",
        AttachIcon.MUSIC to "msg_attachment_music",
        AttachIcon.UNKNOWN to "msg_attachment_unknown",
    )
}
