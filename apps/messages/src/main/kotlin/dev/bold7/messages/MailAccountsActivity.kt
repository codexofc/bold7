package dev.bold7.messages

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import dev.bold7.design.Focus
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.hub.data.MailResult
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.useCaret
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.ListItemView
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.themeImage

/**
 * Email Account Management (Messages > Options, guide 7.1 p. 90, B): the accounts of
 * apps/hub, a row each, then "Internet Mail Account", which opens Email Setup ("follow the screens
 * to set up your email address", B): the address and its app password (decision D4 of
 * apps/hub), the servers only when apps/hub does not know the domain, then Next; apps/hub logs
 * in to both servers before it keeps anything, and the screen says the confirmation (B) or why
 * not. Gmail and Outlook can also sign in through the system's browser (OAuth):
 * apps/hub gives the page and listens for its redirect, the screen waits for it once back.
 * The click on an account offers Edit (its signature, "Click an email account. Click Edit.", B)
 * and Delete, its mail staying on the server. Screens, rows and words are mine (D): the 9900 set
 * accounts up through BlackBerry's service, no capture of it here. The password is in the field
 * only, handed to apps/hub in one call, cleared.
 */
class MailAccountsActivity : Activity() {
    private val graph get() = (application as MessagesApp).graph
    private var accounts: List<MailAccountInfo> = emptyList()
    private var browser: List<String> = emptyList()
    private var focus = 0
    private var setup: SetupState? = null
    private var editing: MailAccountInfo? = null
    private var signing = true
    /** The browser was opened for a sign-in: coming back finishes it. */
    private var browsing = false

