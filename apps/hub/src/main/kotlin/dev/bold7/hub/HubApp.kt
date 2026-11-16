package dev.bold7.hub

import android.app.Application
import dev.bold7.platform.enableStrictModeIfDebuggable

/**
 * Not direct boot aware: the process, its sources and its listener only start after the first
 * unlock, when the Hub's database in credential-encrypted storage can open.
 */
class HubApp : Application() {
    lateinit var graph: HubGraph
        private set

    /** Null until onCreate ran: the provider is published before, and may be queried meanwhile. */
    val graphOrNull: HubGraph? get() = if (::graph.isInitialized) graph else null

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        super.onCreate()
        graph = HubGraph(this).also { it.start() }
    }
}
