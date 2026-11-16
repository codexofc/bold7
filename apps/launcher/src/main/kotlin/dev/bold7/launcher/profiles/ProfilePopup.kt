package dev.bold7.launcher.profiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.View
import dev.bold7.design.DialogColors
import dev.bold7.design.Focus
import dev.bold7.design.Typography
import dev.bold7.launcher.R
import dev.bold7.ui.FocusDialog
import dev.bold7.ui.Focused
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

/** The name of a profile (strings), and its icon: net_rim_bb_profiles_app.* and its ~focus (A), scaled to the row. */
private fun Profile.name() = when (this) {
    Profile.NORMAL -> R.string.profile_normal
    Profile.VIBRATE -> R.string.profile_vibrate
    Profile.SILENT -> R.string.profile_silent
    Profile.LOUD -> R.string.profile_loud
    Profile.PHONE_ONLY -> R.string.profile_phone_only
    Profile.ALERTS_OFF -> R.string.profile_alerts_off
    Profile.MEDIUM -> R.string.profile_medium
}

private fun Profile.icon() = when (this) {
    Profile.NORMAL -> R.drawable.profile_normal
    Profile.VIBRATE -> R.drawable.profile_vibrate
    Profile.SILENT -> R.drawable.profile_silent
    Profile.LOUD -> R.drawable.profile_loud
    Profile.PHONE_ONLY -> R.drawable.profile_phone_only
    Profile.ALERTS_OFF -> R.drawable.profile_alerts_off
    Profile.MEDIUM -> R.drawable.profile_medium
}

private fun Profile.iconFocused() = when (this) {
    Profile.NORMAL -> R.drawable.profile_normal_focus
    Profile.VIBRATE -> R.drawable.profile_vibrate_focus
    Profile.SILENT -> R.drawable.profile_silent_focus
    Profile.LOUD -> R.drawable.profile_loud_focus
    Profile.PHONE_ONLY -> R.drawable.profile_phone_only_focus
    Profile.ALERTS_OFF -> R.drawable.profile_alerts_off_focus
    Profile.MEDIUM -> R.drawable.profile_medium_focus
}

/**
 * "Select a Profile" (docs/BBOS7-VISUEL.md §8; sim-28, sim-29, gsm 010, C): the dark box, its bold
 * title, a row per profile of [Profile.LIST] with its icon, the current one ticked, the trackpad's
 * focus a blue band, then the grey "Change Sounds and Alerts" button, blue when focused. Laid out
 * by [ProfilePopupLayout]; [focused] is the row (the button after the last), moved by its dialog.
 */
