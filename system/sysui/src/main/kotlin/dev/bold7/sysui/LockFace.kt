package dev.bold7.sysui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Process
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import dev.bold7.design.Typography
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// The SystemUI classes LockFace reaches by name (the module's class loader cannot see them).
// LockFaceTest finds each in stubs/systemui.jar, LockTargets.kt compiles the members.
internal const val NOTIFICATION_STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
internal const val CONSTRAINT_LAYOUT = "androidx.constraintlayout.widget.ConstraintLayout"
private const val SYSTEMUI_PACKAGE = "com.android.systemui"
private const val LOCK_PATTERN_UTILS = "com.android.internal.widget.LockPatternUtils"

/**
 * The 9900's face of the lock screen (docs/BBOS7-VISUEL.md §10, sim-64) over Android's, which keeps
 * every mechanism: the home's banner and indicator row with its counters, the padlock at the bottom
 * right, the owner's information. Android's views stay in place with their logic, unseen while the
 * lock screen itself shows, SystemUI awake: the notification stack neither drawn nor touched
 * ([stackHidden]), the keyguard's root view (LineageOS 24's blueprint: clock, date, smartspace,
 * indications, the device entry icon) not drawn but still touched, the keyguard bar clipped away.
 * Pulled down, the shade shows Android's again. Under OS 7's entry the face stays whole and the scrims clear, the wallpaper
 * whole as on sim-85 ([faceAlpha], [scrimsCleared]). Until the face is attached, and for good after
 * one of its failures, Android's face shows.
 */
internal class LockFace(private val images: (Context) -> Images?, private val fail: (String, Exception?) -> Unit, private val bouncer: Bouncer) {
    private var face = WeakReference<FaceView>(null)
    private var broken = false
    private var stackState: Field? = null
    private var stackAmbient: Field? = null
    private var ambientDozing: Field? = null
    private var ambientDozeAmount: Field? = null
    private var root = WeakReference<View>(null)
    private var stack = WeakReference<ViewGroup>(null)
    private var keyguardBar = WeakReference<View>(null)
    private var scrimState: Field? = null
    private var scrimBehind: Field? = null
    private var scrimNotifications: Field? = null
    private var scrimQs: Field? = null
    private var scrimFullShade: Field? = null

    /** Whether OS 7's face stands in for Android's: attached, and never failed. */
    private val ready get() = !broken && face.get()?.isAttachedToWindow == true

    // --- Targets -----------------------------------------------------------------------------

    fun stackTarget(loader: ClassLoader, name: String, vararg params: Class<*>): Method {
        val stack = loader.loadClass(NOTIFICATION_STACK)
        stackState = stack.getField("mStatusBarState")
        stackAmbient = stack.getField("mAmbientState")
        ambientDozing = stackAmbient!!.type.getField("mDozing")
        ambientDozeAmount = stackAmbient!!.type.getField("mDozeAmount")
        return stack.getDeclaredMethod(name, *params)
    }

    fun windowTarget(loader: ClassLoader): Method = loader.loadClass(SHADE_WINDOW_VIEW).getDeclaredMethod("onAttachedToWindow")

    // KeyguardRootView is final and draws through ConstraintLayout's dispatchDraw, which every
    // ConstraintLayout of SystemUI shares: only the keyguard's root is ever skipped ([skipRoot]).
    fun rootDrawTarget(loader: ClassLoader): Method = loader.loadClass(CONSTRAINT_LAYOUT).getDeclaredMethod("dispatchDraw", Canvas::class.java)

    /** [applyState] as the table resolved it: R8 suffixes it (applyState$1 on LineageOS 24). */
    fun scrimTarget(loader: ClassLoader, applyState: Method): Method {
        val cls = loader.loadClass(SCRIM_CONTROLLER)
        scrimState = cls.getField("mState")
        scrimBehind = cls.getField("mBehindAlpha")
        scrimNotifications = cls.getField("mNotificationsAlpha")
        scrimQs = cls.getField("mQsExpansion")
        scrimFullShade = cls.getField("mTransitionToFullShadeProgress")
        return applyState
    }

    // --- Hooks -------------------------------------------------------------------------------

