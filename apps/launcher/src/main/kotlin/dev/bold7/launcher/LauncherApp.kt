package dev.bold7.launcher

import android.app.Application
import dev.bold7.platform.enableStrictModeIfDebuggable

class LauncherApp : Application() {
    val graph by lazy { LauncherGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        super.onCreate()
    }
}

val android.content.Context.graph: LauncherGraph get() = (applicationContext as LauncherApp).graph
