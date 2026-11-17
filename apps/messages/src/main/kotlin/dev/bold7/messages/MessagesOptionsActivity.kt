package dev.bold7.messages

import android.app.Activity
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import dev.bold7.design.ListColors
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.DialogIcon
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

/**
 * Messages Options (Messages > Menu > Options): "Messages Options" in an Options band, the rows of
 * sim-294 and sim-295 (C) that do something in Bold7: Message Display and Actions (Hide Sent
 * Messages, Confirm Delete, Confirm Mark Prior Read, guide 7.1 p. 87-89, B), Inbox Management
 * (a check box per mail account, then Text Messages, p. 87), Email Account Management (its
 * own screen) and Text Messaging (sim-295), which opens the settings of Google Messages, the engine of
 * the texts and of their chat features (D). The others (Email Preferences, Email Filters, Email
 * Reconciliation, Recent Contact Management, Spell Check, Emergency Alert Messages) change nothing
 * in Bold7 and are not there; no Search field above the three rows. Check boxes as Tasks Options
 * (sim-263, C), saved by Menu > Save, or Save when leaving with changes ("Changes made!", sim-47):
 * nothing but what the list shows changes, no message is deleted. Rows, words and form layout are
 * mine where no capture shows them (D).
 */
private const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"

class MessagesOptionsActivity : Activity() {
    private enum class Act { SWITCH, SAVE, CLOSE }

    private val graph get() = (application as MessagesApp).graph
    private var screen = OptionsScreen.LIST
    private var saved = MessagesOptions()
    private var options = saved
    private var accounts: List<MailAccountInfo> = emptyList()
    private var focus = 0
    private var menu: Menu<Act>? = null
    private val icons = arrayOfNulls<Bitmap>(4)