    /**
     * After NotificationShadeWindowView.onAttachedToWindow, on the main thread: the face, a sibling
     * of keyguard_root_view (KeyguardRootView, which super_notification_shade inflates once with the
     * window) right above it, under the notifications, the bouncer and its messages. Android fades
     * and hides its root (KeyguardRootViewBinder: alpha, INVISIBLE once the keyguard is gone): the
     * face copies them at each frame. Once per window; nothing without the root.
     */
    fun addFace(window: ViewGroup) {
        if (face.get()?.parent != null) return
        val images = images(window.context) ?: return
        val id = window.resources.getIdentifier("keyguard_root_view", "id", SYSTEMUI_PACKAGE)
        val keyguard = (if (id == 0) null else window.findViewById<View>(id))
        val parent = keyguard?.parent as? ViewGroup
        if (parent == null) {
            fail("lock face: keyguard_root_view not found, Android's face kept", null)
            return
        }
        val view = FaceView(window.context, images, keyguard, ::state, { awake() == true }, bouncer::shown, ::faceFailed, ::onAttached, fail)
        parent.addView(view, parent.indexOfChild(keyguard) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = WeakReference(keyguard)
        face = WeakReference(view)
        Log.i(TAG, "lock face: the 9900's face over the lock screen")
    }

    /**
     * After KeyguardStatusBarView.onFinishInflate: the bar the face's banner replaces, clipped away
     * while the face is in place. Android sets the bar's own clip in its onLayout (mClipRect, its top
     * clipping) and nowhere else: a layout listener, which View.layout calls right after onLayout,
     * clips it again each time; Android's visibility and alpha are left alone.
     */
    fun onKeyguardBar(bar: View) {
        keyguardBar = WeakReference(bar)
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (ready) v.clipBounds = NOTHING }
        if (ready) bar.clipBounds = NOTHING
    }

    /**
     * Instead of ConstraintLayout.dispatchDraw: for the keyguard's root only, nothing drawn (Android's
     * clock, date, smartspace, indications and device entry icon, kept with their state, logic and
     * touches) where the stack is hidden, the face standing for them; Android's drawing everywhere
     * else (shade, always-on display, pulse) and for every other ConstraintLayout.
     */
    fun skipRoot(view: Any?): Boolean = view != null && view === root.get() && hiddenNow()

    /**
     * Instead of NotificationStackScrollLayout.dispatchDraw and onDraw: true (not drawn) where
     * [stackHidden]; dispatchDraw also records what the frame showed for the touches. The counters
     * are the Hub's (HubCounts), the home's, none while Android shows no notification on its lock
     * screen.
     */
    fun skipStack(stack: ViewGroup, dispatch: Boolean): Boolean {
        if (this.stack.get() !== stack) this.stack = WeakReference(stack)
        val hidden = hiddenNow()
        if (!dispatch) return hidden
        lastFrameHidden = hidden
        return hidden
    }

