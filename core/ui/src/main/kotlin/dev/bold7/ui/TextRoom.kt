package dev.bold7.ui

import dev.bold7.design.ListDimens
import dev.bold7.design.MenuDimens

/**
 * The width each component leaves its texts, pure, in px of the view: what [fit] cuts them to, so
 * that no text leaves its frame nor runs under an icon, a value or an arrow beside it (TextRoomTest). The margins are the ones the components already draw with; a text that fits its
 * room is drawn as before. Where a margin has no capture, it is the one on the other side (D).
 */
object TextRoom {
    /** Two-line row ([ListItemView]): from its text's x to the margin at the right. */
    fun listRow(width: Int, iconed: Boolean): Float =
        (width - (if (iconed) ListDimens.ICON_TEXT_X else ListDimens.TEXT_X) - ListDimens.TEXT_X).coerceAtLeast(0).toFloat()

    /** One-line row ([LineItemView]) with its text at [textX]. */
    fun lineRow(width: Int, textX: Int): Float = (width - textX - LineItemView.TEXT_X).coerceAtLeast(0).toFloat()

    /** Centred header ([ListHeaderView]): the margins of the rows on both sides. */
    fun header(width: Int): Float = (width - 2 * ListDimens.TEXT_X).coerceAtLeast(0).toFloat()

    /** Screen title ([TitleBar]), an Options page's too. */
    fun title(width: Int): Float = (width - 2 * TitleBar.TITLE_X).coerceAtLeast(0).toFloat()

    /** A check box's label ([CheckBoxView]): up to the margin before the box. */
    fun checkLabel(width: Int): Float =
        (width - ListDimens.CHECK_RIGHT - ListDimens.CHECK_BOX - ListDimens.TEXT_X - ListDimens.TEXT_X).coerceAtLeast(0).toFloat()

    /** The x of a label that follows a box at the left ([CheckBoxView.boxFirst], sim-228: box at 12, label at 44). */
    const val CHECK_LABEL_AFTER_BOX = ListDimens.CHECK_RIGHT + ListDimens.CHECK_BOX + 5

    /** A check box's label when the box comes first: from [CHECK_LABEL_AFTER_BOX] to the right margin. */
    fun checkLabelAfterBox(width: Int): Float = (width - CHECK_LABEL_AFTER_BOX - ListDimens.TEXT_X).coerceAtLeast(0).toFloat()

    /** A choice row's label ([ChoiceView]) left of its field, which starts at [fieldLeft]. */
    fun choiceLabel(fieldLeft: Int): Float = (fieldLeft - 2 * ListDimens.TEXT_X).coerceAtLeast(0).toFloat()

    /**
     * A choice field's width ([ChoiceView]): the usual one, wider when its value of [valueWidth] needs it
     * (OS 7's fields take their value's width, sim-170, D), never so wide that it covers the label of
     * [labelWidth] or leaves less than the usual one.
     */
    fun choiceField(rowWidth: Int, labelWidth: Float, valueWidth: Float): Int {
        val wanted = maxOf(ListDimens.CHOICE_FIELD_WIDTH, kotlin.math.ceil(valueWidth).toInt() + CHOICE_VALUE_EXTRA)
        val most = rowWidth - ListDimens.CHECK_RIGHT - 2 * ListDimens.TEXT_X - kotlin.math.ceil(labelWidth).toInt()
        return maxOf(ListDimens.CHOICE_FIELD_WIDTH, minOf(wanted, most))
    }

    /** The value inside a button or a list entry of [width] px: [VALUE_PAD] on each side. */
    fun inside(width: Int): Float = (width - 2 * VALUE_PAD).coerceAtLeast(0).toFloat()

    /** A date and time row's label ([DateTimeRow]): left of its values, [valuesWidth] wide, 8 px off the right edge. */
    fun dateLabel(width: Int, valuesWidth: Float): Float =
        (width - 8 - valuesWidth - 2 * ListDimens.TEXT_X).coerceAtLeast(0f)

    /** A full menu label in a panel whose right border is at [right]: before the margin, or before the submenu's arrow. */
    fun menuLabel(right: Int, submenu: Boolean): Float =
        (right - MenuDimens.TEXT_X - if (submenu) MenuDimens.SUBMENU_ARROW_WIDTH + MenuDimens.PAD_RIGHT else MenuDimens.PAD_RIGHT).coerceAtLeast(0).toFloat()

    /** A submenu entry in a panel [width] px wide. */
    fun submenuLabel(width: Int): Float = (width - MenuDimens.SUB_TEXT_X - MenuDimens.SUB_PAD_RIGHT).coerceAtLeast(0).toFloat()

    /** How wide a full menu's panel may be: the view's width less a strip of the page (D). */
    fun menuRight(viewWidth: Int): Int = viewWidth - MENU_STRIP

    /** What a dialog's button label gets inside a button [buttonWidth] wide. */
    fun button(buttonWidth: Int): Float = (buttonWidth - 2 * dev.bold7.design.DialogDimens.BUTTON_PAD).coerceAtLeast(0).toFloat()

    const val VALUE_PAD = 6

    /** What a choice field keeps besides its value: [VALUE_PAD] each side, the arrow's 20 px and its 13 px margin on the right (45, as Analogue's 127 px in 172, sim-170). */
    const val CHOICE_VALUE_EXTRA = 2 * VALUE_PAD + ListDimens.CHOICE_ARROW_WIDTH + ListDimens.CHOICE_ARROW_RIGHT
    const val MENU_STRIP = 40
}
