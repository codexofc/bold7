package dev.bold7.hub.data

/**
 * How Messages asks apps/hub for mail, through the Hub provider's call, READ_HUB
 * required and refused while locked like every call (HubContract). The accounts, their sealed
 * passwords and the network are apps/hub's only: Messages has no INTERNET and never holds a
 * password beyond the setup screen's field and the one call that hands it over.
 *
 * - [METHOD_ACCOUNTS]: the mail accounts, [EXTRA_IDS], [EXTRA_ADDRESSES] and [EXTRA_SIGNATURES]
 *   ("" when Use Auto Signature is off) in the same order; and [EXTRA_BROWSER], the providers whose
 *   accounts this build signs in through the browser (OAuth), none without a client.
 * - [METHOD_SIGNATURE]: the account whose id is the arg signs with [EXTRA_SIGNATURE], none without it.
 * - [METHOD_BROWSER_BEGIN]: sign-in of [EXTRA_ADDRESS] through the browser: [EXTRA_URL], the page
 *   Messages opens in the system's browser, when [MailResult.OK]; apps/hub listens for its redirect.
 * - [METHOD_BROWSER_FINISH]: once back from the browser: waits for the sign-in and both servers'
 *   check, then [EXTRA_RESULT] and [EXTRA_ID] as [METHOD_ADD]; [MailResult.CANCELLED] when the
 *   browser never came back with a code.
 * - [METHOD_ADD]: [EXTRA_ADDRESS], [EXTRA_PASSWORD], and for a domain apps/hub does not know
 *   [EXTRA_IMAP_HOST], [EXTRA_IMAP_PORT], [EXTRA_SMTP_HOST], [EXTRA_SMTP_PORT]; both servers are
 *   logged in to before anything is kept. Answers [EXTRA_RESULT], and [EXTRA_ID] when [MailResult.OK].
 * - [METHOD_REMOVE]: the account whose id is the arg, its password and its items forgotten.
 * - [METHOD_OPEN]: the mail item named by HubContract.EXTRA_ACCOUNTS and EXTRA_KEYS (one key): its
 *   headers and text ([EXTRA_FROM]… [EXTRA_TEXT], at most [TEXT_MAX] characters), or [EXTRA_RESULT].
 * - [METHOD_SEND]: from the account [EXTRA_ACCOUNT], to [EXTRA_TO] and [EXTRA_CC] ([address]
 *   each), [EXTRA_SUBJECT], [EXTRA_TEXT], and for a reply [EXTRA_IN_REPLY_TO] and
 *   [EXTRA_REFERENCES]: sent over SMTP before it answers [EXTRA_RESULT].
 *
 * Mark Read, Mark Unread and Delete of a mail item are HubContract's own calls: apps/hub carries
 * them to the server too.
 */
object MailContract {
    const val METHOD_ACCOUNTS = "mailAccounts"
    const val METHOD_ADD = "mailAdd"
    const val METHOD_REMOVE = "mailRemove"
    const val METHOD_OPEN = "mailOpen"
    const val METHOD_SEND = "mailSend"
    const val METHOD_SIGNATURE = "mailSignature"
    const val METHOD_BROWSER_BEGIN = "mailBrowserBegin"
    const val METHOD_BROWSER_FINISH = "mailBrowserFinish"

    const val EXTRA_RESULT = "result"
    const val EXTRA_ID = "id"
    const val EXTRA_IDS = "ids"
    const val EXTRA_ADDRESSES = "addresses"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_PASSWORD = "password"
    const val EXTRA_IMAP_HOST = "imapHost"
    const val EXTRA_IMAP_PORT = "imapPort"
    const val EXTRA_SMTP_HOST = "smtpHost"
    const val EXTRA_SMTP_PORT = "smtpPort"
    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_FROM = "from"
    const val EXTRA_TO = "to"
    const val EXTRA_CC = "cc"
    const val EXTRA_REPLY_TO = "replyTo"
    const val EXTRA_SUBJECT = "subject"
    const val EXTRA_DATE = "date"
    const val EXTRA_MESSAGE_ID = "messageId"
    const val EXTRA_IN_REPLY_TO = "inReplyTo"
    const val EXTRA_REFERENCES = "references"
    const val EXTRA_TEXT = "text"
    const val EXTRA_SIGNATURES = "signatures"
    const val EXTRA_SIGNATURE = "signature"
    const val EXTRA_BROWSER = "browser"
    const val EXTRA_URL = "url"
    /** The account's own address, for Reply All to leave it out. */
    const val EXTRA_OWN = "own"

    /** A mail's text crosses the binder up to this many characters (its 1 MB per process); longer, it is cut. */
    const val TEXT_MAX = 200_000

    /** "email" or "email Name": an address has no space. */
    fun address(email: String, name: String?): String = if (name.isNullOrBlank()) email else "$email $name"

    /** [address]'s parts: the email, then the name or null. */
    fun parse(packed: String): Pair<String, String?> {
        val i = packed.indexOf(' ')
        return if (i < 0) packed to null else packed.substring(0, i) to packed.substring(i + 1)
    }
}

/** What a mail call answers in [MailContract.EXTRA_RESULT]. */
enum class MailResult {
    OK,

    /** The address or the app password refused by a server. */
    AUTH,

    /** A domain apps/hub does not know: its servers are to be typed. */
    SERVERS,

    /** A server's certificate the system does not trust, or for another name. */
    CERTIFICATE,

    /** No network, a server that does not answer, or one that failed. */
    NETWORK,

    /** Locked, an account or a message it does not know, an account already there. */
    REFUSED,

    /** No browser sign-in for this address: another provider, or no OAuth client in this build. */
    UNAVAILABLE,

    /** Back from the browser without a sign-in: closed, refused by the user, or too late. */
    CANCELLED,
}
