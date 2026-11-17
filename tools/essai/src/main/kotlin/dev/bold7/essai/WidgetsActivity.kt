package dev.bold7.essai

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The framework's common widgets on one screen, as an ordinary app shows them, for the captures of
 * wait indicators (three sizes), horizontal bars (determinate with a secondary
 * progress, and indeterminate), a slider, check boxes, radio buttons, switches, a text field with
 * its cursor, and the scroll bar (never faded). In Theme.DeviceDefault.Light;
 * [MaterialWidgetsActivity] in Theme.Material.Light, [DarkWidgetsActivity] in Theme.DeviceDefault.
 * Extra "show": pdialog (a ProgressDialog with its wait indicator), hdialog (a horizontal one),
 * select (a word selected, with its handles), toast:
 * `su -c 'am start -S -n dev.bold7.essai/.WidgetsActivity --es show pdialog'`.
 */
open class WidgetsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply { setText("Meet at the BlackBerry booth at noon") }
        setContentView(framework(this, field))
        field.requestFocus()
        show(this, intent.getStringExtra("show"), field)
    }
}

/** The same screen in Theme.Material.Light. */
class MaterialWidgetsActivity : WidgetsActivity()

/** The same screen in Theme.DeviceDefault (dark). */
class DarkWidgetsActivity : WidgetsActivity()

/**
 * The widgets an app built on AppCompat (Theme.AppCompat.Light) carries in its own APK:
 * AppCompat's check box, radio button, switch, slider and text field; ProgressBar is the
 * framework's, under AppCompat's styles. Same extras as [WidgetsActivity].
 */
class CompatWidgetsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = AppCompatEditText(this).apply { setText("Meet at the BlackBerry booth at noon") }
        val page = column(this).apply {
            addView(progressRow(this@CompatWidgetsActivity))
            addView(bars(this@CompatWidgetsActivity))
            addView(AppCompatSeekBar(this@CompatWidgetsActivity).apply { progress = 30 })
            addView(AppCompatCheckBox(this@CompatWidgetsActivity).apply { text = "Wi-Fi"; isChecked = true })
            addView(AppCompatCheckBox(this@CompatWidgetsActivity).apply { text = "Bluetooth" })
            addView(AppCompatRadioButton(this@CompatWidgetsActivity).apply { text = "Normal"; isChecked = true })
            addView(AppCompatRadioButton(this@CompatWidgetsActivity).apply { text = "Silent" })
            addView(SwitchCompat(this@CompatWidgetsActivity).apply { text = "Mobile Network"; isChecked = true })
            addView(SwitchCompat(this@CompatWidgetsActivity).apply { text = "NFC" })
            addView(field)
            addView(filler(this@CompatWidgetsActivity))
        }
        setContentView(scroll(this, page))
        field.requestFocus()
        show(this, intent.getStringExtra("show"), field)
    }
}

/**
 * The widgets of Material Components (Theme.Material3.Light), all drawn by the library inside the
 * app: circular and linear indicators, slider, check box, radio button, switch, outlined text
 * field, and the pull-to-refresh of androidx (SwipeRefreshLayout), shown refreshing.
 */
class M3WidgetsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val field = TextInputEditText(c).apply { setText("Meet at the BlackBerry booth at noon") }
        val page = column(c).apply {
            addView(LinearLayout(c).apply {
                addView(CircularProgressIndicator(c).apply { isIndeterminate = true })
                addView(ProgressBar(c))
            })
            addView(LinearProgressIndicator(c).apply { progress = 40 })
            addView(LinearProgressIndicator(c).apply { isIndeterminate = true })
            addView(Slider(c).apply { value = 0.3f })
            addView(MaterialCheckBox(c).apply { text = "Wi-Fi"; isChecked = true })
            addView(MaterialCheckBox(c).apply { text = "Bluetooth" })
            addView(MaterialRadioButton(c).apply { text = "Normal"; isChecked = true })
            addView(MaterialRadioButton(c).apply { text = "Silent" })
            addView(MaterialSwitch(c).apply { text = "Mobile Network"; isChecked = true })
            addView(MaterialSwitch(c).apply { text = "NFC" })
            addView(TextInputLayout(c).apply { hint = "Subject"; addView(field) })
            addView(filler(c))
        }
        setContentView(SwipeRefreshLayout(c).apply { addView(scroll(c, page)); isRefreshing = true })
        field.requestFocus()
        show(this, intent.getStringExtra("show"), field)
    }
}

private fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }

private fun scroll(c: Context, page: View) = ScrollView(c).apply { isScrollbarFadingEnabled = false; addView(page) }

private fun progressRow(c: Context) = LinearLayout(c).apply {
    for (style in intArrayOf(android.R.attr.progressBarStyleSmall, android.R.attr.progressBarStyle, android.R.attr.progressBarStyleLarge))
        addView(ProgressBar(c, null, style))
}

private fun bars(c: Context) = LinearLayout(c).apply {
    orientation = LinearLayout.VERTICAL
    addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { progress = 40; secondaryProgress = 70 })
    addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
}

/** Enough rows under the widgets for the screen to scroll, so that the scroll bar shows. */
private fun filler(c: Context) = TextView(c).apply { text = (1..30).joinToString("\n") { "Row $it" } }

private fun framework(c: Context, field: EditText) = scroll(c, column(c).apply {
    addView(progressRow(c))
    addView(bars(c))
    addView(SeekBar(c).apply { progress = 30 })
    addView(CheckBox(c).apply { text = "Wi-Fi"; isChecked = true })
    addView(CheckBox(c).apply { text = "Bluetooth" })
    addView(RadioButton(c).apply { text = "Normal"; isChecked = true })
    addView(RadioButton(c).apply { text = "Silent" })
    addView(Switch(c).apply { text = "Mobile Network"; isChecked = true })
    addView(Switch(c).apply { text = "NFC" })
    addView(field)
    addView(EditText(c).apply { hint = "Subject" })
    addView(filler(c))
})

@Suppress("DEPRECATION") // ProgressDialog: deprecated, still shown by many apps.
private fun show(a: Activity, what: String?, field: EditText) {
    when (what) {
        "pdialog" -> ProgressDialog(a).apply { setMessage("Please wait..."); show() }
        "hdialog" -> ProgressDialog(a).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setTitle("Downloading")
            setMessage("Music.mp3")
            max = 100
            progress = 40
            show()
        }
        // A word selected as a long press selects it: the handles and the selection bar.
        "select" -> field.post { field.setSelection(0, 4); field.performLongClick() }
        "toast" -> android.widget.Toast.makeText(a, "Message saved as draft.", android.widget.Toast.LENGTH_LONG).show()
    }
}
