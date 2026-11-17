package dev.bold7.clock

import android.content.Context
import android.graphics.Bitmap
import dev.bold7.ui.themeBitmap
import dev.bold7.ui.themeImage

/** The firmware's images the Clock draws (tools/import-assets.sh clock), read by name at their pixels. Off the main thread. */
class ClockImages(private val context: Context) {
    private fun bitmap(name: String): Bitmap = themeBitmap(context, name)

    // Eight numbered images per hand: the name is the index.
    private fun eight(prefix: String) = (0..7).map { bitmap("$prefix$it") }

    val face = FaceView.Images(
        bitmap("clk_face"), eight("clk_hour_"), eight("clk_minute_"), themeImage(context, "clk_bar"), themeImage(context, "clk_bar_focus"),
        bitmap("clk_alarm_on"), bitmap("clk_alarm_add"), bitmap("clk_snoozed"),
        listOf("t", "s", "j", "m").map { size -> FaceView.Lcd((0..9).map { bitmap("clk_lcd_${size}_$it") }, bitmap("clk_lcd_${size}_colon")) },
    )

    val watch = WatchView.Images(
        bitmap("clk_watch_face"), eight("clk_watch_minute_"), eight("clk_watch_second_"),
        themeImage(context, "clk_bar"), themeImage(context, "clk_bar_focus"),
        bitmap("clk_start"), bitmap("clk_stop"), bitmap("clk_lap"), bitmap("clk_reset"),
    )

    val reminder: Bitmap = bitmap("clk_reminder")
}