internal class ProfilePopupView(context: Context, private val current: Profile) : View(context), Focused {
    override var focused: Int = Profile.LIST.indexOf(current)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val popup = themeImage(context, "ui_popup")
    private val button = themeImage(context, "ui_button")
    private val buttonFocused = themeImage(context, "ui_button_focus")
    private val icons = Profile.LIST.map { (themeImage(context, it.icon()) as BitmapDrawable).bitmap }
    private val iconsFocused = Profile.LIST.map { (themeImage(context, it.iconFocused()) as BitmapDrawable).bitmap }
    private val names = Profile.LIST.map { context.getString(it.name()) }
    private val buttonText = context.getString(R.string.profile_change_sounds)
    private val title = textPaint(Typography.TEXT, Typography.BOLD, DialogColors.Text)
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.Text)
    private val buttonLabel = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val buttonLabelFocused = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private val line = fillPaint(0xFF43454A.toInt())
    private val band = focusPaint(ProfilePopupLayout.ROW, Focus.RowTop, Focus.RowBottom)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DialogColors.Text
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tickPath = Path()

    var layout: ProfilePopupLayout? = null
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = (listOf(context.getString(R.string.profile_select)) + names + buttonText).joinToString(". ")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout = ProfilePopupLayout(w, Profile.LIST.size)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        // border_popup.png as the dialog's: its border pixels are column 4 and 35, row 2 and 33.
        popup.setBounds(l.left - 4, l.top - 2, l.left + l.width + 4, l.top + l.height + 6)
        popup.draw(canvas)
        canvas.drawText(title.fit(context.getString(R.string.profile_select), l.titleRoom), l.bandLeft.toFloat(), l.titleBaseline.toFloat(), title)
        canvas.drawRect(l.bandLeft.toFloat(), l.titleLine.toFloat(), (l.bandLeft + l.bandWidth).toFloat(), l.titleLine + 1f, line)
        val size = ProfilePopupLayout.ROW
        for (i in Profile.LIST.indices) {
            val top = l.rowTop(i)
            val lit = i == focused
            if (lit) {
                canvas.save()
                canvas.translate(l.bandLeft.toFloat(), top.toFloat())
                canvas.drawRect(0f, 0f, l.bandWidth.toFloat(), size.toFloat(), band)
                canvas.restore()
            }
            val icon = if (lit) iconsFocused[i] else icons[i]
            canvas.drawBitmap(icon, null, android.graphics.Rect(l.iconX, top, l.iconX + ICON, top + ICON), bitmapPaint)
            canvas.drawText(label.fit(names[i], l.nameRoom), l.textX.toFloat(), (top + BASELINE).toFloat(), label)
            if (Profile.LIST[i] == current) {
                // A white tick, 21 x 16 (sim-28, C), at the band's right: drawn, no firmware image found for it (D).
                val x = (l.tickRight - 20).toFloat()
                val y = top + (size - 16) / 2f
                tickPath.rewind()
                tickPath.moveTo(x + 1, y + 9)
                tickPath.lineTo(x + 7, y + 15)
                tickPath.lineTo(x + 20, y + 1)
                canvas.drawPath(tickPath, tick)
            }
        }
        canvas.drawRect(l.bandLeft.toFloat(), l.listLine.toFloat(), (l.bandLeft + l.bandWidth).toFloat(), l.listLine + 1f, line)
        val lit = focused == Profile.LIST.size
        // border_button*.png as the dialog's.
        val image = if (lit) buttonFocused else button
        image.setBounds(l.buttonLeft - 3, l.buttonTop - 3, l.buttonLeft + l.buttonWidth + 3, l.buttonTop + ProfilePopupLayout.BUTTON_HEIGHT + 3)
        image.draw(canvas)
        canvas.drawText((if (lit) buttonLabelFocused else buttonLabel).fit(buttonText, l.buttonRoom), l.buttonLeft + l.buttonWidth / 2f, (l.buttonTop + BUTTON_BASELINE).toFloat(), if (lit) buttonLabelFocused else buttonLabel)
    }

    private companion object {
        /** Under a row's top (Normal: top 135, glyphs' bottom 170, sim-28, C) and under the button's (35, as the dialog's). */
        /** The icon drawn at 52 px (docs/BBOS7-VISUEL.md §8), in a row of 53. */
        const val ICON = 52
        const val BASELINE = 36
        const val BUTTON_BASELINE = 35
    }
}

/**
 * The popup as a window over the home, its focus and its keys those of [FocusDialog]: a touch on
 * a row or the button chooses it, elsewhere closes it. [onProfile] runs after it closes with the
 * profile chosen, [onSettings] with "Change Sounds and Alerts". It hides Android's status bar as
 * the home does. No animation (D).
 */
internal class ProfilePopup(
    context: Context,
    current: Profile,
    private val onProfile: (Profile) -> Unit,
    private val onSettings: () -> Unit,
    onCancel: () -> Unit,
) : FocusDialog<ProfilePopupView>(context, ProfilePopupView(context, current), Profile.LIST.size + 1, hideStatusBar = true, touchOutsideCancels = true) {
    init {
        window?.setTitle(context.getString(R.string.profile_select))
        setOnCancelListener { onCancel() }
    }

    override fun itemAt(x: Float, y: Float): Int? = view.layout?.itemAt(x, y)

    override fun onChosen(item: Int) = if (item == Profile.LIST.size) onSettings() else onProfile(Profile.LIST[item])
}
