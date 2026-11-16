package dev.bold7.messages

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import dev.bold7.design.ThreadColors
import dev.bold7.design.ThreadDimens
import dev.bold7.platform.hideStatusBar
import dev.bold7.showcase.avatar
import dev.bold7.showcase.isAvatar
import dev.bold7.showcase.SHOWCASE_EVENT_SETTING
import dev.bold7.showcase.showcaseEvent
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Emoticons
import dev.bold7.ui.EmoticonStrip
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Dir
import dev.bold7.ui.ListItemView
import dev.bold7.ui.FieldSelection
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TextAct
import dev.bold7.ui.Travel
import dev.bold7.ui.bb10List
import dev.bold7.ui.status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A text message's thread of BBOS 7.1 (docs/BBOS7-VISUEL.md §16): the contact's
 * band (sim-50), the bubbles oldest first, the field at the bottom; for a new message the To band
 * (sim-49). Google Messages keeps the SMS role: the thread is read from Telephony, sent, marked
 * read and deleted through its ExternalMessagingApiService (Conversation, GrpcMessagingApi). The
 * inbox opens it only when that door is open (MessagesActivity.open). Not exported; never shown
 * over the lock screen, and every write checks the keyguard again. Renders [ConversationState],
 * decides nothing.
 *
 * `su -c 'am start -n dev.bold7.messages/.ConversationActivity --ei rows 500'`: made-up bubbles,
 * nothing read nor sent (tools/perf.sh, APP mode).
 */
class ConversationActivity : Activity() {
    companion object {
        private const val EXTRA_THREAD = "thread"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ROWS = "rows"
        /** The Compose alias's (Text of Contacts): a new message to this number. */
        private const val EXTRA_TO = "to"
        /** The field's first text (Send Contact Card of Contacts); with no To, a new message to anyone. */
        private const val EXTRA_BODY = "body"

        /** Choose Contact: Select Contact of Bold7's Contacts (SELECT_CONTACT, signature). */
        private const val CONTACTS = "dev.bold7.contacts"
        private const val SELECT_CONTACT = "$CONTACTS.SelectContactActivity"
        private const val CHOOSE_CONTACT = 1
        /** Attach's file: the system's document picker, D (no source of OS 7's). */
        private const val PICK_FILE = 2
        private val PICKERS = setOf(ThreadAct.CHOOSE_CONTACT, ThreadAct.ATTACH_PICTURE, ThreadAct.ATTACH_VIDEO, ThreadAct.ATTACH_AUDIO, ThreadAct.ATTACH_FILE)

        /** The thread [threadId] under [title]; null: a new message (Compose Text Message). */
        fun intent(context: Context, threadId: Long?, title: String): Intent =
            Intent(context, ConversationActivity::class.java).putExtra(EXTRA_TITLE, title).apply { if (threadId != null) putExtra(EXTRA_THREAD, threadId) }
    }

