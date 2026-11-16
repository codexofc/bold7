package dev.bold7.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
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
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.setContentUnderBar
import dev.bold7.ui.status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Speed Dial Numbers list of OS 7 (guide 7.1 p. 65-66, B; its look D, no capture: Options'
 * rows under a title): every letter key, its contact or number, W the voice mail's. A free key's
 * click asks Select Contact for its number; an assigned key's opens its menu, Call, Edit, Move,
 * Delete. Opened by View > Speed Dial List, or by Add Speed Dial ([adding]): then a free key's
 * click takes the number and the list closes. Decisions in [SpeedDial.click] and [speedMenu].
 */
class SpeedDialActivity : Activity() {
    companion object {
        private const val EXTRA_NUMBER = "dev.bold7.phone.SPEED_NUMBER"
        private const val EXTRA_NAME = "dev.bold7.phone.SPEED_NAME"
        private const val EXTRA_KEY = "dev.bold7.phone.SPEED_KEY"
        private const val PICK = 1
        private const val STATE_CHOOSING = "choosing"

        /** The list, to give [n] a key (Add Speed Dial of the Phone screen). */
        fun adding(context: Context, n: SpeedNumber): Intent =
            Intent(context, SpeedDialActivity::class.java).putExtra(EXTRA_NUMBER, n.number).putExtra(EXTRA_NAME, n.name)

        /** The list, straight to Select Contact for [key] (a free letter held on the home or in Messages, guide p. 65). */
        fun assigning(context: Context, key: Char): Intent =
            Intent(context, SpeedDialActivity::class.java).putExtra(EXTRA_KEY, key.toString())
    }

