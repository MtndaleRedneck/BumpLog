package com.bumplog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private val spot = Spot(1, "BL-0001", 40.7128, -74.006, 90.0, DefectType.POTHOLE, false, 1.85f, 1, 47f,
        1_700_000_000_000L, 1_700_000_000_000L, 1, "PENDING", false, "NONE", 0, "Example Street", 0)

    @Test fun reportBlockNeverCarriesSpeed() {
        val b = Reports.spotBlock(spot)
        assertFalse(b.contains("mph", ignoreCase = true))
        assertFalse(b.contains("speed", ignoreCase = true))
        assertFalse(b.contains("47"))
        assertTrue(b.contains("Measured jolt"))
    }

    @Test fun everyHeaderStartsWithTheSelfDescribingBlockAndNoHeaderGivesASpeed() {
        for (h in listOf(Reports.ABOUT_TEST, Reports.ABOUT_REAL, Reports.ABOUT_DAMAGE)) {
            assertTrue(h.startsWith("=== ABOUT THIS EMAIL (read first) ==="))
            assertTrue(h.contains("BumpLog report v1") || h.contains("BumpLog damage report v1") || h.contains("Format: BumpLog"))
            assertFalse(Regex("\\d+\\s*mph", RegexOption.IGNORE_CASE).containsMatchIn(h.replace("\"at 40\"", "")))
        }
    }

    @Test fun realAndDamageHeadersCarryTheAbsoluteSpeedRuleAndApprovalStep() {
        for (h in listOf(Reports.ABOUT_REAL, Reports.ABOUT_DAMAGE)) {
            assertTrue(h.contains(Reports.SPEED_RULE))
            assertTrue(h.contains("do not supply or invent one"))
            assertTrue(h.contains("wait for approval"))
            assertTrue(h.contains("Do not submit, send or pay anything without it"))
        }
        assertTrue(Reports.ABOUT_REAL.contains("Never mention BumpLog, an app, AI or automation"))
    }

    @Test fun testHeaderAsksForOnlyLowRiskActions() {
        val h = Reports.ABOUT_TEST
        assertTrue(h.contains("THIS IS A TEST"))
        assertTrue(h.contains("Do nothing else"))
        assertFalse(h.contains("github.com"))
    }

    @Test fun setupPromptCoversTheRules() {
        val p = Reports.setupPrompt()
        assertTrue(p.contains("deliberately has no speed"))
        assertTrue(p.contains("do not invent one"))
        assertTrue(p.contains("wait for my approval"))
        assertTrue(p.contains("separate letters"))
        assertFalse(p.contains("ready"))
    }

    @Test fun setupPromptEndsCleanlyAfterItemSix() {
        val p = Reports.setupPrompt()
        org.junit.Assert.assertEquals(p.trimEnd(), p)
        org.junit.Assert.assertTrue(p.endsWith("Do nothing else."))
        org.junit.Assert.assertFalse(p.contains("7."))
    }
}
