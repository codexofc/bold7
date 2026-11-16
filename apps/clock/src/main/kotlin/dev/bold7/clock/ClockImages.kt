package dev.bold7.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable

/** The firmware's images the Clock draws (tools/import-assets.sh clock), decoded at their pixels. Off the main thread. */
class ClockImages(context: Context) {
    private val res = context.resources
    private val options = BitmapFactory.Options().apply { inScaled = false }

    private fun bitmap(name: String): Bitmap {
        val id = res.getIdentifier(name, "drawable", "dev.bold7.clock")
        return requireNotNull(BitmapFactory.decodeResource(res, id, options)) { "clock image $name" }
    }

    @Suppress("DiscouragedApi") // eight numbered images per hand: the name is the index
    private fun eight(prefix: String) = (0..7).map { bitmap("$prefix$it") }

    private val bar: Drawable = requireNotNull(context.getDrawable(R.drawable.clk_bar))
    private val barFocus: Drawable = requireNotNull(context.getDrawable(R.drawable.clk_bar_focus))

    val face = FaceView.Images(
        bitmap("clk_face"), eight("clk_hour_"), eight("clk_minute_"), bar, barFocus,
        bitmap("clk_alarm_on"), bitmap("clk_alarm_add"), bitmap("clk_snoozed"),
        listOf("t", "s", "j", "m").map { size -> FaceView.Lcd((0..9).map { bitmap("clk_lcd_${size}_$it") }, bitmap("clk_lcd_${size}_colon")) },
    )

    val watch = WatchView.Images(
        bitmap("clk_watch_face"), eight("clk_watch_minute_"), eight("clk_watch_second_"),
        requireNotNull(context.getDrawable(R.drawable.clk_bar)), requireNotNull(context.getDrawable(R.drawable.clk_bar_focus)),
        bitmap("clk_start"), bitmap("clk_stop"), bitmap("clk_lap"), bitmap("clk_reset"),
    )

    val reminder: Bitmap = bitmap("clk_reminder")
}