    private val graph get() = (application as PhoneApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var list: ListView
    private lateinit var menuView: MenuView<SpeedAct>

    private var dial = SpeedDial()
    private var rows = dial.rows()
    private var focus = 0
    private var pending: Pending? = null
    private var menu: Menu<SpeedAct>? = null
    private var held = false

    /** The key whose number Select Contact is choosing (a free key's click, or Edit). */
    private var choosing: Char? = null
    private val adapter = Rows()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        pending = intent.getStringExtra(EXTRA_NUMBER)?.let { Pending.Add(SpeedNumber(it, intent.getStringExtra(EXTRA_NAME).orEmpty())) }
        choosing = savedInstanceState?.getChar(STATE_CHOOSING)?.takeIf { it != '\u0000' }
        list = bb10List(ListView(this), 0).apply {
            adapter = this@SpeedDialActivity.adapter
            isFocusable = false
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                moveTo(position)
                click()
            }
            setOnItemLongClickListener { _, _, position, _ ->
                moveTo(position)
                show(Menu(entries(), line = 0, highlighted = 0))
                true
            }
        }
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        setContentUnderBar(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@SpeedDialActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@SpeedDialActivity, getString(R.string.speed_dial_title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(list, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        scope = CoroutineScope(SupervisorJob() + graph.main)
        scope.launch { set(withContext(graph.io) { graph.speedDial.load() }) }
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_KEY)?.singleOrNull()?.let(::pick)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        choosing?.let { outState.putChar(STATE_CHOOSING, it) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun set(d: SpeedDial) {
        dial = d
        rows = d.rows()
        adapter.notifyDataSetChanged()
    }

    /** [d] kept, written off the main thread. */
    private fun keep(d: SpeedDial) {
        set(d)
        scope.launch { withContext(graph.io) { graph.speedDial.save(d) } }
    }

    private fun moveTo(i: Int) {
        focus = i.coerceIn(0, rows.size - 1)
        list.invalidateViews()
        if (focus < list.firstVisiblePosition || focus > list.lastVisiblePosition) list.setSelection(focus)
    }

    private fun row() = rows[focus]

    private fun click() {
        when (val c = dial.click(row().key, pending)) {
            is SpeedClick.Changed -> {
                val adding = pending is Pending.Add
                pending = null
                keep(c.dial)
                if (adding) finish()
            }
            is SpeedClick.Choose -> pick(c.key)
            SpeedClick.Menu -> show(Menu(entries(), highlighted = 0))
            SpeedClick.None -> Unit
        }
    }

    private fun pick(key: Char) {
        choosing = key
        val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            .setClassName(PhoneActivity.CONTACTS, PhoneActivity.SELECT_CONTACT)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, PICK)
        } catch (e: ActivityNotFoundException) {
            status(this, getString(R.string.no_contacts_app))
        } catch (e: SecurityException) {
            status(this, getString(R.string.no_contacts_app))
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val key = choosing ?: return
        choosing = null
        val uri = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || uri == null) return
        scope.launch {
            val picked = withContext(graph.io) { graph.log.picked(uri.toString()) } ?: return@launch
            keep(dial.assign(key, picked))
        }
    }

    // Menus

    private fun entries() = speedMenu(row()).map { MenuEntry(it) }

    private fun show(m: Menu<SpeedAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun label(a: SpeedAct) = getString(
        when (a) {
            SpeedAct.CALL -> R.string.act_call
            SpeedAct.EDIT -> R.string.act_edit
            SpeedAct.MOVE -> R.string.act_move
            SpeedAct.DELETE -> R.string.act_delete
            SpeedAct.ASSIGN -> R.string.act_add_speed_dial
        },
    )

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        val r = row()
        when (a) {
            SpeedAct.CALL -> call(r)
            SpeedAct.EDIT, SpeedAct.ASSIGN -> pick(r.key)
            // Guide p. 66: "In the Speed Dial Numbers list, click a new speed dial key."
            SpeedAct.MOVE -> pending = Pending.Move(r.key)
            // D: no confirmation, the guide names none.
            SpeedAct.DELETE -> keep(dial.delete(r.key))
        }
    }

    private fun call(r: SpeedRow) = scope.launch {
        if (r.voiceMail) {
            when (withContext(graph.io) { graph.log.voiceMail() }) {
                VoiceMailResult.PLACED -> finish()
                VoiceMailResult.NO_NUMBER -> status(this@SpeedDialActivity, getString(R.string.no_voice_mail))
                VoiceMailResult.REFUSED -> status(this@SpeedDialActivity, getString(R.string.call_refused))
            }
            return@launch
        }
        val n = r.number ?: return@launch
        if (withContext(graph.io) { graph.log.place(n.number) }) finish() else status(this@SpeedDialActivity, getString(R.string.call_refused))
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            if (held) held = false else if (menu == null) click()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            when (val r = m.key(
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                    KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                    else -> if (click) MenuKey.CLICK else return true
                },
            )) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        when {
            code == KeyEvent.KEYCODE_DPAD_UP -> moveTo(focus - 1)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> moveTo(focus + 1)
            code == KeyEvent.KEYCODE_MENU -> show(Menu(entries(), highlighted = 0))
            code == KeyEvent.KEYCODE_CALL -> if (e.repeatCount == 0) call(row())
            // Escape gives up a move first (D), then closes.
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE ->
                if (pending is Pending.Move) pending = null else return super.dispatchKeyEvent(e)
            click -> if (e.isLongPress || e.repeatCount == 1) {
                held = true
                show(Menu(entries(), line = 0, highlighted = 0))
            }
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? ListItemView ?: ListItemView(this@SpeedDialActivity)
            val r = rows[position]
            val key = r.key.uppercaseChar().toString()
            val n = r.number
            val title = when {
                r.voiceMail -> getString(R.string.speed_key, key, getString(R.string.voice_mail))
                n == null -> key
                else -> getString(R.string.speed_key, key, n.name.ifEmpty { n.number })
            }
            v.bind(title, if (n != null && n.name.isNotEmpty()) n.number else "", lit = position == focus)
            return v
        }
    }
}