    private lateinit var title: TitleBar
    private lateinit var list: ListView
    private lateinit var form: ScrollView
    private lateinit var checks: LinearLayout
    private lateinit var menuView: MenuView<Act>
    private val rows = Rows()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        title = TitleBar(this, getString(R.string.options_title))
        list = bb10List(ListView(this), 0).apply {
            adapter = rows
            isFocusable = false
            setOnItemClickListener { _, _, position, _ -> focus = position; clicked() }
        }
        checks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ListColors.Background)
                setStroke(1, BOX_BORDER)
            }
            setPadding(1, 1, 1, 1)
        }
        form = ScrollView(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            isFillViewport = true
            addView(checks, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(5, 4, 5, 0) })
        }
        menuView = MenuView(
            this, glyph = { null },
            label = { getString(when (it) { Act.SWITCH -> R.string.act_switch_application; Act.SAVE -> R.string.button_save; Act.CLOSE -> R.string.mail_close }) },
            onEntry = ::chosen, onExpand = {}, onClose = { show(null) },
        )
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@MessagesOptionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(list, LinearLayout.LayoutParams(match, 0, 1f))
                addView(form, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        // No status bar of Android's: the 9900 shows the title bar alone (sim-294, C).
        window.hideStatusBar()
        render()
        // The options and the accounts, read off the main thread (the store's copy is already loaded).
        graph.ioThread.post {
            val read = graph.store.options
            val mail = graph.mail.accounts()?.list.orEmpty()
            val images = listOf("msg_cat_display", "msg_cat_inbox", "msg_cat_accounts", "msg_cat_texts").map { themeBitmap(applicationContext, it) }
            runOnUiThread {
                saved = read
                options = read
                accounts = mail
                images.forEachIndexed { i, b -> icons[i] = b }
                render()
            }
        }
    }

    // Back from Email Account Management: its accounts may have changed.
    override fun onRestart() {
        super.onRestart()
        graph.ioThread.post {
            val mail = graph.mail.accounts()?.list.orEmpty()
            runOnUiThread { accounts = mail; render() }
        }
    }

    private fun render() {
        list.visibility = if (screen == OptionsScreen.LIST) View.VISIBLE else View.GONE
        form.visibility = if (screen == OptionsScreen.LIST) View.GONE else View.VISIBLE
        title.bind(getString(R.string.options_title))
        rows.notifyDataSetChanged()
        if (screen == OptionsScreen.LIST) return
        val labels = when (screen) {
            OptionsScreen.DISPLAY -> listOf(R.string.opt_hide_sent, R.string.opt_confirm_delete, R.string.opt_confirm_prior).map(::getString) to options.displayChecks().map { it.second }
            else -> (accounts.map { it.address } + getString(R.string.opt_text_messages)) to options.inboxChecks(accounts.map { it.id })
        }
        while (checks.childCount < labels.first.size) checks.addView(CheckBoxView(this).apply {
            val at = checks.childCount
            setOnClickListener { focus = at; toggle() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CheckBoxView.HEIGHT))
        while (checks.childCount > labels.first.size) checks.removeViewAt(checks.childCount - 1)
        labels.first.forEachIndexed { i, label -> (checks.getChildAt(i) as CheckBoxView).bind(label, labels.second[i], lit = i == focus && menu == null) }
    }

    private fun count() = when (screen) {
        OptionsScreen.LIST -> 4
        OptionsScreen.DISPLAY -> 3
        OptionsScreen.INBOX -> accounts.size + 1
    }

    private fun open(to: OptionsScreen) {
        screen = to
        focus = 0
        render()
    }

    private fun clicked() {
        when (screen) {
            OptionsScreen.LIST -> when (focus) {
                0 -> open(OptionsScreen.DISPLAY)
                1 -> open(OptionsScreen.INBOX)
                2 -> if (!locked()) startActivity(Intent(this, MailAccountsActivity::class.java))
                else -> if (!locked()) textSettings()
            }
            else -> toggle()
        }
    }

    private fun toggle() {
        options = options.toggled(screen, focus, accounts.map { it.id })
        render()
    }

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
        render()
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (act) {
            Act.SWITCH -> SwitchApplication.start(this)
            Act.SAVE -> save()
            Act.CLOSE -> leave()
        }
    }

    /** Escape or Close: a form with changes asks first ("Changes made!", sim-47, C); a form without goes back to the list, the list leaves. */
    private fun leave() {
        if (screen == OptionsScreen.LIST) return finish()
        if (options == saved) return open(OptionsScreen.LIST)
        Bold7Dialog(this, getString(R.string.changes_made), listOf(getString(R.string.button_save), getString(R.string.button_discard), getString(R.string.button_cancel)), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> { options = saved; open(OptionsScreen.LIST) }
            }
        }).show()
    }

    private fun save() {
        val o = options
        graph.ioThread.post {
            graph.store.update(o)
            graph.feed.reload()
            runOnUiThread { saved = o; if (screen != OptionsScreen.LIST) open(OptionsScreen.LIST) }
        }
    }

    /** Text Messaging: Google Messages' own settings (its notification preferences entry, exported), chats and RCS among them. */
    private fun textSettings() {
        val settings = Intent(Intent.ACTION_MAIN).addCategory(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES).setPackage(GOOGLE_MESSAGES)
        try {
            startActivity(settings)
        } catch (e: ActivityNotFoundException) {
            Log.w("Bold7/MessagesOptions", "Google Messages has no settings to open", e)
            status(this, getString(R.string.no_options))
        }
    }

    private fun locked() = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return if (menu != null) true else super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        if (click && e.repeatCount > 0) return true
        val m = menu
        if (m != null) {
            val key = when {
                code == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                code == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                click -> MenuKey.CLICK
                back -> MenuKey.BACK
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
            back -> leave()
            code == KeyEvent.KEYCODE_MENU -> show(Menu(listOf(MenuEntry(Act.SAVE), MenuEntry(Act.CLOSE)), highlighted = 0).withSwitchApplication(Act.SWITCH))
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                focus = (focus + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, count() - 1)
                render()
                if (screen == OptionsScreen.LIST) list.setSelection(focus)
            }
            click || code == KeyEvent.KEYCODE_SPACE -> clicked()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private inner class Rows : BaseAdapter() {
        private val texts = listOf(
            R.string.options_display to R.string.options_display_hint,
            R.string.options_inbox to R.string.options_inbox_hint,
            R.string.accounts_title to R.string.options_accounts_hint,
            R.string.options_texts to R.string.options_texts_hint,
        )

        override fun getCount() = texts.size
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? ListItemView) ?: ListItemView(this@MessagesOptionsActivity)).apply {
                bind(getString(texts[position].first), getString(texts[position].second), lit = position == focus && menu == null, icon = icons[position])
            }
    }

    private companion object {
        const val BOX_BORDER = 0xFFC6C6C6.toInt()
    }
}