    private val graph get() = (application as MessagesApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var conversation: Conversation
    private var state = ConversationState()
    private var menu: Menu<ThreadAct>? = null
    private var dialog: Dialog? = null
    private var held = false
    private val selection = FieldSelection()
    private var clicking = false
    private var travel = Travel()
    /** The editor being given the state's draft: not an edit to report. */
    private var binding = false
    private var smileys: Dialog? = null

    private val images = ThreadImages()
    private val glyphs = arrayOfNulls<Bitmap>(ThreadAct.entries.size)
    private val pictures = HashMap<String, Bitmap?>()
    /** The theme's attachment_*.png by kind, and the attached picture's thumbnail. */
    private val attachIcons = HashMap<AttachIcon, Bitmap?>()
    private var thumbnail: Pair<String, Bitmap?>? = null
    /** The contact's picture in the band (gsm 023, C), decoded off the main thread. */
    private var photo: Bitmap? = null
    /** The phone's zone, language and clock, read off the main thread with the images. */
    @Volatile private var format: InboxFormat? = null

    private lateinit var root: FrameLayout
    private lateinit var band: ThreadBandView
    private lateinit var list: ListView
    /** To's proposals while a name is typed, over the bubbles' place (empty for a new message). */
    private lateinit var proposals: ListView
    private lateinit var composer: ComposerView
    private lateinit var attachmentView: AttachmentView
    private lateinit var menuView: MenuView<ThreadAct>
    private val bubbles = BubbleAdapter()
    private val proposed = ProposalAdapter()

    private val observer by lazy {
        object : ContentObserver(graph.ioThread) {
            override fun onChange(selfChange: Boolean) = conversation.reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        band = ThreadBandView(this, images) { conversation.event(ThreadEvent.ToTouched(it)) }
        composer = ComposerView(this, images, ::openSmileys)
        composer.editor.apply {
            addTextChangedListener(object : TextWatcher {
                private var before = ""
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) { before = s.toString() }
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    markEmoticons(s)
                    // The state is the draft's source; the editor reports what was typed into it.
                    if (!binding) conversation.event(ThreadEvent.Edited(before, s.toString()))
                }
            })
            setOnCapturedPointerListener { _, e -> onPointer(e) }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) conversation.event(ThreadEvent.FieldTouched)
                false
            }
        }
        list = bb10List(ListView(this), 0).apply {
            adapter = bubbles
            isFocusable = false
            isHapticFeedbackEnabled = false
            setBackgroundColor(ThreadColors.Background)
            // The newest at the bottom, over the field (sim-52, C).
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = false
            setOnItemClickListener { _, _, position, _ ->
                conversation.event(ThreadEvent.Moved(position))
                // A bubble with an attachment opens it (D).
                state.messages.getOrNull(position)?.opens?.let(::open)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                conversation.event(ThreadEvent.Moved(position))
                held = showShort()
                true
            }
        }
        proposals = bb10List(ListView(this), 0).apply {
            adapter = proposed
            isFocusable = false
            isHapticFeedbackEnabled = false
            setBackgroundColor(ThreadColors.Background)
            visibility = View.GONE
            setOnItemClickListener { _, _, position, _ -> state.suggestions.getOrNull(position)?.let { take(choice(it)) } }
        }
        attachmentView = AttachmentView(this).apply { visibility = View.GONE }
        menuView = MenuView(
            this,
            glyph = { glyphs[it.ordinal] },
            label = ::label,
            onEntry = ::chosen,
            onExpand = { menu?.let { show(it.expand()) } },
            onClose = { show(null) },
        )
        root = FrameLayout(this).apply {
            setBackgroundColor(ThreadColors.Background)
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            setOnCapturedPointerListener { _, e -> onPointer(e) }
            addView(LinearLayout(this@ConversationActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(band, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(FrameLayout(this@ConversationActivity).apply {
                    addView(list, FrameLayout.LayoutParams(match, match))
                    addView(proposals, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
                }, LinearLayout.LayoutParams(match, 0, 1f))
                addView(attachmentView, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(composer, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            addView(menuView)
        }
        setContentView(root)
        // The thread has no status bar (sim-50, sim-49, C): gone at once, a swipe only shows it for
        // a moment (core/platform), hidden again whenever the window gets its focus back.
        window.hideStatusBar()

        scope = CoroutineScope(SupervisorJob() + graph.main)
        conversation = graph.conversation(scope)
        val rows = intent.getIntExtra(EXTRA_ROWS, 0)
        val thread = if (intent.hasExtra(EXTRA_THREAD)) intent.getLongExtra(EXTRA_THREAD, -1) else null
        val to = intent.getStringExtra(EXTRA_TO)?.takeIf { it.isNotBlank() }
        // The showcase shows nothing real: a real thread, or a new message from another app (Contacts'
        // Text), closes. Android's settings cache makes the read a memory one.
        if (rows == 0 && (thread == null || !isShowcaseThread(thread)) && showcaseOn(this)) return finish()
        when {
            rows > 0 -> conversation.demo(rows, System.currentTimeMillis())
            // The showcase's rows open its own threads (ShowcaseInbox): nothing real is read.
            thread != null && isShowcaseThread(thread) -> showcaseConversation(thread, System.currentTimeMillis(), showcaseEvent(this))?.let(conversation::show) ?: finish()
            to != null -> conversation.compose(to, intent.getStringExtra(EXTRA_TITLE).orEmpty())
            else -> conversation.open(thread, intent.getStringExtra(EXTRA_TITLE).orEmpty())
        }
        // The text the caller asked for, in the field of the new message; the draft it makes is Messages' own.
        intent.getStringExtra(EXTRA_BODY)?.takeIf { it.isNotEmpty() }?.let { conversation.event(ThreadEvent.Edited("", it)) }
        // A picture from Pictures' Send > Text Message (through Compose, COMPOSE_TEXT):
        // attached to the new message as Attach's file is, copied, size checked.
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { conversation.attach(it.toString()) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { conversation.state.collect(::render) }
        loadImages()
    }

    override fun onStart() {
        super.onStart()
        // Telephony notifies content://mms-sms/ for every SMS and MMS row, RCS included.
        graph.ioThread.post {
            contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
            contentResolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_EVENT_SETTING), false, story)
        }
        conversation.reload()
    }

    override fun onStop() {
        graph.ioThread.post {
            contentResolver.unregisterContentObserver(observer)
            contentResolver.unregisterContentObserver(story)
        }
        super.onStop()
    }

    /** The video's story (bold7_showcase_event, the shell's): its text or reply comes into the showcase thread shown. */
    private val story by lazy {
        object : ContentObserver(graph.ioThread) {
            override fun onChange(selfChange: Boolean) {
                val thread = conversation.state.value.threadId?.takeIf(::isShowcaseThread) ?: return
                val made = showcaseConversation(thread, System.currentTimeMillis(), showcaseEvent(this@ConversationActivity)) ?: return
                runOnUiThread { conversation.arrive(made) }
            }
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        smileys?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Back from Select Contact, the bar was there over To.
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        takeFocus()
    }

    /**
     * Android's focus: on the field's editor, so that the input method sees every key first, but
     * on To, which the band draws and the thread types into; the trackpad captured by the view
     * that has it.
     */
    private fun takeFocus() {
        val view = if (state.focus == TO) root else composer.editor
        view.requestFocus()
        view.requestPointerCapture()
    }

    private fun loadImages() = scope.launch {
        val sm = mapOf(
            ThreadAct.SEND to R.drawable.msg_sm_chat_send, ThreadAct.CLEAR_FIELD to R.drawable.msg_sm_clear_input,
            ThreadAct.CLEAR_CONVERSATION to R.drawable.msg_sm_cancel_generic, ThreadAct.DELETE_MESSAGE to R.drawable.msg_sm_delete,
            ThreadAct.ADD_SMILEY to R.drawable.msg_sm_add_emoticon, ThreadAct.CHOOSE_CONTACT to R.drawable.msg_sm_address_book_app,
            ThreadAct.ATTACH to R.drawable.msg_sm_add_attachment, ThreadAct.OPEN_ATTACHMENT to R.drawable.msg_sm_open_attachment,
            ThreadAct.REMOVE_ATTACHMENT to R.drawable.msg_sm_delete,
        )
        val kinds = mapOf(
            AttachIcon.PICTURE to R.drawable.msg_attachment_generic_picture, AttachIcon.VIDEO to R.drawable.msg_attachment_generic_video,
            AttachIcon.MUSIC to R.drawable.msg_attachment_music, AttachIcon.CONTACT to R.drawable.msg_attachment_contact,
            AttachIcon.CALENDAR to R.drawable.msg_attachment_calendar, AttachIcon.UNKNOWN to R.drawable.msg_attachment_unknown,
        )
        val decoded = withContext(graph.io) {
            images.icons = decode(R.drawable.msg_thread_icons)
            images.avatar = decode(R.drawable.msg_avatar)
            images.smiley = decode(R.drawable.msg_smiley)
            images.smileyFocus = decode(R.drawable.msg_smiley_focus)
            EmoticonStrip.decode(this@ConversationActivity)
            images.smileys45 = decode(R.drawable.msg_smileys_45)
            format = graph.reader.format()
            sm.mapValues { decode(it.value) } to kinds.mapValues { decode(it.value) }
        }
        decoded.first.forEach { (act, bitmap) -> glyphs[act.ordinal] = bitmap }
        attachIcons.putAll(decoded.second)
        bindAttachment(state.attachment)
        band.invalidate()
        composer.invalidate()
        composer.editor.invalidate()
        list.invalidateViews()
        menuView.invalidate()
    }

    private fun decode(id: Int): Bitmap? = BitmapFactory.decodeResource(resources, id)

    // State

    private fun render(next: ConversationState) {
        val old = state
        state = next
        bindBand(next)
        if (next.photo != old.photo) loadPhoto(next.photo)
        if (next.attachment != old.attachment) bindAttachment(next.attachment)
        if (next.to != old.to || next.contacts !== old.contacts || next.pick != old.pick || next.threadId != old.threadId) {
            proposals.visibility = if (next.threadId == null && next.suggestions.isNotEmpty()) View.VISIBLE else View.GONE
            proposed.notifyDataSetChanged()
            if (next.pick >= 0) proposals.smoothScrollToPosition(next.pick)
        }
        composer.bind(next.focus == COMPOSER, next.sending, next.focus == SMILEY)
        // The latest draft, not this render's: an edit reported since must not be undone.
        val draft = conversation.state.value.draft
        val editor = composer.editor
        if (editor.text.toString() != draft) {
            binding = true
            editor.setText(draft)
            editor.setSelection(draft.length)
            binding = false
        }
        if ((next.focus == TO) != (old.focus == TO)) takeFocus()
        if (next.messages !== old.messages || next.focus != old.focus) bubbles.notifyDataSetChanged()
        if (next.messages.size > old.messages.size) list.setSelection(next.messages.lastIndex)
        if (next.focus >= 0 && next.focus != old.focus) list.smoothScrollToPosition(next.focus)
        next.notice?.let {
            conversation.event(ThreadEvent.Noticed)
            status(this, when (it) {
                Notice.NOT_SENT -> getString(R.string.not_sent)
                Notice.NOT_CREATED -> getString(R.string.not_created, next.destinations.joinToString(", "))
                Notice.NO_RECIPIENT -> getString(R.string.no_recipient)
                Notice.NOT_CHOSEN -> getString(R.string.not_chosen)
                Notice.NOT_DELETED -> getString(R.string.not_deleted)
                Notice.NOT_ATTACHABLE -> getString(R.string.not_attachable)
                Notice.NOT_ATTACHED -> getString(R.string.not_attached)
                Notice.TOO_LARGE -> getString(R.string.too_large)
            })
        }
        loadPictures(next.messages)
    }

    /** The contact's band, or a new message's To lines, the caret on To's focused line. */
    private fun bindBand(s: ConversationState) {
        val lines = if (s.threadId == null) s.recipients.map { it.shown } + s.to else null
        band.bind(s.title, lines, if (s.focus == TO && s.pick < 0) s.line else -1, photo)
    }

    /** The band's picture: the contact's thumbnail, decoded on the thread's executor; none, the default one. */
    private fun loadPhoto(uri: String?) {
        if (uri == null) {
            photo = null
            return bindBand(state)
        }
        scope.launch {
            val bitmap = withContext(graph.io) { if (isAvatar(uri)) avatar(this@ConversationActivity, uri, ThreadDimens.AVATAR) else graph.contacts.picture(uri) }
            if (state.photo != uri) return@launch
            photo = bitmap
            bindBand(state)
        }
    }

    /**
     * The attachment's band over the field: a picture's thumbnail (decoded on the thread's
     * executor), else its kind's image; its name and size.
     */
    private fun bindAttachment(a: Attachment?) {
        attachmentView.visibility = if (a == null) View.GONE else View.VISIBLE
        if (a == null) return
        val icon = iconOf(a.type)
        val thumb = thumbnail?.takeIf { it.first == a.uri }?.second
        attachmentView.bind(a.name, Formatter.formatShortFileSize(this, a.size), thumb ?: attachIcons[icon])
        if (icon != AttachIcon.PICTURE || thumbnail?.first == a.uri) return
        thumbnail = a.uri to null
        scope.launch {
            val bitmap = withContext(graph.io) { graph.threads.image(a.uri, AttachmentView.IMAGE) }
            if (state.attachment?.uri != a.uri) return@launch
            thumbnail = a.uri to bitmap
            bindAttachment(a)
        }
    }

    /** An MMS's images, decoded once on the thread's executor, at most the bubble's text width. */
    private fun loadPictures(messages: List<ThreadMessage>) {
        val wanted = messages.flatMap { it.images }.filter { it !in pictures }
        if (wanted.isEmpty()) return
        wanted.forEach { pictures[it] = null }
        val width = resources.displayMetrics.widthPixels
        scope.launch {
            val loaded = withContext(graph.io) { wanted.associateWith { graph.threads.image(it, width) } }
            pictures.putAll(loaded)
            list.invalidateViews()
        }
    }

    // Menus

    private fun label(act: ThreadAct) = getString(
        when (act) {
            ThreadAct.SEND -> R.string.thread_send
            ThreadAct.DELETE_MESSAGE -> R.string.thread_delete_message
            ThreadAct.CLEAR_FIELD -> R.string.thread_clear_field
            ThreadAct.CLEAR_CONVERSATION -> R.string.thread_clear_conversation
            ThreadAct.ADD_SMILEY -> R.string.thread_add_smiley
            ThreadAct.CHOOSE_CONTACT -> R.string.thread_choose_contact
            ThreadAct.SWITCH -> R.string.act_switch_application
            ThreadAct.CLOSE -> R.string.mail_close
            ThreadAct.ATTACH -> R.string.thread_attach
            ThreadAct.ATTACH_PICTURE -> R.string.attach_picture
            ThreadAct.ATTACH_VIDEO -> R.string.attach_video
            ThreadAct.ATTACH_AUDIO -> R.string.attach_audio
            ThreadAct.ATTACH_FILE -> R.string.attach_file
            ThreadAct.REMOVE_ATTACHMENT -> R.string.thread_remove_attachment
            ThreadAct.OPEN_ATTACHMENT -> R.string.thread_open_attachment
            ThreadAct.START_SELECTION, ThreadAct.CUT, ThreadAct.COPY, ThreadAct.PASTE -> FieldSelection.label(TextAct.valueOf(act.name))
        },
    )

    private fun show(m: Menu<ThreadAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun showFull() {
        val field = state.focus == COMPOSER || state.focus == SMILEY
        show(fullThreadMenu(state, if (field) selection.acts(composer.editor) else emptyList()))
    }

    private fun showShort(): Boolean {
        val entries = threadMenu(state)
        if (entries.isEmpty()) return false
        show(Menu(entries.map { it.copy(separatorAbove = false) }, line = state.focus.coerceAtLeast(0), highlighted = defaultEntry(entries)))
        return true
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        perform(act)
    }

    private fun onMenuKey(m: Menu<ThreadAct>, key: MenuKey) {
        when (val r = m.key(key)) {
            is MenuResult.Show -> show(r.menu)
            is MenuResult.Choose -> chosen(r.index)
            MenuResult.Close -> show(null)
        }
    }

    /** Nothing interactive while the phone is locked (CONTRIBUTING.md). */
    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun perform(act: ThreadAct) {
        if (locked()) return
        // A made-up thread opens nothing of the phone's: no picker of its files, no contacts.
        if (isMadeUp(state.threadId) && act in PICKERS) return
        when (act) {
            ThreadAct.SWITCH -> SwitchApplication.start(this)
            ThreadAct.CLOSE -> leave()
            ThreadAct.CHOOSE_CONTACT -> chooseContact()
            ThreadAct.SEND -> conversation.send()
            ThreadAct.CLEAR_FIELD -> conversation.event(ThreadEvent.FieldCleared)
            ThreadAct.ADD_SMILEY -> openSmileys()
            ThreadAct.DELETE_MESSAGE -> state.focusedMessage?.let { m ->
                confirm(R.string.confirm_delete_message, R.string.button_delete) { conversation.delete(listOf(m.uri)) }
            }
            // Asked before: a whole conversation (Confirm Delete, guide p. 89, B), Cancel focused (D).
            ThreadAct.CLEAR_CONVERSATION -> {
                val uris = state.messages.map { it.uri }
                confirm(R.string.confirm_clear_conversation, R.string.button_delete, focused = 1) { conversation.delete(uris) }
            }
            // Its kinds in the place of the menu (D, as the Calendar's View).
            ThreadAct.ATTACH -> show(Menu(attachMenu(), highlighted = 0))
            ThreadAct.ATTACH_PICTURE, ThreadAct.ATTACH_VIDEO, ThreadAct.ATTACH_AUDIO, ThreadAct.ATTACH_FILE -> act.attachKind()?.let(::pick)
            ThreadAct.REMOVE_ATTACHMENT -> conversation.removeAttachment()
            ThreadAct.OPEN_ATTACHMENT -> state.focusedMessage?.opens?.let(::open)
            ThreadAct.START_SELECTION, ThreadAct.CUT, ThreadAct.COPY, ThreadAct.PASTE -> selection.perform(composer.editor, TextAct.valueOf(act.name))
        }
    }

    /**
     * "Click an attachment type. Click a file" (guide 7.1 p. 104, B): Picture, Video and Audio open
     * Bold7's picker (PickerActivity); File, and any kind whose media permission is
     * not granted, the system's document picker. The
     * answer in [onActivityResult].
     */
    private fun pick(kind: AttachKind) {
        if (MediaReader.readable(this, kind)) {
            @Suppress("DEPRECATION")
            startActivityForResult(PickerActivity.intent(this, kind), PICK_FILE)
            return
        }
        val types = pickerTypes(kind)
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(types.singleOrNull() ?: "*/*").putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(open, PICK_FILE)
        } catch (e: ActivityNotFoundException) {
            status(this, getString(R.string.no_picker))
        }
    }

    /**
     * A bubble's attachment: a picture in the viewer of OS 7 (ViewerActivity); a video or a sound
     * in its player (PlayerActivity); a card in the app that opens it, lent the part
     * for reading.
     */
    private fun open(o: Opens) {
        if (locked()) return
        val intent = when (o) {
            is Opens.Picture -> ViewerActivity.intent(this, o.uri)
            is Opens.Media -> if (plays(o.type)) PlayerActivity.intent(this, o.uri, o.type) else Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(o.uri), o.type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            status(this, getString(R.string.no_player))
        } catch (e: SecurityException) {
            status(this, getString(R.string.no_player))
        }
    }

    /** Choose Contact (guide 7.1 p. 103, B): Select Contact of Bold7's Contacts for a number, its answer in [onActivityResult]. */
    private fun chooseContact() {
        val pick = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI).setClassName(CONTACTS, SELECT_CONTACT)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, CHOOSE_CONTACT)
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
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || locked()) return
        when (requestCode) {
            CHOOSE_CONTACT -> conversation.choose(uri.toString())
            PICK_FILE -> conversation.attach(uri.toString())
        }
    }

    /** A contact or a number taken in To; several numbers: which one, a button each, as Select Contact asks (D). */
    private fun take(choice: ToChoice) {
        if (locked()) return
        when (choice) {
            is ToChoice.One -> conversation.event(ThreadEvent.Chosen(choice.recipient))
            is ToChoice.Numbers -> {
                val c = choice.contact
                dialog?.dismiss()
                dialog = Bold7Dialog(this, getString(R.string.select_number), c.numbers.map { "${it.label}: ${it.number}" }, onButton = {
                    if (!locked()) conversation.event(ThreadEvent.Chosen(c.recipient(c.numbers[it])))
                }).also { it.show() }
            }
        }
    }

    private fun confirm(text: Int, positive: Int, focused: Int = 0, then: () -> Unit) {
        dialog?.dismiss()
        dialog = Bold7Dialog(
            this, getString(text), listOf(getString(positive), getString(R.string.button_cancel)), DialogIcon.QUESTION,
            focused = focused, onButton = { if (it == 0 && !locked()) then() },
        ).also { it.show() }
    }

    /** Add Smiley's list (sim-145, C), from the button, its touch or the menus; nothing while locked. */
    private fun openSmileys() {
        if (locked()) return
        smileys?.dismiss()
        smileys = EmoticonDialog(this, images, ::putEmoticon).also { it.show() }
    }

    /**
     * The chosen emoticon's emoji in the field at its caret, in place of a selection (sim-148: the
     * image at the caret, the caret after it), through the editor: its watcher reports the edit.
     */
    private fun putEmoticon(index: Int) {
        if (locked()) return
        val text = composer.editor.text
        val a = composer.editor.selectionStart.takeIf { it >= 0 } ?: text.length
        val b = composer.editor.selectionEnd.takeIf { it >= 0 } ?: a
        text.replace(minOf(a, b), maxOf(a, b), Emoticons.ALL[index].emoji)
        conversation.event(ThreadEvent.FieldTouched)
    }

    /** Escape: the thread closes; a message being written is not dropped without asking (D, sim-47's question). */
    private fun leave() {
        if (state.draft.isBlank()) return finish()
        confirm(R.string.confirm_discard, R.string.button_discard, focused = 1) { finish() }
    }

    // Keys and the trackpad

    /**
     * A click: on the field with text, Send ("Type a message. Press the [Enter] key", guide 7.1
     * p. 103, B: the key's picture is lost in the text, Enter by the steps of every composer);
     * elsewhere the short menu (D).
     */
    private fun clicked() {
        if (state.focus == COMPOSER && state.sendable) return perform(ThreadAct.SEND)
        if (state.focus == SMILEY) return openSmileys()
        // A bubble with an attachment: opened (D); held, the short menu as before.
        state.focusedMessage?.opens?.let { return open(it) }
        // To: a number typed, the proposal under the trackpad or the first (guide 7.1 p. 103, B; D).
        if (state.focus == TO) state.toClick()?.let { return take(it) }
        showShort()
    }

    private fun move(dir: Dir, shift: Boolean = false) {
        menu?.let {
            onMenuKey(it, when (dir) {
                Dir.UP -> MenuKey.UP
                Dir.DOWN -> MenuKey.DOWN
                Dir.LEFT -> MenuKey.LEFT
                Dir.RIGHT -> MenuKey.RIGHT
            })
            return
        }
        val code = when (dir) {
            Dir.UP -> KeyEvent.KEYCODE_DPAD_UP
            Dir.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            Dir.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            Dir.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        // Start Selection: the trackpad extends the selection until Cut, Copy or Escape.
        if (selection.key(code)) return
        // Shift and the trackpad in the field: the selection grows (guide 7.1 p. 37, B), the focus stays.
        if (shiftSelects(fieldKey(code), state.focus, shift)) {
            FieldSelection.extend(composer.editor, code)
            return
        }
        if (dir == Dir.UP || dir == Dir.DOWN) return conversation.event(ThreadEvent.Step(down = dir == Dir.DOWN))
        // The trackpad as a pointer: in the field, its caret; at its end, the button.
        val right = dir == Dir.RIGHT
        val key = if (right) FieldKey.RIGHT else FieldKey.LEFT
        if (editorTakes(key, state.focus, caretAtEnd())) {
            val e = composer.editor
            if (shift && FieldSelection.extend(e, code)) return
            e.setSelection((e.selectionEnd + if (right) 1 else -1).coerceIn(0, e.length()))
        } else {
            conversation.event(ThreadEvent.Side(right))
        }
    }

    private fun caretAtEnd() = composer.editor.let { it.selectionEnd >= it.length() }

    private val hold = Runnable { if (clicking && menu == null) held = showShort() }

    /** The trackpad as a relative pointer, as in the inbox: steps move the focus, the button clicks, held the short menu. */
    private fun onPointer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> if (!clicking) {
                clicking = true
                root.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> if (clicking) {
                clicking = false
                root.removeCallbacks(hold)
                val m = menu
                when {
                    held -> held = false
                    m != null -> onMenuKey(m, MenuKey.CLICK)
                    else -> clicked()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                clicking = false
                root.removeCallbacks(hold)
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                val (next, steps) = travel.moved(dx, dy)
                travel = next
                val shift = e.metaState and KeyEvent.META_SHIFT_ON != 0
                for (d in steps) move(d, shift)
            }
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (menu == null) {
            // Start Selection: the trackpad extends, Escape stops. Shift and the trackpad up or down in the field select a line, the bubbles keep the steps otherwise (guide 7.1 p. 37, B).
            val down = e.action == KeyEvent.ACTION_DOWN
            if (down && selection.key(e)) return true
            if (shiftSelects(fieldKey(code), state.focus, e.isShiftPressed)) {
                if (down) FieldSelection.extend(composer.editor, code)
                return true
            }
        }
        // What types goes to the editor (the input method has seen it first); OS 7's keys stay here.
        if (menu == null && editorTakes(fieldKey(code), state.focus, caretAtEnd())) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            when {
                held -> held = false
                menu == null -> clicked()
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            onMenuKey(m, when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            })
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> conversation.event(ThreadEvent.Side(right = false))
            KeyEvent.KEYCODE_DPAD_RIGHT -> conversation.event(ThreadEvent.Side(right = true))
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> leave()
            KeyEvent.KEYCODE_MENU -> showFull()
            // Only To comes here for these: the field's are its editor's.
            KeyEvent.KEYCODE_DEL -> conversation.event(ThreadEvent.Erased)
            else -> when {
                click -> if (e.isLongPress || e.repeatCount == 1) held = showShort()
                else -> {
                    val c = e.unicodeChar
                    if (c == 0 || Character.isISOControl(c)) return super.dispatchKeyEvent(e)
                    conversation.event(ThreadEvent.Typed(c.toChar()))
                }
            }
        }
        return true
    }

    // The bubbles

    /** To's proposals: the contact's name over its number, or its numbers' types (D), as Options' rows (ListItemView). */
    private inner class ProposalAdapter : BaseAdapter() {
        override fun getCount() = if (state.threadId == null) state.suggestions.size else 0
        override fun getItem(position: Int) = state.suggestions[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? ListItemView) ?: ListItemView(this@ConversationActivity)
            val c = state.suggestions[position]
            val one = c.numbers.singleOrNull()
            view.bind(c.name, if (one != null) "${one.label}: ${one.number}" else c.numbers.joinToString(", ") { it.label }, position == state.pick)
            return view
        }
    }

    private inner class BubbleAdapter : BaseAdapter() {
        override fun getCount() = state.messages.size
        override fun getItem(position: Int) = state.messages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? BubbleView) ?: BubbleView(this@ConversationActivity, images) { pictures[it] }
            val m = state.messages[position]
            // A group's received bubble names its sender's number; one person's, the band's name (D).
            // A new message's history (one recipient, before sending): that recipient's name.
            val name = state.title.ifEmpty { state.recipients.singleOrNull()?.let { it.name ?: it.number }.orEmpty() }
            val label = if (m.out) getString(R.string.thread_me) else m.from?.takeIf { name.isEmpty() || isGroup() } ?: name
            val stamp = format?.let { bubbleTime(m.time, System.currentTimeMillis(), it) }.orEmpty()
            view.bind(m, label, stamp, position == state.focus, parent.width)
            return view
        }

        private fun isGroup() = state.messages.mapNotNull { it.from }.distinct().size > 1
    }
}

/** Android's key [code] as a field's owner reads it (the thread, the inbox's search). */
fun fieldKey(code: Int): FieldKey = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> FieldKey.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> FieldKey.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> FieldKey.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> FieldKey.RIGHT
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> FieldKey.CLICK
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> FieldKey.BACK
    KeyEvent.KEYCODE_MENU -> FieldKey.MENU
    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> FieldKey.DELETE
    else -> FieldKey.TEXT
}