    /**
     * Instead of NotificationStackScrollLayout.onInterceptTouchEvent ([intercept]) and
     * onTouchEvent: what touches what is seen. Decided at ACTION_DOWN from the last frame drawn,
     * kept for the gesture ([stackTouchAnswer]); null: Android's call.
     */
    fun stackTouch(event: MotionEvent, intercept: Boolean): Boolean? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureHidden = ready && lastFrameHidden
        return stackTouchAnswer(event.actionMasked, gestureHidden, intercept)
    }

    /**
     * Instead of NotificationStackScrollLayout.getChildAtRawPosition(float, float): no row under the
     * finger while the stack is hidden, so that a pull of the shade from where an unseen card lies
     * opens the shade as from an empty place (DragDownHelper), not that card expanded. True: the
     * answer is null; false: Android's call.
     */
    fun noRowUnderFinger(): Boolean = ready && lastFrameHidden

    /** Whether SystemUI is awake ([awake], the stack's AmbientState); null before the stack's first frame. */
    fun awake(): Boolean? {
        val stack = stack.get() ?: return null
        return try {
            val ambient = stackAmbient!!.get(stack) ?: return null
            awake(ambientDozing!!.getBoolean(ambient), ambientDozeAmount!!.getFloat(ambient))
        } catch (e: ReflectiveOperationException) {
            faceFailed("doze state unreadable", e)
            null
        }
    }

    private var lastFrameHidden = false
    private var gestureHidden = false

    /** [stackHidden] now; false before the stack's first frame, and after any failure. */
    private fun hiddenNow(): Boolean {
        if (!ready) return false
        val stack = stack.get() ?: return false
        return try {
            stackHidden(stackState!!.getInt(stack), awake() == true, ready)
        } catch (e: ReflectiveOperationException) {
            faceFailed("notification rows unreadable", e)
            false
        } catch (e: RuntimeException) {
            faceFailed("notification rows unreadable", e)
            false
        }
    }

    /**
     * After ScrimController.applyState, where Android sets the scrims' alphas for the state it is in
     * (both ScrimController.transitionTo and applyAndDispatchState call it, then animate to them):
     * clear behind and notifications where [scrimsCleared]. Read on SystemUI's main thread, as
     * Android's; nothing else of the controller changes.
     */
    fun clearScrims(controller: Any) {
        val state = (scrimState!!.get(controller) as Enum<*>).name
        if (!scrimsCleared(state, ready, bouncer.dressed(), scrimQs!!.getFloat(controller), scrimFullShade!!.getFloat(controller))) return
        scrimBehind!!.setFloat(controller, 0f)
        scrimNotifications!!.setFloat(controller, 0f)
    }

    /** When the face attaches: Android's keyguard bar steps aside. */
    private fun onAttached() {
        keyguardBar.get()?.clipBounds = NOTHING
    }

    /** StatusBarState as the stack last knew it; -1 (face unseen) before its first frame. */
    private fun state(): Int = try {
        stack.get()?.let { stackState?.getInt(it) } ?: -1
    } catch (e: IllegalAccessException) {
        faceFailed("status bar state unreadable", e)
        -1
    }

    private fun faceFailed(what: String, e: Exception) {
        if (broken) return
        broken = true
        fail("lock face: $what, Android's face from now on", e)
        face.get()?.visibility = View.GONE
        // Android's cards, clock, indications and lock icon back.
        stack.get()?.invalidate()
        root.get()?.invalidate()
        // Unclipped now, Android's own clip at its next layout.
        keyguardBar.get()?.let {
            it.clipBounds = null
            it.requestLayout()
        }
    }

    private companion object {
        /** An empty clip: a view clipped to it draws nothing (View.setClipBounds), and keeps its touches. */
        val NOTHING = Rect()
    }
}

/**
 * The face itself, over the whole shade window right above the keyguard's root: the banner and its
 * row (BannerView), the padlock and the owner's information, drawn at the alpha Android gives its
 * root, none while the root is not visible ([faceAlpha]), whole under OS 7's entry, none in the
 * shade. It takes no touch: the unlock gesture, a slide up as on the 9900 (guide p. 30: "press the
 * Lock key and slide your finger up", B), stays Android's, under it.
 */