    private lateinit var title: TitleBar
    private lateinit var list: ListView
    private lateinit var form: ScrollView
    private lateinit var address: EditText
    private lateinit var password: EditText
    private lateinit var servers: LinearLayout
    private lateinit var imapHost: EditText
    private lateinit var imapPort: EditText
    private lateinit var smtpHost: EditText
    private lateinit var smtpPort: EditText
    private lateinit var next: TextView
    private lateinit var browserHint: TextView
    private lateinit var browserButton: TextView
    private lateinit var edit: ScrollView
    private lateinit var useSignature: CheckBoxView
    private lateinit var signature: EditText
    private val rows = Rows()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        title = TitleBar(this, getString(R.string.accounts_title))
        list = ListView(this).apply {
            bb10List(this, Focus.RowBottom)
            adapter = rows
            setOnItemClickListener { _, _, position, _ ->
                focus = position
                clicked()
            }
        }
        address = field(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        imapHost = field(InputType.TYPE_TEXT_VARIATION_URI)
        imapPort = field(0).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        smtpHost = field(InputType.TYPE_TEXT_VARIATION_URI)
        smtpPort = field(0).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        next = button(R.string.setup_continue) { submit() }
        browserButton = button(R.string.setup_browser) { submitBrowser() }
        browserHint = hint("")
        servers = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(line(R.string.setup_imap, imapHost))
            addView(line(R.string.setup_port, imapPort))
            addView(line(R.string.setup_smtp, smtpHost))
            addView(line(R.string.setup_port, smtpPort))
        }
        form = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(this@MailAccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 9, 0, 9)
                addView(line(R.string.setup_address, address))
                addView(line(R.string.setup_password, password))
                addView(hint(getString(R.string.setup_password_hint)))
                addView(servers)
                addView(next, buttonParams())
                addView(browserHint)
                addView(browserButton, buttonParams(width = 340))
            })
        }
        useSignature = CheckBoxView(this).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener {
                signing = !signing
                bindEdit()
            }
            setOnFocusChangeListener { _, _ -> bindEdit() }
        }
        signature = field(0).apply {
            isSingleLine = false
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setPadding(6, 6, 6, 6)
        }
        edit = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(this@MailAccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 9, 0, 9)
                addView(useSignature, LinearLayout.LayoutParams(match, CheckBoxView.HEIGHT))
                addView(line(R.string.edit_signature, signature))
                addView(button(R.string.edit_save) { saveEdit() }, buttonParams())
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            addView(LinearLayout(this@MailAccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(list, LinearLayout.LayoutParams(match, 0, 1f))
                addView(form, LinearLayout.LayoutParams(match, 0, 1f))
                addView(edit, LinearLayout.LayoutParams(match, 0, 1f))
            })
        })
        // No status bar of Android's: the 9900 shows the title bar alone (sim-296, C).
        window.hideStatusBar()
        listOf(address, password, imapHost, imapPort, smtpHost, smtpPort).forEach {
            it.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) = read()
            })
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (browsing) {
            browsing = false
            finishBrowser()
        }
    }

    /** A label over its field (border_form, A; D for the layout). */
    private fun line(label: Int, f: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(9, 4, 9, 4)
        addView(TextView(this@MailAccountsActivity).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            text = getString(label)
        })
        addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun hint(text: String) = TextView(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.SUBTITLE.toFloat())
        setTextColor(ListColors.Subtitle)
        setPadding(9, 12, 9, 0)
        this.text = text
    }

    /** OS 7's button (ui_button, its focused image when the trackpad is on it; A). */
    private fun button(label: Int, onClick: () -> Unit) = TextView(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        gravity = Gravity.CENTER
        background = themeImage(this@MailAccountsActivity, "ui_button")
        text = getString(label)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            v.background = themeImage(this@MailAccountsActivity, if (has) "ui_button_focus" else "ui_button")
            (v as TextView).setTextColor(if (has) ListColors.Background else ListColors.Title)
        }
    }

    private fun buttonParams(width: Int = 220) = LinearLayout.LayoutParams(width, 51).apply {
        gravity = Gravity.END
        setMargins(0, 12, 9, 0)
    }

    private fun field(variation: Int) = EditText(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        background = themeImage(this@MailAccountsActivity, R.drawable.msg_form)
        setPadding(6, 0, 6, 0)
        minHeight = 43
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        useCaret()
        inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        defaultFocusHighlightEnabled = false
    }

    /** The accounts, and the home's icon of each (AccountShortcuts) kept in step with them. */
    private fun load() {
        val g = graph
        g.ioThread.post {
            val all = g.mail.accounts() ?: return@post
            AccountShortcuts.publish(this, all.list)
            runOnUiThread {
                accounts = all.list
                browser = all.browser
                focus = focus.coerceIn(0, accounts.size)
                rows.notifyDataSetChanged()
                bindBrowser()
            }
        }
    }

    /** Browser sign-in offered when this build has an OAuth client, said off otherwise. */
    private fun bindBrowser() {
        val on = browser.isNotEmpty()
        browserButton.visibility = if (on && setup?.servers != true) View.VISIBLE else View.GONE
        browserHint.text = if (on) getString(R.string.setup_browser_hint, browser.joinToString(", ")) else getString(R.string.setup_browser_off)
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** An account: Edit or Delete? The last row: Email Setup. */
    private fun clicked() {
        if (locked()) return
        val account = accounts.getOrNull(focus) ?: return openSetup()
        Bold7Dialog(this, account.address, listOf(getString(R.string.accounts_edit), getString(R.string.accounts_delete), getString(R.string.button_cancel)), focused = 0, onButton = {
            when (it) {
                0 -> openEdit(account)
                1 -> askDelete(account)
            }
        }).show()
    }

    private fun askDelete(account: MailAccountInfo) {
        Bold7Dialog(this, getString(R.string.accounts_delete_question, account.address), listOf(getString(R.string.accounts_delete), getString(R.string.button_cancel)), DialogIcon.QUESTION, focused = 1, onButton = {
            if (it != 0 || locked()) return@Bold7Dialog
            val g = graph
            g.ioThread.post {
                g.mail.remove(account.id)
                runOnUiThread { load() }
            }
        }).show()
    }

    private fun openSetup() {
        setup = SetupState()
        title.bind(getString(R.string.setup_title))
        list.visibility = View.GONE
        form.visibility = View.VISIBLE
        servers.visibility = View.GONE
        imapPort.setText("993")
        smtpPort.setText("465")
        bindBrowser()
        address.requestFocus()
    }

    private fun closeSetup() {
        setup = null
        listOf(address, password, imapHost, smtpHost).forEach { it.setText("") }
        title.bind(getString(R.string.accounts_title))
        form.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.requestFocus()
        load()
    }

    /** Edit: Use Auto Signature and its text (guide 7.1 p. 90, B), OS 7's own the default. */
    private fun openEdit(account: MailAccountInfo) {
        editing = account
        signing = account.signature != null
        signature.setText(account.signature ?: getString(R.string.edit_signature_default))
        title.bind(account.address)
        list.visibility = View.GONE
        edit.visibility = View.VISIBLE
        bindEdit()
        useSignature.requestFocus()
    }

    private fun bindEdit() {
        useSignature.bind(getString(R.string.edit_use_signature), signing, lit = useSignature.isFocused)
        signature.isEnabled = signing
        signature.alpha = if (signing) 1f else 0.5f
    }

    private fun closeEdit() {
        editing = null
        title.bind(getString(R.string.accounts_title))
        edit.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.requestFocus()
        load()
    }

    /** Save (Menu > Save, B): the signature kept by apps/hub with the account, added to each mail sent. */
    private fun saveEdit() {
        val account = editing ?: return
        if (locked()) return
        val text = signature.text.toString().trim().takeIf { signing && it.isNotEmpty() }
        val g = graph
        g.ioThread.post {
            g.mail.setSignature(account.id, text)
            runOnUiThread { closeEdit() }
        }
    }

    private fun read() {
        val s = setup ?: return
        setup = s.copy(
            address = address.text.toString(), password = password.text.toString(),
            imapHost = imapHost.text.toString(), imapPort = imapPort.text.toString(),
            smtpHost = smtpHost.text.toString(), smtpPort = smtpPort.text.toString(),
        )
    }

    private fun submit() {
        val s = setup ?: return
        if (s.busy || locked()) return
        if (!s.ready) return status(this, getString(if (s.servers) R.string.setup_servers else R.string.setup_invalid))
        setup = s.copy(busy = true, notice = null)
        status(this, getString(R.string.setup_busy))
        val g = graph
        g.ioThread.post {
            val (result, _) = g.mail.add(s)
            runOnUiThread { answered(s.address, result) }
        }
    }

    /**
     * Sign In With Browser: apps/hub's page opened in the system's browser (a Custom Tab when the
     * browser has them, its own tab otherwise), never a WebView: the password is typed into Google's
     * or Microsoft's page only. Escape back here finishes the sign-in (onResume).
     */
    private fun submitBrowser() {
        val s = setup ?: return
        if (s.busy || locked()) return
        if (!isAddress(s.address)) return status(this, getString(R.string.setup_invalid_address))
        setup = s.copy(busy = true, notice = null)
        val g = graph
        g.ioThread.post {
            val (result, url) = g.mail.browserBegin(s.address)
            runOnUiThread {
                if (result != MailResult.OK || url == null) return@runOnUiThread answered(s.address, result)
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    // A Custom Tab without the androidx library: the session extra alone asks for one.
                    .putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
                try {
                    startActivity(view)
                    browsing = true
                } catch (e: ActivityNotFoundException) {
                    setup = setup?.copy(busy = false)
                    status(this, getString(R.string.setup_no_browser))
                }
            }
        }
    }

    /** Back from the browser: apps/hub's answer, once its sign-in and both servers' check are done. */
    private fun finishBrowser() {
        val s = setup ?: return
        status(this, getString(R.string.setup_busy))
        val g = graph
        g.ioThread.post {
            val (result, _) = g.mail.browserFinish()
            runOnUiThread { answered(s.address, result) }
        }
    }

    private fun answered(typed: String, result: MailResult) {
        val now = (setup ?: return).answered(result)
        setup = now
        if (result == MailResult.OK) {
            status(this, getString(R.string.setup_done, typed.trim()))
            closeSetup()
            return
        }
        if (now.servers && servers.visibility != View.VISIBLE) {
            servers.visibility = View.VISIBLE
            bindBrowser()
            imapHost.requestFocus()
        }
        status(this, getString(when (now.notice) {
            SetupNotice.AUTH -> R.string.setup_auth
            SetupNotice.CERTIFICATE -> R.string.setup_certificate
            SetupNotice.NETWORK -> R.string.setup_network
            SetupNotice.SERVERS -> R.string.setup_servers
            SetupNotice.UNAVAILABLE -> R.string.setup_browser_unavailable
            SetupNotice.CANCELLED -> R.string.setup_browser_cancelled
            SetupNotice.REFUSED, SetupNotice.INVALID, null -> R.string.setup_refused
        }))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val code = e.keyCode
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        if (setup != null || editing != null) {
            if (!back) return super.dispatchKeyEvent(e)
            if (setup != null) closeSetup() else closeEdit()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                focus = (focus + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, accounts.size)
                rows.notifyDataSetChanged()
                list.setSelection(focus)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) clicked()
            else -> if (back) finish() else return super.dispatchKeyEvent(e)
        }
        return true
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = accounts.size + 1
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? ListItemView) ?: ListItemView(this@MailAccountsActivity)).apply {
                val a = accounts.getOrNull(position)
                if (a != null) bind(a.address, "", lit = position == focus) else bind(getString(R.string.accounts_add), getString(R.string.accounts_add_hint), lit = position == focus)
            }
    }
}
