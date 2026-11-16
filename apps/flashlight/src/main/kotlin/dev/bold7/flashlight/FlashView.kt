package dev.bold7.flashlight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.Focus
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.textPaint
import kotlin.math.min

/**
 * The Flashlight's page (D: the 9900 had none): the dark ground of the calculator's page, a big
 * round button at the centre, grey and ringed in the blue of OS 7's focus when off, lit white
 * and yellow when on, its state in words under it. The trackpad's focus is always on it (it is
 * the only control), so the ring is always there. A tap switches.
 */
class FlashView(context: Context, private val onToggle: () -> Unit) : View(context) {
    companion object {
        val BACKGROUND = 0xFF262626.toInt()
        const val RING = 6f
        const val NOTE_SIZE = Typography.TEXT
        const val NOTE_GAP = 40f
    }

    private val ground = fillPaint(BACKGROUND)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Focus.RowTop; style = Paint.Style.STROKE; strokeWidth = RING }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND }
    private val note = textPaint(NOTE_SIZE, Typography.REGULAR, 0xFFCCCCCC.toInt(), align = Paint.Align.CENTER)
    private var lamp = Lamp()
    private var text = ""

    fun bind(lamp: Lamp, text: String) {
        this.lamp = lamp
        this.text = text
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) onToggle()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, ground)
        val cx = w / 2
        val cy = h / 2 - NOTE_GAP
        val r = min(w, h) * 0.26f
        val lit = lamp.on && lamp.status == Lamp.Status.ON
        disc.shader = if (lit) RadialGradient(cx, cy, r, 0xFFFFFFFF.toInt(), 0xFFFFD84A.toInt(), Shader.TileMode.CLAMP)
        else RadialGradient(cx, cy - r / 3, r * 1.4f, 0xFF5A5A5A.toInt(), 0xFF2E2E2E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, disc)
        canvas.drawCircle(cx, cy, r + RING, ring)
        // The power sign: an open circle with a bar.
        glyph.color = if (lit) 0xFF8A6A00.toInt() else if (lamp.flash && !lamp.busy) 0xFFE1E3E5.toInt() else 0xFF777777.toInt()
        val g = r * 0.42f
        canvas.drawArc(cx - g, cy - g, cx + g, cy + g, -60f, 300f, false, glyph)
        canvas.drawLine(cx, cy - g * 1.15f, cx, cy, glyph)
        canvas.drawText(text, cx, cy + r + NOTE_GAP + NOTE_SIZE, note)
    }
}
