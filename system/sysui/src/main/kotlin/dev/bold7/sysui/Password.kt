package dev.bold7.sysui

// The device password of OS 7, set from Options > Security > Password (sim-60, sim-299, C), pure
// (PasswordTest). Only a password: the 9900 had no PIN of its own and no pattern. SystemUI asks it
// in OS 7's boxes (PasswordScreen) because only SystemUI, among the processes Bold7 runs code in,
// holds ACCESS_KEYGUARD_SECURE_STORAGE (signature|setup on both lines): the password typed goes
// from the box to LockPatternUtils.setLockCredential in this process, never to the launcher.

/** What Options asks for, the broadcast's [PASSWORD_TASK_EXTRA]. */
enum class PasswordTask { SET, CHANGE, REMOVE }

/** One box of the flow, by what it asks. */
enum class PasswordPrompt { CURRENT, NEW, CONFIRM }

/**
 * The boxes [task] asks, in order (simulator 7.1.0.318, C): "Enter Password:", "New Password:",
 * "Verify New Password:" for Change Password (sim-452 to sim-454); Set Password, and Save with
 * Enable ticked, the last two (sim-461, sim-462); Save with Enable cleared, the first alone (sim-459).
 */
fun passwordPrompts(task: PasswordTask): List<PasswordPrompt> = when (task) {
    PasswordTask.SET -> listOf(PasswordPrompt.NEW, PasswordPrompt.CONFIRM)
    PasswordTask.CHANGE -> listOf(PasswordPrompt.CURRENT, PasswordPrompt.NEW, PasswordPrompt.CONFIRM)
    PasswordTask.REMOVE -> listOf(PasswordPrompt.CURRENT)
}

/**
 * Why a new password is refused before Android sees it. OS 7 says why for a sequence and a
 * verification that differs, then asks the new password again; it says nothing for one too short
 * ("abc") or one character repeated ("aaaa") and starts over from the first box (simulator, C;
 * sim-455 to sim-457).
 */
enum class PasswordProblem(val said: Boolean) {
    TOO_SHORT(false),
    REPEATED(false),
    /** Android's limit alone: refused as OS 7 refuses what it does not say (D). */
    CHARACTERS(false),
    SEQUENCE(true),
    MISMATCH(true),
}

/** Android refuses a password under 4 characters (LockscreenCredential.validateBasicRequirements). */
const val PASSWORD_MIN = 4

/**
 * What is wrong with [p] as a new password, or null. Android's own limits first (at least
 * [PASSWORD_MIN] characters, printable ASCII only: LockscreenCredential refuses anything else),
 * then OS 7's (simulator, C): 4 characters at least as well ("abc" refused, "zxqv" taken), not one
 * character repeated ("aaaa"), not a sequence ("1234" on sim-62, "abcd").
 */
fun passwordProblem(p: CharArray): PasswordProblem? = when {
    p.size < PASSWORD_MIN -> PasswordProblem.TOO_SHORT
    p.any { it.code !in 32..126 } -> PasswordProblem.CHARACTERS
    isRun(p, 0) -> PasswordProblem.REPEATED
    isRun(p, 1) || isRun(p, -1) -> PasswordProblem.SEQUENCE
    else -> null
}

/** Whether [p] is a run of characters [step] apart, case aside: up (1), down (-1, D) or the same (0). */
fun isRun(p: CharArray, step: Int): Boolean =
    p.size >= 2 && (1 until p.size).all { p[it].lowercaseChar() - p[it - 1].lowercaseChar() == step }

/** The confirmation must be the new password again; compared without making a String of either. */
fun confirmProblem(new: CharArray, confirm: CharArray): PasswordProblem? = if (new.contentEquals(confirm)) null else PasswordProblem.MISMATCH

/**
 * Who may ask: the launcher alone (BroadcastReceiver.getSentFromPackage, its identity shared), on
 * top of the signature permission the system already checked; and a task it knows.
 */
fun passwordTaskFor(sender: String?, task: String?): PasswordTask? =
    if (sender != LAUNCHER_PACKAGE) null else PasswordTask.entries.firstOrNull { it.name == task }

/** Options' broadcasts to SystemUI, behind [LOCK_SETTINGS_PERMISSION] (signature, declared by the launcher and held by it alone). */
const val PASSWORD_ACTION = "dev.bold7.action.DEVICE_PASSWORD"
const val LOCK_SETTINGS_PERMISSION = "dev.bold7.permission.LOCK_SETTINGS"
const val PASSWORD_TASK_EXTRA = "task"

/**
 * Message on Lock Screen (Options > Display): an ordered broadcast answered with the owner's name
 * and information as Android keeps them ([OWNER_NAME_EXTRA], [OWNER_INFO_EXTRA] in the result),
 * after writing them when the request carries them. Not secret: the lock screen shows them.
 */
const val LOCK_MESSAGE_ACTION = "dev.bold7.action.LOCK_MESSAGE"
const val OWNER_NAME_EXTRA = "name"
const val OWNER_INFO_EXTRA = "info"
