package com.speeduino.manager.desktop

import com.speeduino.manager.desktop.ui.invalidNumbers
import com.speeduino.manager.desktop.ui.isInvalidNumber
import com.speeduino.manager.desktop.ui.isOutOfRange
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsabilityHelpersTest {
    @Test fun invalidNumberAcceptsCommaDecimalAndRejectsText() {
        assertFalse(isInvalidNumber("12,5"))
        assertFalse(isInvalidNumber(""))          // vazio não é "inválido" (campo ainda não preenchido)
        assertTrue(isInvalidNumber("12x"))
        assertTrue(isInvalidNumber("1.5", integerOnly = true))
    }

    @Test fun outOfRangeOnlyFlagsValidIntegersOutsideRange() {
        assertTrue(isOutOfRange("3000", 0..2550))
        assertFalse(isOutOfRange("2550", 0..2550))
        assertFalse(isOutOfRange("abc", 0..2550)) // não numérico é tratado como "número inválido"
    }

    @Test fun invalidNumbersTreatsBlankAsInvalid() {
        assertTrue(invalidNumbers("1", ""))
        assertFalse(invalidNumbers("1", "2.5"))
    }
}
