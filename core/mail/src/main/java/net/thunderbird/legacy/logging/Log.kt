// Bold7: reduced from Thunderbird for Android's legacy/logging (Apache License 2.0). Upstream
// forwards to net.thunderbird.core.logging.Logger; here only the static methods the protocol code
// calls remain, forwarded to [sink], with three cuts so that nothing read from the network or the
// account reaches a log, whatever K9MailLib.setDebug() says:
// - below WARN, nothing is forwarded (wire traffic, commands, responses);
// - the format string goes out without its arguments (addresses, subjects, server data); every
//   WARN and ERROR format in the vendored code is a plain literal;
// - an exception goes out as its class name, never its message.
package net.thunderbird.legacy.logging

object Log {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        /** [error]: the class name of the exception, if any. */
        fun log(level: Level, format: String, error: String?)
    }

    @Volatile
    var sink: Sink? = null

    @JvmStatic fun v(message: String?, vararg args: Any?) = Unit
    @JvmStatic fun v(t: Throwable?, message: String?, vararg args: Any?) = Unit
    @JvmStatic fun d(message: String?, vararg args: Any?) = Unit
    @JvmStatic fun d(t: Throwable?, message: String?, vararg args: Any?) = Unit
    @JvmStatic fun i(message: String?, vararg args: Any?) = Unit
    @JvmStatic fun i(t: Throwable?, message: String?, vararg args: Any?) = Unit
    @JvmStatic fun w(message: String?, vararg args: Any?) = log(Level.WARN, null, message)
    @JvmStatic fun w(t: Throwable?, message: String?, vararg args: Any?) = log(Level.WARN, t, message)
    @JvmStatic fun e(message: String?, vararg args: Any?) = log(Level.ERROR, null, message)
    @JvmStatic fun e(t: Throwable?, message: String?, vararg args: Any?) = log(Level.ERROR, t, message)

    private fun log(level: Level, t: Throwable?, format: String?) {
        sink?.log(level, format.orEmpty(), t?.javaClass?.name)
    }
}