@SuppressLint("ViewConstructor")
private class FaceView(
    context: Context,
    private val images: Images,
    private val keyguard: View,
    private val state: () -> Int,
    private val awake: () -> Boolean,
    private val bouncerShown: () -> Boolean,
    private val failed: (String, Exception) -> Unit,
    private val attached: () -> Unit,
    private val fail: (String, Exception?) -> Unit,
) : FrameLayout(context) {
    private val banner = BannerView(context, images, BannerKind.LOCK, failed)
    private val padlock = images[PADLOCK]
    private var owner: Pair<StaticLayout?, StaticLayout?> = null to null

    // Runs before each frame of the keyguard's window, outside the hooks' guard: nothing escapes.
    private val follow = ViewTreeObserver.OnPreDrawListener {
        try {
            val android = if (keyguard.visibility == VISIBLE) keyguard.alpha else 0f
            val a = faceAlpha(android, state(), awake(), bouncerShown())
            if (a != alpha) alpha = a
        } catch (e: RuntimeException) {
            failed("alpha", e)
        }
        true
    }

    // ACTION_SCREEN_ON, protected: the owner's information read again each time the screen lights.
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = readOwner()
    }

    init {
        alpha = 0f
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, HOME_BANNER_HEIGHT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
        try {
            context.registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
        } catch (e: RuntimeException) {
            fail("lock face: screen broadcasts refused, owner information read once", e)
        }
        readOwner()
        attached()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        try {
            context.unregisterReceiver(screenOn)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "lock face: screen broadcasts were not registered", e)
        }
        super.onDetachedFromWindow()
    }

    /**
     * The owner's information as Android's own lock screen reads it (KeyguardIndicationController,
     * on its background executor): the organisation's, else the user's if enabled. LockPatternUtils
     * asks LockSettingsService, a binder call: on [ownerReader], the result drawn on the main thread.
     */
    private fun readOwner() {
        val context = context
        ownerReader.execute {
            val text = try {
                val cls = Class.forName(LOCK_PATTERN_UTILS)
                val utils = cls.getConstructor(Context::class.java).newInstance(context)
                // Simplification: SystemUI's user, the only one on this phone (Process.myUid's user, PER_USER_RANGE).
                val user = Process.myUid() / 100_000
                cls.getMethod("getDeviceOwnerInfo").invoke(utils) as String?
                    ?: if (cls.getMethod("isOwnerInfoEnabled", Int::class.javaPrimitiveType).invoke(utils, user) == true) {
                        cls.getMethod("getOwnerInfo", Int::class.javaPrimitiveType).invoke(utils, user) as String?
                    } else {
                        null
                    }
            } catch (e: ReflectiveOperationException) {
                fail("lock face: owner information unreadable", e)
                null
            } catch (e: RuntimeException) {
                fail("lock face: owner information unreadable", e)
                null
            }
            post { showOwner(ownerLines(text)) }
        }
    }

    private fun showOwner(lines: OwnerLines) {
        owner = layout(lines.name, Typography.OWNER_NAME, OWNER_NAME_WIDTH, 1) to layout(lines.info, Typography.OWNER_INFO, OWNER_INFO_WIDTH, Int.MAX_VALUE)
        invalidate()
    }

    /** One text of the owner, BBAlpha Sans Condensed bold at [size], in [width], as its outline and its fill. */
    private fun layout(text: String?, size: Int, width: Int, lines: Int): StaticLayout? = text?.let {
        StaticLayout.Builder.obtain(it, 0, it.length, ownerPaint(size), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun ownerPaint(size: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.create(Typography.FAMILY_CONDENSED, Typeface.NORMAL), Typography.BOLD, false)
        textSize = size.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        try {
            val w = width
            val h = height
            padlock.setBounds(w - PADLOCK_RIGHT - padlock.intrinsicWidth, h - PADLOCK_BOTTOM - padlock.intrinsicHeight, w - PADLOCK_RIGHT, h - PADLOCK_BOTTOM)
            padlock.draw(canvas)
            var y = OWNER_TOP.toFloat()
            owner.first?.let { y += drawOwner(canvas, it, y) }
            owner.second?.let {
                canvas.save()
                canvas.clipRect(0f, y, w.toFloat(), y + OWNER_INFO_HEIGHT)
                drawOwner(canvas, it, y)
                canvas.restore()
            }
        } catch (e: RuntimeException) {
            failed("drawing", e)
        }
    }

    /** The outline (stroke=000000, stroke-opacity=0.6, A) under the white fill; returns its height. */
    private fun drawOwner(canvas: Canvas, layout: StaticLayout, y: Float): Int {
        canvas.save()
        canvas.translate(OWNER_X.toFloat(), y)
        val paint = layout.paint
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = OWNER_STROKE
        paint.color = OWNER_STROKE_COLOR
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        layout.draw(canvas)
        canvas.restore()
        return layout.height
    }

    // No touch of its own, nor its children's: everything goes to Android's views under it.
    override fun onInterceptTouchEvent(ev: MotionEvent) = false
    override fun onTouchEvent(event: MotionEvent) = false

    private companion object {
        /** The owner's information is read off the main thread, one read at a time. */
        val ownerReader: ExecutorService by lazy { Executors.newSingleThreadExecutor { Thread(it, "Bold7LockOwner") } }
    }
}
