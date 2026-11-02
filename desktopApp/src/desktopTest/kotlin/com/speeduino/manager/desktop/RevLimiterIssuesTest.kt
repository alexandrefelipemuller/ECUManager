package com.speeduino.manager.desktop

import com.speeduino.manager.desktop.feature.configs.RevLimiterIssue
import com.speeduino.manager.desktop.feature.configs.revLimiterIssues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevLimiterIssuesTest {
    @Test fun validLimitsHaveNoIssues() = assertTrue(revLimiterIssues("7000", "6500").isEmpty())

    @Test fun softAboveHardIsFlagged() =
        assertEquals(setOf(RevLimiterIssue.SOFT_ABOVE_HARD), revLimiterIssues("6000", "6500"))

    @Test fun nonMultipleOfStepIsFlagged() =
        assertTrue(RevLimiterIssue.HARD_NOT_STEP in revLimiterIssues("7050", "6500"))

    @Test fun blankIsOutOfRange() =
        assertTrue(RevLimiterIssue.HARD_OUT_OF_RANGE in revLimiterIssues("", "6500"))
}
