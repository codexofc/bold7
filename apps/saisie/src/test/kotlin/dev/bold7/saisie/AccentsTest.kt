package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals

class AccentsTest {
    @Test fun slidingLeftFromUGivesUUmlautAsInTheGuide() {
        assertEquals('ü', accent('u', -1))
        assertEquals('Ü', accent('U', -1))
    }

    @Test fun slidingRightWalksTheListAndComesBackToTheLetter() {
        assertEquals('è', accent('e', 1))
        assertEquals('é', accent('e', 2))
        assertEquals('e', accent('e', 5))
        assertEquals('ë', accent('e', -1))
        assertEquals('ç', accent('c', 1))
    }

    @Test fun lettersWithoutAccentsStay() {
        assertEquals('t', accent('t', 1))
        assertEquals('3', accent('3', -2))
    }
}
