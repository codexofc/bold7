package dev.bold7.sysui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordTest {
    private fun problem(s: String) = passwordProblem(s.toCharArray())

    @Test fun onlyAPasswordAndTheCurrentOneFirst() {
        assertEquals(listOf(PasswordPrompt.NEW, PasswordPrompt.CONFIRM), passwordPrompts(PasswordTask.SET))
        assertEquals(listOf(PasswordPrompt.CURRENT, PasswordPrompt.NEW, PasswordPrompt.CONFIRM), passwordPrompts(PasswordTask.CHANGE))
        assertEquals(listOf(PasswordPrompt.CURRENT), passwordPrompts(PasswordTask.REMOVE))
    }

    // Android throws on fewer than 4 characters: refused here, before it is asked.
    @Test fun underFourCharactersIsTooShort() {
        assertEquals(PasswordProblem.TOO_SHORT, problem(""))
        assertEquals(PasswordProblem.TOO_SHORT, problem("bo7"))
        assertNull(problem("bol7"))
    }

    // LockscreenCredential refuses anything outside printable ASCII.
    @Test fun onlyPrintableAscii() {
        assertEquals(PasswordProblem.CHARACTERS, problem("blé blé"))
        assertEquals(PasswordProblem.CHARACTERS, problem("tab\there"))
        assertNull(problem("a b~c!"))
    }

    // sim-62 and the simulator (C): "1234" and "abcd" are sequences, said; "aaaa" is refused unsaid.
    @Test fun sequencesAndRepeatsAreRefused() {
        for (s in listOf("1234", "abcd", "ABCDE", "9876", "dcba")) assertEquals(PasswordProblem.SEQUENCE, problem(s), s)
        for (s in listOf("aaaa", "1111", "zZzZ")) assertEquals(PasswordProblem.REPEATED, problem(s), s)
        for (s in listOf("bold", "zxqv", "1243", "acegi", "1235", "aaab")) assertNull(problem(s), s)
    }

    // What OS 7 says, then the new password again; what it does not say starts over.
    @Test fun onlyTheSequenceAndTheMismatchAreSaid() {
        assertEquals(setOf(PasswordProblem.SEQUENCE, PasswordProblem.MISMATCH), PasswordProblem.entries.filter { it.said }.toSet())
    }

    @Test fun oneCharacterIsNoRun() {
        assertFalse(isRun("a".toCharArray(), 0))
        assertTrue(isRun("ab".toCharArray(), 1))
    }

    @Test fun theConfirmationMustMatch() {
        assertNull(confirmProblem("bold7x".toCharArray(), "bold7x".toCharArray()))
        assertEquals(PasswordProblem.MISMATCH, confirmProblem("bold7x".toCharArray(), "bold7X".toCharArray()))
    }

    // Only the launcher, its identity shared; an unknown task is no task.
    @Test fun onlyTheLauncherAsks() {
        assertEquals(PasswordTask.CHANGE, passwordTaskFor(LAUNCHER_PACKAGE, "CHANGE"))
        assertNull(passwordTaskFor(null, "SET"))
        assertNull(passwordTaskFor("com.android.shell", "SET"))
        assertNull(passwordTaskFor(LAUNCHER_PACKAGE, "WIPE"))
        assertNull(passwordTaskFor(LAUNCHER_PACKAGE, null))
    }
}
