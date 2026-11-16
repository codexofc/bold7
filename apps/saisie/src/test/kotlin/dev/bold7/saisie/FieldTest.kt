package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals

// InputType and EditorInfo values, written out: the test must break if a constant in Field.kt does.
class FieldTest {
    private val text = 0x1
    private val capSentences = 0x4000

    @Test fun nullNumberPhoneAndDateFieldsAreLeftAlone() {
        for (type in listOf(0, 0x2, 0x2 or 0x10, 0x3, 0x4, 0x14)) assertEquals(Kind.PASS, fieldOf(type, 0).kind, "type $type")
    }

    @Test fun passwordsOfEveryKindAreTypedPlain() {
        for (variation in listOf(0x80, 0x90, 0xe0)) {
            val f = fieldOf(text or variation or capSentences, 0)
            assertEquals(Field(Kind.PLAIN), f, "variation $variation")
        }
    }

    @Test fun incognitoAndAddressesAreTypedPlain() {
        assertEquals(Field(Kind.PLAIN), fieldOf(text or capSentences, 0x1000000))
        assertEquals(Field(Kind.PLAIN), fieldOf(text or 0x10, 0))
    }

    @Test fun emailAddressesGetTheirOwnSpace() {
        assertEquals(Field(Kind.EMAIL), fieldOf(text or 0x20, 0))
        assertEquals(Field(Kind.EMAIL), fieldOf(text or 0xd0, 0))
    }

    @Test fun textTakesItsCapsFromTheFieldAndSubstitutesUnlessAskedNot() {
        assertEquals(Field(Kind.TEXT, capsSentences = true, substitute = true), fieldOf(text or capSentences or 0x50, 0))
        assertEquals(Field(Kind.TEXT, capsWords = true, substitute = true), fieldOf(text or 0x2000 or 0x60, 0))
        assertEquals(Field(Kind.TEXT, capsChars = true, substitute = true), fieldOf(text or 0x1000, 0))
        assertEquals(Field(Kind.TEXT, capsSentences = true), fieldOf(text or capSentences or 0x80000, 0))
        assertEquals(Field(Kind.TEXT, substitute = true), fieldOf(text or 0xb0, 0))
    }
}
