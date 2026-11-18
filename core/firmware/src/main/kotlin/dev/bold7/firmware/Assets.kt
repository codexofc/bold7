package dev.bold7.firmware

import java.io.File
import java.io.IOException

/**
 * The blocks of tools/import-assets.sh, from the firmware tree to the layout of assets-gen/: the
 * same names, the same bytes for what the script copies, the same pixels for what ImageMagick
 * makes (9-patches, crops, GIF to PNG, resizes). Every block that reads only the firmware is
 * ported; clock and calendar without their one image cut from a capture of the simulator, and
 * sons copies the sounds as Android reads them.
 */
internal class Assets(
    private val tree: File,
    private val out: File,
    private val iconTable: String,
    private val soundTable: String,
    /** Typing's AOSP lists (apps/saisie/src/main/assets/dictionary), for [saisie]; none: its word lists are not made. */
    private val dictionaries: File? = null,
) {
    private val theme = File(tree, "theme/net_rim_theme_BlackBerry7_640x480_b")
    private val bs1 = File(tree, "theme/net_rim_theme_BlackBerry7_640x480_bs1")

    /** App icons the file lacks (the simulator has no YouTube icon): the launcher draws those with its template. */
    val skipped = mutableListOf<String>()

    fun all(progress: (Float) -> Unit) {
        // import-assets.sh's order.
        val blocks = listOf(
            ::polices, ::accueil, ::profils, ::connexions, ::options, ::icones, ::sons, ::ui, ::dialogues, ::messages, ::phone,
            ::clock, ::calendar, ::contacts, ::calculatrice, ::memopad, ::tasks, ::pictures, ::saisie, ::bandeau, ::systeme, ::fonds,
            ::help,
        )
        blocks.forEachIndexed { i, b -> b(); progress((i + 1f) / blocks.size) }
    }

    private fun need(f: File): File = if (f.isFile) f else throw IOException("missing ${f.relativeTo(tree)} in the firmware")

    private fun copy(src: File, rel: String) = put(out, rel, need(src).readBytes())

    private fun image(src: File) = Png.decode(need(src).readBytes())

    private fun png(img: Image, rel: String) = put(out, rel, Png.encode(img))

    /** _bs1 first, then _b, as the messages and phone blocks look an image up. */
    private fun either(name: String) = File(bs1, name).takeIf { it.isFile } ?: File(theme, name)

    private fun ninepatch(src: File, rel: String, x0: Int, x1: Int, y0: Int, y1: Int) = png(ninepatch(image(src), x0, x1, y0, y1), rel)

    /** magick -crop WxH+X+Y +repage PNG32: */
    private fun crop(src: File, rel: String, x: Int, y: Int, w: Int, h: Int) = png(image(src).crop(x, y, w, h), rel)

    /**
     * The help of the 9900 in British English (docs/BBOS7-VISUEL.md, section 39) under help/: each page
     * decoded from RIM's YK compression against the module's side data ([YkDictionary]), as
     * `<page>.html`; its style sheets, pictures and search index as they are; and, under help/theme/,
     * the theme's category icons the pages name as `theme://cat_…`. Nothing of it is in a public build:
     * it is made here, on the user's phone, from the user's file.
     */
    fun help() {
        val src = File(tree, "help/$HELP/en_GB/9900_series")
        if (!src.isDirectory) { skipped += "help"; return } // Help then shows only Bold7's own section
        val side = try {
            YkDictionary.load(need(File(src, "sidedata.data")).readBytes())
        } catch (e: YkAdaptiveException) { // the simulator's help (docs/YK.md, section 7)
            skipped += "help"; return
        }
        for (f in src.listFiles().orEmpty().sortedBy { it.name }) when {
            f.name.endsWith(".html.ykz") -> put(out, "help/" + f.name.removeSuffix(".ykz"), side.decode(f.readBytes()))
            f.name != "sidedata.data" -> copy(f, "help/${f.name}")
        }
        for (f in theme.listFiles { f -> f.name.startsWith("cat_") && f.name.endsWith(".png") }.orEmpty())
            copy(f, "help/theme/${f.name}")
    }

    fun polices() {
        // Unlike import-assets.sh, the fonts come out with their line metrics fixed, as the
        // module's build makes them (tools/ttf_metrics.py): this is what layer.img holds.
        val src = File(tree, "fonts/net_rim_font_latin_truetype")
        need(File(src, "BBAlphaSans.ttf"))
        val fonts = src.listFiles { f -> f.name.startsWith("BBAlpha") && f.name.endsWith(".ttf") }.orEmpty().sortedBy { it.name }
        for (f in fonts) put(out, "module/system/fonts/${f.name}", TrueType.fixLineMetrics(f.readBytes()))
        for (fw in FALLBACKS) {
            val f = File(tree, "fonts/net_rim_font_$fw")
            copy(f, "module/system/fonts-os7/${f.name}")
        }
    }

    fun accueil() {
        val dir = "launcher/res/drawable-nodpi"
        for (name in HOME_IMAGES) {
            val src = if (name.startsWith("bs1/")) File(bs1, name.removePrefix("bs1/") + ".png") else File(theme, "$name.png")
            val res = name.removePrefix("bs1/").lowercase().replace('~', '_').replace('-', '_')
            copy(src, "$dir/home_$res.png")
        }
        ninepatch(File(theme, "icon_underlay~focus.png"), "$dir/home_focus.9.png", 6, 83, 6, 88)
    }

    fun icones() {
        val dir = "launcher/res/drawable-nodpi"
        for (line in iconTable.lineSequence()) {
            if (line.startsWith("#") || line.isBlank()) continue
            // import-assets.sh reads it with `read`: tabs are whitespace there, empty fields vanish.
            val fields = line.trimEnd('\r').split('\t').filter { it.isNotEmpty() }
            if (fields.size < 4) throw IOException("bbos7_icons.tsv: short line $line")
            val (icon, source) = fields[2] to fields[3]
            if (!source.startsWith("bbos7-9900/")) throw IOException("bbos7_icons.tsv: $icon from $source, not from the firmware")
            val src = safeFile(tree, source.removePrefix("bbos7-9900/"))
            if (src.isFile) copy(src, "$dir/$icon.png") else skipped += icon
        }
        for (n in listOf("underlay", "mask", "overlay")) copy(File(theme, "shortcut_$n.png"), "$dir/bbos7_shortcut_$n.png")
    }

    fun ui() {
        val dir = "ui/res/drawable-nodpi"
        val essai = "essai/res/drawable-nodpi"
        // Full menu and dialog boxes; buttons; the search field (BBOS7-VISUEL §4, §12, sim-19, sim-57).
        ninepatch(File(theme, "border_menu.png"), "$dir/ui_menu.9.png", 18, 21, 18, 21)
        ninepatch(File(theme, "border_popup.png"), "$dir/ui_popup.9.png", 18, 21, 18, 21)
        ninepatch(File(theme, "border_button.png"), "$dir/ui_button.9.png", 20, 39, 26, 96)
        ninepatch(File(theme, "border_button~focus.png"), "$dir/ui_button_focus.9.png", 20, 39, 26, 96)
        ninepatch(File(theme, "border_find.png"), "$dir/ui_find.9.png", 13, 17, 13, 16)
        png(image(File(theme, "background_find_field.png")).crop(0, 0, 640, 60), "$dir/ui_find_background.png")
        png(image(File(theme, "background_list_header.png")).crop(0, 0, 640, 56), "$dir/ui_list_header.png")
        copy(File(theme, "net_rim_system_Check-icons27x27.png"), "$dir/ui_check.png")
        copy(File(theme, "arrow_choice.png"), "$dir/ui_choice_arrow.png")
        copy(File(theme, "arrow_choice~focus.png"), "$dir/ui_choice_arrow_focus.png")
        ninepatch(File(theme, "border_inplace.png"), "$dir/ui_choice_list.9.png", 10, 14, 10, 14)
        ninepatch(File(theme, "border_spinboxbutton~focus.png"), "$dir/ui_choice_chosen.9.png", 20, 39, 22, 49)
        for (f in listOf("question", "exclamation", "information", "error")) copy(File(theme, "dialog_$f.png"), "$dir/ui_dialog_$f.png")
        copy(File(bs1, "sm_full_menu.png"), "$dir/ui_sm_full_menu.png")
        copy(File(tree, "images/net_rim_bb_sms/smileys_27x27.png"), "$dir/ui_smileys_27.png")
        copy(File(theme, "arrow_menu.png"), "$dir/ui_menu_arrow.png")
        for (f in listOf("open", "mark_opened", "search", "compose", "switch_apps")) copy(File(bs1, "sm_$f.png"), "$essai/ui_sm_$f.png")
        for (f in listOf("delete", "to_top", "to_bottom")) copy(File(theme, "sm_$f.png"), "$essai/ui_sm_$f.png")
    }

    fun profils() {
        val dir = "launcher/res/drawable-nodpi"
        for ((src, name) in PROFILES) {
            copy(File(theme, "net_rim_bb_profiles_app.$src.png"), "$dir/profile_$name.png")
            copy(File(theme, "net_rim_bb_profiles_app.$src~focus.png"), "$dir/profile_${name}_focus.png")
        }
    }

    fun connexions() {
        val dir = "launcher/res/drawable-nodpi"
        copy(File(theme, "net_rim_Manage_Connections-icons40x40.png"), "$dir/connections_icons.png")
        ninepatch(File(theme, "border_groupboxfull.png"), "$dir/connections_box.9.png", 6, 13, 6, 59)
        ninepatch(File(theme, "border_groupboxtitlebar.png"), "$dir/connections_title.9.png", 6, 13, 6, 59)
    }

    fun options() {
        val dir = "launcher/res/drawable-nodpi"
        for ((name, src) in OPTIONS) copy(File(theme, "$src.png"), "$dir/opt_$name.png")
        ninepatch(File(theme, "border_button~disabled.png"), "$dir/opt_button_disabled.9.png", 20, 39, 26, 96)
        ninepatch(File(theme, "border_form.png"), "$dir/opt_field.9.png", 4, 4, 4, 4)
        copy(File(bs1, "net_rim_WlanSignalLevel-icons58x27.png"), "$dir/opt_wifi_signal.png")
        copy(File(theme, "wi-fi_lock_icon.png"), "$dir/opt_wifi_lock.png")
        copy(File(theme, "net_rim_bluetooth_device-icons42x42.png"), "$dir/opt_bt_devices.png")
        // Home Screen Preferences' Downloads Folder (sim-284): Home's folder and a folder's, at 30 px.
        png(Resize.resize(image(File(theme, "up_folder.png")), 30, 30), "$dir/opt_home_folder.png")
        png(Resize.resize(image(File(theme, "folder.png")), 30, 30), "$dir/opt_folder.png")
    }

    /**
     * The sources sounds.tsv names, under sounds/<cod>/<path> as import-assets.sh copies them, and
     * under module/product/media/audio/ as the module lays them: copied, never re-encoded, since
     * Android reads the firmware's AAC and WAV as they are and picks the extractor by sniffing the
     * bytes, never by the name. A name under ui/ is fixed by
     * Android (MediaActionSound, SettingsProvider's defaults) and kept, its .ogg holding the AAC;
     * any other takes the source's extension (Sonar.flac of the table is the WAV, Sonar.wav).
     */
    fun sons() {
        for (line in soundTable.lineSequence()) {
            if (line.startsWith("#") || line.isBlank()) continue
            val fields = line.trimEnd('\r').split('\t')
            if (fields.size < 3) throw IOException("sounds.tsv: short line $line")
            val (dest, source) = fields
            val data = need(safeFile(File(tree, "sounds"), source)).readBytes()
            put(out, "sounds/$source", data)
            val name = if (dest.startsWith("ui/")) dest else dest.substringBeforeLast('.') + "." + source.substringAfterLast('.')
            put(out, "module/product/media/audio/$name", data)
        }
    }

    fun dialogues() {
        val dir = "android/res/drawable-nodpi"
        // The dialog box and the full menu, with their content band and optical bounds.
        png(ninepatchPad(image(File(theme, "border_popup.png")), 18, 21, 18, 21, 5, 34, 3, 32), "$dir/bold7_box.9.png")
        png(ninepatchPad(image(File(theme, "border_menu.png")), 18, 21, 18, 21, 4, 35, 19, 29), "$dir/bold7_menu.9.png")
        ninepatch(File(theme, "border_button.png"), "$dir/bold7_button.9.png", 20, 39, 26, 96)
        ninepatch(File(theme, "border_button~focus.png"), "$dir/bold7_button_focus.9.png", 20, 39, 26, 96)
        // The choice field's open list, content 1 to 23 both ways; its chosen value, all content.
        val inplace = ninepatch(image(File(theme, "border_inplace.png")), 10, 14, 10, 14)
        for (i in 2..24) { inplace[i, 26] = BLACK; inplace[26, i] = BLACK }
        png(inplace, "$dir/bold7_inplace.9.png")
        val chosenSrc = image(File(theme, "border_spinboxbutton~focus.png"))
        val chosen = ninepatch(chosenSrc, 20, 39, 22, 49)
        for (x in 1..chosenSrc.width) chosen[x, chosenSrc.height + 1] = BLACK
        for (y in 1..chosenSrc.height) chosen[chosenSrc.width + 1, y] = BLACK
        png(chosen, "$dir/bold7_chosen.9.png")
        val check = File(theme, "net_rim_system_Check-icons27x27.png")
        for ((name, x, y) in CHECKS) crop(check, "$dir/bold7_$name.png", x, y, 27, 27)
        // The wait indicator's twelve frames (sim-111), and the text selection handle.
        val wait = File(bs1, "net_rim_progress.png")
        for (i in 0 until 12) crop(wait, "$dir/bold7_wait_%02d.png".format(i), i * 27, 0, 27, 27)
        copy(File(theme, "border_trackpad_end.png"), "$dir/bold7_handle.png")
        // The permission request's overlay cannot name the framework overlay's drawables: its copy.
        for (b in listOf("bold7_button.9.png", "bold7_button_focus.9.png"))
            put(out, "permissioncontroller/res/drawable-nodpi/$b", File(out, "$dir/$b").readBytes())
    }

    fun messages() {
        val dir = "messages/res/drawable-nodpi"
        copy(File(theme, "net_rim_Message-icons42x42.png"), "$dir/msg_message_icons.png")
        copy(File(bs1, "net_rim_Phone-icons42x42.png"), "$dir/msg_phone_icons.png")
        png(Gif.decode(need(File(theme, "navigation_up_arrow.gif")).readBytes()), "$dir/msg_scroll_up.png")
        png(Gif.decode(need(File(theme, "navigation_down_arrow.gif")).readBytes()), "$dir/msg_scroll_down.png")
        for (img in MESSAGES_SM) copy(either("$img.png"), "$dir/msg_$img.png")
        copy(File(theme, "net_rim_Message-icons32x32.png"), "$dir/msg_thread_icons.png")
        copy(File(bs1, "default_avatar.png"), "$dir/msg_avatar.png")
        copy(File(theme, "smiley_button.png"), "$dir/msg_smiley.png")
        ninepatch(File(theme, "border_reply_field.png"), "$dir/msg_field.9.png", 13, 17, 13, 16)
        copy(File(theme, "cat_app_options_sub.png"), "$dir/msg_cat_display.png")
        copy(File(theme, "cat_app_messages_inbox_settings.png"), "$dir/msg_cat_inbox.png")
        copy(File(theme, "cat_app_messages_email_acc_management.png"), "$dir/msg_cat_accounts.png")
        copy(File(theme, "cat_app_sms_n_mms.png"), "$dir/msg_cat_texts.png")
        copy(File(tree, "images/net_rim_bb_sms/smileys_45x45.png"), "$dir/msg_smileys_45.png")
        copy(File(theme, "smiley_button_focus_indicator.png"), "$dir/msg_smiley_focus.png")
        copy(File(bs1, "sm_add_emoticon.png"), "$dir/msg_sm_add_emoticon.png")
        copy(File(theme, "send_email.png"), "$dir/msg_send_email.png")
        ninepatch(File(theme, "border_form.png"), "$dir/msg_form.9.png", 4, 4, 4, 4)
        for (img in listOf("attachment_generic_picture", "attachment_generic_video", "attachment_music", "attachment_contact", "attachment_calendar", "attachment_unknown"))
            copy(File(theme, "$img.png"), "$dir/msg_$img.png")
        png(Resize.resize(image(File(theme, "folder.png")), 52, 52), "$dir/msg_folder.png")
        for (img in listOf("play", "pause", "stop", "previous", "play_focus", "pause_focus", "stop_focus", "previous_focus", "next_disabled"))
            copy(File(theme, "$img.png"), "$dir/msg_player_$img.png")
        copy(File(tree, "images/net_rim_bb_facebook_resource/res/facebook_ML_40.png"), "$dir/msg_facebook_icons.png")
        copy(File(tree, "images/net_rim_bb_twitter_resources_small/res/twitter_ML_icons40.png"), "$dir/msg_twitter_icons.png")
    }

    fun phone() {
        for ((src, name) in PHONE) copy(either("$src.png"), "phone/res/drawable-nodpi/ph_$name.png")
    }

    /** Without clk_alarm_add.png, cut from a capture of the simulator (sim-153), not the firmware. */
    fun clock() {
        val dir = "clock/res/drawable-nodpi"
        val faces = File(tree, "images/net_rim_bb_clock_faces_480/net/rim/device/apps/internal/clock/face/analog/images/size370")
        val lib = File(tree, "images/net_rim_bb_clock_lib/net/rim/device/apps/internal/clock/images/size316")
        copy(File(faces, "face_370.png"), "$dir/clk_face.png")
        copy(File(lib, "face_316.png"), "$dir/clk_watch_face.png")
        for (i in 0..7) {
            val a = i * 6
            copy(File(faces, "hour_370_$a.png"), "$dir/clk_hour_$i.png")
            copy(File(faces, "minute_370_$a.png"), "$dir/clk_minute_$i.png")
            copy(File(lib, "minute_316_$a.png"), "$dir/clk_watch_minute_$i.png")
            copy(File(lib, "second_316_$a.png"), "$dir/clk_watch_second_$i.png")
        }
        ninepatch(File(theme, "border_clock_button.png"), "$dir/clk_bar.9.png", 6, 6, 17, 20)
        ninepatch(File(theme, "border_clock_button~focus.png"), "$dir/clk_bar_focus.9.png", 6, 6, 17, 20)
        copy(File(bs1, "Alarm_Indicator.png"), "$dir/clk_alarm_on.png")
        copy(File(theme, "Snooze_Indicator.png"), "$dir/clk_snoozed.png")
        copy(File(theme, "net_rim_alarm_reminder.png"), "$dir/clk_reminder.png")
        copy(File(theme, "timer_start.png"), "$dir/clk_start.png")
        copy(File(theme, "timer_stop.png"), "$dir/clk_stop.png")
        copy(File(bs1, "timer_lap.png"), "$dir/clk_lap.png")
        copy(File(bs1, "timer_reset.png"), "$dir/clk_reset.png")
        val lcd = File(tree, "images/net_rim_bb_clock_faces_480/net/rim/device/apps/internal/clock/face/lcddigital/images")
        for (set in listOf("t", "", "j", "m")) for (d in (0..9).map { "$it" } + "colon")
            copy(File(lcd, "$set$d.png"), "$dir/clk_lcd_${set.ifEmpty { "s" }}_$d.png")
    }

    /** Without cal_allday.png, cut from a capture of the simulator (sim-224), not the firmware. */
    fun calendar() {
        val dir = "calendar/res/drawable-nodpi"
        copy(File(theme, "net_rim_calendar_reminder.png"), "$dir/cal_reminder.png")
        crop(File(theme, "net_rim_Reminder_Status-icons19x19.png"), "$dir/cal_bell.png", 0, 0, 19, 19)
        // magick -fill '#E6E6E6' -colorize 100: the colour replaced, the alpha kept; -flop mirrors.
        val arrow = image(File(tree, "images/net_rim_bb_otaupgrade/arrow_more.png"))
        val right = Image(arrow.width, arrow.height, IntArray(arrow.argb.size) { (arrow.argb[it] and 0xFF000000.toInt()) or 0xE6E6E6 })
        png(right, "$dir/cal_arrow_right.png")
        png(Image(right.width, right.height).also { m -> for (y in 0 until m.height) for (x in 0 until m.width) m[x, y] = right[m.width - 1 - x, y] }, "$dir/cal_arrow_left.png")
        copy(File(theme, "save.png"), "$dir/cal_save.png")
    }

    fun contacts() {
        val dir = "contacts/res/drawable-nodpi"
        copy(File(tree, "images/net_rim_bb_feeds_lib/res/bbfeeds_new_contact.png"), "$dir/cnt_new.png")
        ninepatch(File(theme, "border_groupboxfull.png"), "$dir/cnt_box.9.png", 6, 13, 6, 59)
        ninepatch(File(theme, "border_groupboxtitlebar.png"), "$dir/cnt_title.9.png", 6, 13, 6, 59)
        ninepatch(File(theme, "border_form.png"), "$dir/cnt_field.9.png", 4, 4, 4, 4)
        for ((src, name) in CONTACTS) copy(either("$src.png"), "$dir/cnt_$name.png")
    }

    fun calculatrice() {
        val dir = "calculator/res/drawable-nodpi"
        for (p in listOf("left", "middle", "right")) copy(File(theme, "calculator_display_$p.png"), "$dir/calc_display_$p.png")
        for ((src, name) in listOf("" to "key", "_numeric" to "key_digit", "~focus" to "key_focus", "~active" to "key_active"))
            ninepatch(File(theme, "calculator_key_landscape$src.png"), "$dir/calc_$name.9.png", 14, 47, 24, 60)
    }

    /** The form's field and the short menu's images, shared by MemoPad and Tasks. */
    private fun pim(dir: String, prefix: String, images: List<String>) {
        ninepatch(File(theme, "border_form.png"), "$dir/${prefix}_field.9.png", 4, 4, 4, 4)
        for (src in images) copy(either("$src.png"), "$dir/${prefix}_$src.png")
    }

    fun memopad() {
        val dir = "memopad/res/drawable-nodpi"
        pim(dir, "mem", listOf("sm_new", "sm_delete", "sm_open", "sm_edit", "sm_home", "sm_switch_apps"))
        copy(File(theme, "save.png"), "$dir/mem_save.png")
    }

    fun tasks() {
        val dir = "tasks/res/drawable-nodpi"
        pim(dir, "tsk", listOf("sm_new_task", "sm_delete", "sm_open", "sm_mark_completed"))
        copy(File(theme, "net_rim_Task_Status-icons27x27.png"), "$dir/tsk_status.png")
        copy(File(theme, "net_rim_Priority-icons9x27.png"), "$dir/tsk_priority.png")
        copy(File(theme, "net_rim_task_reminder.png"), "$dir/tsk_reminder.png")
        copy(File(theme, "save.png"), "$dir/tsk_save.png")
    }

    fun pictures() {
        for (src in PICTURES) copy(either("$src.png"), "pictures/res/drawable-nodpi/pic_${src.lowercase().replace('.', '_')}.png")
    }

    /**
     * OS 7's part of Typing (import-assets.sh saisie): its English word substitution, and the lines
     * of the French list that Typing's AOSP list lacks. Not the English list's part: the merged list
     * it comes from was made from AOSP's British and American lists, which Typing's own English
     * list (both merged) no longer gives apart.
     */
    fun saisie() {
        val dir = "saisie/assets/os7"
        val ling = File(tree, "ling")
        put(out, "$dir/substitution/en.tsv", Typing.substitution(need(File(ling, "net_rim_tid_dynamic_ling_data_misspellings_de/misspelling_words_de.txt")).readBytes()).toByteArray())
        val aosp = dictionaries?.let { File(it, "fr.tsv") } ?: return
        if (!aosp.isFile) throw IOException("missing $aosp")
        val parts = File(ling, "net_rim_tid_dynamic_ling_data_french").listFiles { f -> WRD.matches(f.name) }.orEmpty().sortedBy { it.name }
        if (parts.isEmpty()) throw IOException("missing the French word list in the firmware")
        put(out, "$dir/dictionary/fr.tsv", Typing.part(parts.map { it.readBytes() }, aosp.readText()).toByteArray())
    }

    /** One frame of a strip (frames side by side, states in rows), and an optional crop of it. */
    private fun frame(strip: File, w: Int, h: Int, col: Int, row: Int, name: String, sub: IntArray? = null) {
        var img = image(strip).crop(col * w, row * h, w, h)
        if (sub != null) img = img.crop(sub[2], sub[3], sub[0], sub[1])
        png(img, "sysui/res/drawable-nodpi/banner_$name.png")
    }

    fun bandeau() {
        val battery = File(theme, "net_rim_Indicator_BatteryLevel-icons50x25.png")
        for (i in 0..11) {
            frame(battery, 50, 25, i, 0, "battery_$i")
            frame(battery, 50, 25, i, 2, "battery_charging_$i")
        }
        for (i in 1..5) frame(File(theme, "lightning_bolt_0$i.png"), 18, 29, 0, 0, "bolt_$i")
        val signal = File(theme, "net_rim_Ribbon_SignalLevel-icons49x19.png")
        frame(signal, 49, 19, 0, 0, "signal_off")
        frame(signal, 49, 19, 1, 0, "signal_x")
        for (i in 0..5) frame(signal, 49, 19, i + 2, 0, "signal_$i")
        frame(signal, 49, 19, 8, 0, "signal_sos")
        for (i in 1..5) frame(File(theme, "net_rim_Ribbon_WlanSignalLevel-icons30x24.png"), 30, 24, i, 0, "wifi_$i")
        frame(File(theme, "net_rim_Ribbon_Roaming-icons28x28.png"), 28, 28, 1, 0, "roaming")
        frame(File(theme, "net_rim_Ribbon_GPS-icons43x24.png"), 43, 24, 0, 0, "gps_on")
        frame(File(theme, "net_rim_Ribbon_GPS-icons43x24.png"), 43, 24, 1, 0, "gps_active")
        frame(File(theme, "net_rim_Ribbon_NFC-icons12x24.png"), 12, 24, 1, 0, "nfc")
        frame(File(theme, "net_rim_bluetooth_banner-icons15x25.png"), 15, 25, 0, 0, "bluetooth_on")
        frame(File(theme, "net_rim_bluetooth_banner-icons15x25.png"), 15, 25, 1, 0, "bluetooth_connected")
        frame(File(theme, "net_rim_Alarm_Indicator-icons21x21.png"), 21, 21, 0, 0, "alarm")
        frame(File(bs1, "net_rim_Phone-icons19x19.png"), 19, 19, 11, 0, "call")
        frame(File(bs1, "net_rim_Phone-icons19x19.png"), 19, 19, 12, 0, "call_muted")
        val profile = File(theme, "net_rim_Ribbon_Profile-icons43x43.png")
        for ((col, name) in listOf(1 to "vibrate", 2 to "silent", 4 to "phone_only"))
            frame(profile, 43, 43, col, 0, "profile_$name", intArrayOf(43, 33, 0, 5))
        val count = File(theme, "net_rim_Ribbon_UnreadCount-icons33x29.png")
        for ((col, name) in listOf(0 to "email", 4 to "calendar", 6 to "sms", 14 to "missed_call", 7 to "other"))
            frame(count, 33, 29, col, 0, "count_$name")
    }

    fun systeme() {
        val dir = "sysui/res/drawable-nodpi"
        copy(File(theme, "banner_background.png"), "$dir/lock_banner_background.png")
        copy(File(theme, "banner_divider.png"), "$dir/lock_banner_divider.png")
        copy(File(theme, "indicator_bar_background.png"), "$dir/lock_indicator_bar.png")
        // One speaker per profile, in the strip's order; frame 6, the custom profile, is not made.
        listOf("normal", "vibrate", "silent", "loud", "phone_only", "alerts_off", null, "medium").forEachIndexed { i, name ->
            if (name != null) crop(File(theme, "net_rim_Ribbon_Profile-icons43x43.png"), "$dir/lock_profile_$name.png", i * 43, 0, 43, 43)
        }
        copy(File(theme, "net_rim_KeyLockSystem.png"), "$dir/lock_padlock.png")
        copy(File(theme, "phone_emergency-icons32x32.png"), "$dir/lock_emergency.png")
        for (f in listOf("bg", "0") + (1..15).map { "%03d".format(it) } + listOf("loudspeaker", "headset", "handset"))
            copy(File(theme, "vol-$f.png"), "$dir/vol_$f.png")
        copy(File(bs1, "vol-bluetooth.png"), "$dir/vol_bluetooth.png")
        for (f in listOf("loudspeaker", "headset", "handset", "bluetooth")) copy(File(bs1, "vol-$f-mute.png"), "$dir/vol_${f}_mute.png")
        copy(File(theme, "osicon_hourglass.gif"), "$dir/shutdown_busy.gif")
    }

    /** The 9900's default home image and its 14 wallpapers, 720x720 (Resize.cover). */
    fun fonds() {
        val sources = listOf(File(theme, "background_homescreen.png")) +
            listOf("02a", "02b").flatMap { s ->
                File(tree, "images/net_rim_bb_medialoader_backgrounds_640x480_${s}_t/pictures").listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }
            }
        if (sources.size != 15) throw IOException("expected 15 wallpapers in the firmware, found ${sources.size}")
        for (f in sources) {
            val name = if (f.name == "background_homescreen.png") "Default" else f.name.removeSuffix(".png")
            put(out, "wallpapers/$name.png", Png.encode(Resize.cover(image(f), 720), alpha = false))
        }
    }

    private companion object {
        val WRD = Regex("french\\.\\d{3}\\.wrd")
        const val BLACK = 0xFF000000.toInt()

        // The theme's name, then the resource's: Normal is "default", Vibrate Only "discreet"...
        val PROFILES = listOf(
            "default" to "normal", "discreet" to "vibrate", "quiet" to "silent", "loud" to "loud", "medium" to "medium",
            "phoneonly" to "phone_only", "off" to "alerts_off",
        )

        // Resource name to theme image, the rows of Options' lists (import-assets.sh says which capture).
        val OPTIONS = listOf(
            "sound" to "cat_sound_ringtones", "connectivity" to "cat_connectivity", "display" to "cat_display",
            "typing" to "cat_typing_input", "device" to "cat_device", "calls" to "cat_callmanagement", "security" to "cat_security",
            "accessibility" to "cat_accessibility", "phone_ringtone" to "cat_sound_phone_ringtone",
            "profile_sounds" to "cat_sound_current_profile", "contact_sounds" to "cat_sound_contact",
            "profiles" to "cat_sound_profile_management", "screen_display" to "cat_display_screen_components",
            "date_time" to "cat_display_date_time", "lock_message" to "cat_device_owner", "language" to "cat_typing_input_language",
            "typing_style" to "cat_text_input", "trackpad" to "cat_typing_input_trackpad_sensitivity",
            "touchscreen" to "cat_typing_input_touchscreen_sensitivity", "keyboard" to "cat_typing_input_keyboard",
            "dictionary" to "cat_typing_input_custom_dictionary", "substitution" to "cat_typing_input_autotext",
            "spell_check" to "cat_typing_input_spellcheck", "messages" to "cat_app_messages", "reminders" to "cat_help_calendar",
            "mobile" to "cat_mobile_network", "wifi" to "cat_connectivity_wifi", "bluetooth" to "cat_connectivity_bluetooth",
            "nfc" to "cat_app_nfc", "about" to "cat_device_about_device", "memory" to "cat_device_memory",
            "status" to "cat_device_status_info", "password" to "cat_security_password", "encryption" to "cat_security_encryption",
            "device_advanced" to "cat_device_advanced",
            // The Setup application's green check (sim-375), drawn by the Setup Wizard.
            "checkmark" to "cat_toolkit_checkmark",
            // Display > Home Screen Preferences (sim-446).
            "home_screen" to "cat_display_home_screen_preferences",
        )

        // The 27x27 cells of net_rim_system_Check-icons27x27: columns normal and focus.
        val CHECKS = listOf(
            Triple("check_off", 0, 0), Triple("check_off_focus", 27, 0), Triple("check_on", 0, 27), Triple("check_on_focus", 27, 27),
            Triple("radio_off", 0, 108), Triple("radio_off_focus", 27, 108), Triple("radio_on", 0, 135), Triple("radio_on_focus", 27, 135),
        )

        val PHONE = listOf(
            "background_calldisplay" to "background", "activecall_avatar" to "avatar", "incomingcall_button_base_l" to "bar",
            "incomingcall_button_l~focus" to "bar_focus", "phone_speakerphone" to "speaker", "phone_handset" to "handset",
            "phone_mute" to "mute", "phone_unmute" to "unmute", "phone_hold" to "hold", "phone_resume" to "resume",
            "phone_conference" to "add", "phone_silence_ringtones" to "ringer_off", "activecall_button_mute_p" to "mute_on",
            "net_rim_Phone_Receiver-icons58x33" to "receiver", "volume_icon" to "volume_icon",
            "net_rim_Phone_Volume-icons110x6" to "volume", "net_rim_Phone-icons42x42" to "log_icons", "sm_call" to "sm_call",
            "sm_drop_call" to "sm_drop_call", "sm_mute" to "sm_mute", "sm_unmute" to "sm_unmute", "sm_speaker_on" to "sm_speaker_on",
            "sm_speaker_off" to "sm_speaker_off", "sm_new_call" to "sm_new_call", "address_book_shortcut" to "contacts",
        )

        val CONTACTS = listOf(
            "sm_call", "sm_sms", "sm_compose", "sm_view_contact", "sm_add_contact", "sm_edit", "sm_delete", "sm_copy", "sm_select",
        ).map { it to it } + listOf("addressbook_contact" to "picture", "edit" to "edit", "save" to "save")

        val PICTURES = listOf(
            "noart_picture_icon", "noart_picture_icon_thumbnail", "net_rim_bb_camera.Camera", "sm_play", "sm_set_as_wallpaper",
            "sm_set_as_caller_id", "sm_send_or_share_image", "sm_delete", "sm_zoom_in", "sm_rotate",
        )
        val FALLBACKS = listOf(
            "latin_truetype/BBGlobalSans.ttf", "latin_truetype/BBGlobalSans-Bold.ttf", "latin_truetype/BBSemitic.ttf",
            "thai/BBThai.ttf", "indic/UniversOTSDevanagari.ttf", "indic/UniversOTSBengali.ttf", "indic/UniversOTSGujarati.ttf",
            "indic/UniversOTSTamil.ttf", "japanese_droid/DroidSansJapanese.ttf",
            "monotype_hkscs/MYuenC-SemiBold-Big5HKSCS.ttf", "global_type/BBGlobalSansFallback.ttf",
        )

        // HOME_IMAGES of import-assets.sh, in its order (its comments say what each one is).
        val HOME_IMAGES = listOf(
            "banner_background", "banner_divider", "indicator_bar_background",
            "tabmanager_header_line_top", "tabmanager_header_line_bottom",
            "net_rim_Indicator_BatteryLevel-icons50x25", "net_rim_Ribbon_SignalLevel-icons49x19",
            "net_rim_Ribbon_WlanSignalLevel-icons30x24", "net_rim_Ribbon_Profile-icons43x43",
            "net_rim_Ribbon_UnreadCount-icons33x29", "net_rim_Alarm_Indicator-icons21x21",
            "lightning_bolt_01", "lightning_bolt_02", "lightning_bolt_03", "lightning_bolt_04", "lightning_bolt_05",
            "banner_search_button", "banner_voice_button",
            "homescreen_mask_p", "folder", "net_rim_bb_ribbon_app",
            "background_folder_1", "background_folder_2", "background_folder_3", "background_folder_4",
            "background_folder_5", "background_folder_bottom", "icon_move_underlay", "new_overlay",
            "sm_launch", "sm_delete", "sm_add_folder", "sm_delete_folder",
            "bs1/banner_background~focus",
            "bs1/sm_mark_favourite", "bs1/sm_unmark_favourite", "bs1/sm_move", "bs1/sm_move_to_folder", "bs1/sm_hide",
            "bs1/sm_unhide", "bs1/sm_folder_open", "bs1/sm_rename_folder",
        ) + (1..20).map { "bs1/folder%02d".format(it) }

        val MESSAGES_SM = listOf(
            "sm_open", "sm_mark_read", "sm_mark_unread", "sm_mark_prior_opened", "sm_compose", "sm_search", "sm_delete",
            "sm_select_folder", "sm_chat_send", "sm_clear_input", "sm_cancel_generic", "sm_address_book_app", "sm_reply",
            "sm_forward", "sm_reply_all", "sm_save_draft", "sm_add_attachment", "sm_open_attachment",
            "sm_save_image_browser", "sm_zoom_in", "sm_options_generic",
        )
    }
}

