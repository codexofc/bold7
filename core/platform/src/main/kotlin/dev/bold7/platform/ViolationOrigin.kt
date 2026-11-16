package dev.bold7.platform

/**
 * True when a StrictMode violation is not ours to fix, so it is logged instead of crashing:
 * - raised in another process and handed back through binder: StrictMode deserializes it from the
 *   reply (readAndHandleBinderCallViolations) with the stack of the remote binder thread, whose
 *   bottom is Binder.execTransact (on the Q25, system_server's MediaTek code reads the disk on
 *   every foreground activity change);
 * - raised in our process by MediaTek's code with no frame of ours: its boost framework, loaded in
 *   every app of this ROM, reads the disk on the main thread at each touch.
 * Anything with a dev.bold7 frame stays fatal, and so does the AOSP framework on its own.
 */
// Simplification: a violation raised on one of our own binder threads would read as remote. Those are
// gathered and sent back to the caller rather than handled here, and only the main thread carries
// our listener; extend if a thread serving incoming binder calls ever gets this policy.
internal fun isNotOurs(stack: Array<StackTraceElement>): Boolean =
    stack.none { it.className.startsWith("dev.bold7.") } &&
        stack.any {
            (it.className == "android.os.Binder" && it.methodName.startsWith("execTransact")) ||
                it.className.startsWith("com.mediatek.")
        }
