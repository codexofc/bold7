package dev.bold7.launcher.hub

import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.showcaseHub
import dev.bold7.showcase.pick

// The showcase (core/showcase) as the home reads the Hub: the made-up texts, emails and missed
// calls give the banner's counts, the icons' stars and the list of notifications. Nothing here
// reaches the Hub, Telephony, the contacts or a mail account, and nothing is ever written.

private const val MESSAGES = "dev.bold7.messages"
private const val PHONE = "dev.bold7.phone"

/** The made-up inbox; the [format] is the phone's own. Writes go nowhere. */
class ShowcaseHubReader(private val format: () -> HubFormat) : HubReader {
    override fun read(): HubSnapshot {
        val hub = showcaseHub(System.currentTimeMillis())
        // Bold7's own Messages and Phone hold the texts and the calls: the star of an icon lands on them.
        return HubSnapshot(hub.items, smsApp = MESSAGES, dialer = PHONE, accounts = hub.accounts, demo = true)
    }

    override fun format() = format.invoke()

    override fun write(method: String, keys: List<ItemKey>) = Unit
}

/**
 * The showcase's reader while [on] says so (read at each call, off the main thread), the Hub's
 * otherwise: [real] is not called, nor built, while it is on.
 */
class SwitchedHubReader(private val on: () -> Boolean, private val real: () -> HubReader, private val showcase: HubReader) : HubReader {
    private fun current() = pick(on(), real, { showcase })
    override fun read() = current().read()
    override fun format() = current().format()
    override fun write(method: String, keys: List<ItemKey>) = current().write(method, keys)
}
