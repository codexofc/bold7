package dev.bold7.contacts

// A contact's card as a vCard 3.0 text (RFC 2426), pure: what Send Contact Card puts in a message
// (docs/BBOS7-VISUEL.md §30, D). Built from what the form shows of the provider's rows, so nothing
// is kept of the contact: the text exists for the length of the message. No photo (a text message
// or an email body would carry it as a block of letters).

/** The vCard of [stored], CRLF-separated; null when there is no name and no company to call it. */
fun vCard(stored: List<Stored>): String? {
    fun one(k: FieldKey) = stored.firstOrNull { it.key == k }?.value?.trim().orEmpty()
    val title = one(FieldKey.TITLE)
    val first = one(FieldKey.FIRST)
    val last = one(FieldKey.LAST)
    val company = one(FieldKey.COMPANY)
    val shown = listOf(title, first, last).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { company }
    if (shown.isEmpty()) return null
    val lines = ArrayList<String>()
    lines += "BEGIN:VCARD"
    lines += "VERSION:3.0"
    lines += "N:${esc(last)};${esc(first)};;${esc(title)};"
    lines += "FN:${esc(shown)}"
    if (company.isNotEmpty()) lines += "ORG:${esc(company)}"
    for (s in stored) {
        val v = s.value.trim()
        if (v.isEmpty()) continue
        when (s.key) {
            FieldKey.PHONE -> lines += "TEL;TYPE=${phoneParam(s.base)}:${esc(v)}"
            FieldKey.EMAIL -> lines += "EMAIL;TYPE=INTERNET:${esc(v)}"
            FieldKey.ADDRESS -> lines += "ADR;TYPE=HOME:;;${esc(v)};;;;"
            FieldKey.WEBSITE -> lines += "URL:${esc(v)}"
            FieldKey.NOTES -> lines += "NOTE:${esc(v)}"
            FieldKey.BIRTHDAY -> lines += "BDAY:${esc(v)}"
            FieldKey.ANNIVERSARY -> lines += "X-ANNIVERSARY:${esc(v)}"
            else -> Unit
        }
    }
    lines += "END:VCARD"
    return lines.joinToString("\r\n") + "\r\n"
}

private fun phoneParam(b: PhoneBase?) = when (b) {
    PhoneBase.WORK -> "WORK,VOICE"
    PhoneBase.HOME -> "HOME,VOICE"
    PhoneBase.MOBILE -> "CELL,VOICE"
    PhoneBase.PAGER -> "PAGER"
    PhoneBase.FAX -> "WORK,FAX"
    PhoneBase.OTHER, null -> "VOICE"
}

/** RFC 2426's text escapes: backslash, semicolon, comma, and a line break as \n. */
private fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\n").replace("\n", "\\n")
