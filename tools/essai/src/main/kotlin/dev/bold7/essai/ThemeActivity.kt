package dev.bold7.essai

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.ContextMenu
import android.view.Menu
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ListPopupWindow
import android.widget.PopupMenu
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * The framework's own dialogs, menus, lists and text field, as an ordinary app shows them, for the
 * captures of the framework dialogs. In the phone's light theme
 * (Theme.DeviceDefault.Light); [MaterialThemeActivity] shows the same in Theme.Material.Light,
 * [DarkThemeActivity] in Theme.DeviceDefault (dark).
 * Extra "show": alert, alert3 (three buttons), changes (sim-47's "Changes made!", Save, Discard,
 * Cancel, no title), status (a message without title or button), long (labels
 * too long to stand side by side), disabled (as alert, "Delete" disabled), medium (one label too long for its half, the two fitting the
 * row together), one (a single button), view (setView), customtitle (setCustomTitle), list (an
 * alert with a list and no message), date, time, menu (the action bar's overflow), pmenu (a PopupMenu), popup (a ListPopupWindow), context (a
 * context menu in a dialog, as a long press without coordinates gives), single, multi and dspinner
 * (the lists with radio buttons, check boxes, and a spinner in dialog mode), spinner, auto (autocomplete suggestions), or nothing for the text field
 * alone (long press a word for the selection bar):
 * `su -c 'am start -n dev.bold7.essai/.ThemeActivity --es show date'`.
 */
open class ThemeActivity : Activity() {
    private val rows = listOf("To Top", "To Bottom", "Attachments", "Compose", "Search", "Settings", "Refresh", "Select More")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply { setText("Meet at the BlackBerry booth at noon") }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(field) }
        setContentView(page)
        when (intent.getStringExtra("show")) {
            "alert" -> AlertDialog.Builder(this)
                .setTitle("Delete Message?")
                .setMessage("This message will be deleted from your device.")
                .setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .show()
            "alert3" -> AlertDialog.Builder(this)
                .setTitle("My Dialog Box Title")
                .setMessage("This is the body of the dialog box. It asks the user to confirm an action.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Maybe", null)
                .show()
            "changes" -> AlertDialog.Builder(this)
                .setMessage("Changes made!")
                .setPositiveButton("Save", null)
                .setNegativeButton("Discard", null)
                .setNeutralButton("Cancel", null)
                .show()
            "status" -> AlertDialog.Builder(this)
                .setMessage("Device is locked")
                .show()
            "long" -> AlertDialog.Builder(this)
                .setTitle("Delete Message?")
                .setMessage("This message will be deleted from your device.")
                .setPositiveButton("Delete this message from the device", null)
                .setNegativeButton("Keep the message where it is", null)
                .show()
            "disabled" -> AlertDialog.Builder(this)
                .setTitle("Delete Message?")
                .setMessage("This message will be deleted from your device.")
                .setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .create()
                .apply {
                    setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
                    show()
                }
            "medium" -> AlertDialog.Builder(this)
                .setTitle("Delete Message?")
                .setMessage("This message will be deleted from your device.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Move to the trash folder", null)
                .show()
            "one" -> AlertDialog.Builder(this)
                .setTitle("Message Sent")
                .setMessage("Your message has been sent.")
                .setPositiveButton("OK", null)
                .show()
            "view" -> AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(EditText(this).apply { setText("Notes") })
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .show()
            "customtitle" -> AlertDialog.Builder(this)
                .setCustomTitle(TextView(this).apply { text = "Custom Title"; setPadding(31, 31, 31, 0) })
                .setMessage("This message will be deleted from your device.")
                .setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .show()
            "list" -> AlertDialog.Builder(this)
                .setTitle("Message")
                .setItems(rows.take(4).toTypedArray(), null)
                .setNegativeButton("Cancel", null)
                .show()
            "single" -> AlertDialog.Builder(this)
                .setTitle("Status")
                .setSingleChoiceItems(rows.take(5).toTypedArray(), 1, null)
                .setNegativeButton("Cancel", null)
                .show()
            "multi" -> AlertDialog.Builder(this)
                .setTitle("Categories")
                .setMultiChoiceItems(
                    (rows.take(4) + "A category with a label too long to stand on one line of the box").toTypedArray(),
                    booleanArrayOf(true, false, true, false, false), null)
                .setNegativeButton("Cancel", null)
                .show()
            "dspinner" -> page.addView(Spinner(this, Spinner.MODE_DIALOG).apply {
                adapter = ArrayAdapter(this@ThemeActivity, android.R.layout.simple_spinner_item, rows)
                (adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.select_dialog_singlechoice)
                prompt = "Priority"
                page.post { performClick() }
            })
            "date" -> DatePickerDialog(this, null, 2014, 11, 23).show()
            "time" -> TimePickerDialog(this, null, 15, 26, false).show()
            "menu" -> page.post { openOptionsMenu() }
            "context" -> {
                registerForContextMenu(field)
                page.post { openContextMenu(field) }
            }
            "pmenu" -> page.post {
                PopupMenu(this, field).apply { rows.take(4).forEach { menu.add(it) } }.show()
            }
            "popup" -> page.post {
                ListPopupWindow(this).apply {
                    anchorView = field
                    setAdapter(ArrayAdapter(this@ThemeActivity, android.R.layout.simple_list_item_1, rows.take(4)))
                    width = ListPopupWindow.WRAP_CONTENT
                }.show()
            }
            "spinner" -> {
                val spinner = Spinner(this, Spinner.MODE_DROPDOWN)
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, rows)
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                page.addView(spinner)
                page.post { spinner.performClick() }
            }
            "auto" -> {
                val auto = AutoCompleteTextView(this)
                auto.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, rows))
                auto.threshold = 1
                page.addView(auto)
                page.post { auto.setText("S"); auto.showDropDown() }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Eight rows in two groups, with the group dividers that show OS 7's separator (sim-04).
        rows.forEachIndexed { i, row -> menu.add(if (i < 2) 1 else 2, Menu.NONE, i, row) }
        menu.setGroupDividerEnabled(true)
        return true
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        menu.setHeaderTitle("Message")
        rows.take(4).forEach { menu.add(it) }
    }
}

/** The same screens in Theme.Material.Light, the framework's theme without DeviceDefault. */
class MaterialThemeActivity : ThemeActivity()

/** The same screens in Theme.DeviceDefault, the dark theme. */
class DarkThemeActivity : ThemeActivity()