/**
 * A theme image as an Android 9-patch: a 1 px transparent border whose black lines mark the band
 * that stretches, [x0] to [x1] and [y0] to [y1] of the image, inclusive (import-assets.sh ninepatch).
 */
internal fun ninepatch(src: Image, x0: Int, x1: Int, y0: Int, y1: Int): Image {
    val img = Image(src.width + 2, src.height + 2)
    for (y in 0 until src.height) System.arraycopy(src.argb, y * src.width, img.argb, (y + 1) * img.width + 1, src.width)
    for (x in x0..x1) img[x + 1, 0] = 0xFF000000.toInt()
    for (y in y0..y1) img[0, y + 1] = 0xFF000000.toInt()
    return img
}

/**
 * [ninepatch] with its content band, [cl] to [cr] and [ct] to [cb], in black on the bottom and right
 * lines, and the rest of those lines red: the layout bounds Android gives as optical insets
 * (import-assets.sh ninepatch_pad).
 */
internal fun ninepatchPad(src: Image, x0: Int, x1: Int, y0: Int, y1: Int, cl: Int, cr: Int, ct: Int, cb: Int): Image {
    val img = ninepatch(src, x0, x1, y0, y1)
    val w = src.width
    val h = src.height
    val red = 0xFFFF0000.toInt()
    for (x in 1..w) img[x, h + 1] = if (x in cl + 1..cr + 1) 0xFF000000.toInt() else red
    for (y in 1..h) img[w + 1, y] = if (y in ct + 1..cb + 1) 0xFF000000.toInt() else red
    return img
}
