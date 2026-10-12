package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.toArgb
import org.junit.Test

class TypesTest {
    @Test fun plainLabelsAndTechnicalTerms() {
        assertEquals("Broken pavement (lip)", DefectType.FAULTING.label)
        assertEquals("Rough stretch", DefectType.RUMBLE.label)
        assertTrue(DefectType.FAULTING.technical.contains("aulting"))
        assertTrue(DefectType.RUMBLE.technical.contains("washboarding"))
        assertFalse(DefectType.values().any { it.label.contains("Expansion") || it.label.contains("Rumble") })
    }

    @Test fun oldJointRecordsReadAsRoughStretch() {
        assertEquals(DefectType.RUMBLE, DefectType.parse("JOINT"))
        assertEquals(DefectType.POTHOLE, DefectType.parse("nonsense"))
        assertEquals(3, DefectType.values().size)
    }

    @Test fun addressCleaning() {
        assertEquals("a@b.com", Prefs.cleanEmail("  a@b.com\n"))
        assertEquals("a@b.com", Prefs.cleanEmail("\u00A0a@b.com\u200B"))
        assertEquals("a@b.com", Prefs.cleanEmail("\uFEFFa@b.com"))
        assertTrue(Prefs.isValidEmail(" a@b.com "))
        assertFalse(Prefs.isValidEmail("a b@c.com"))
        assertFalse(Prefs.isValidEmail("a@b.com, c@d.com"))
    }
}

class AppearanceTest {
    @Test fun sageLeadsItsRowAndIsTheDefault() {
        assertEquals(15, Swatches.size)
        assertEquals(SAGE_ARGB.toInt(), Swatches[10].toArgb())
        assertEquals(0, Swatches.chunked(5).last().indexOfFirst { it.toArgb() == SAGE_ARGB.toInt() })
        // Nothing saved at all gives sage.
        assertEquals(SAGE_ARGB.toInt(), AccentStore.resolve(null, null))
    }

    @Test fun everyOldPositionKeepsItsColourAfterTheReorder() {
        for (i in LegacySwatchArgb.indices) {
            val colour = AccentStore.resolve(null, i)
            assertEquals(LegacySwatchArgb[i].toInt(), colour)
            // And the picker still has that colour, so the right swatch is highlighted.
            assertEquals(colour, Swatches[AccentStore.indexOf(colour)].toArgb())
        }
    }

    @Test fun oldSageChoiceAndNewDefaultAreTheSameColour() {
        // In the older list sage was the last position and the default. Someone who picked it still gets sage.
        assertEquals(SAGE_ARGB.toInt(), AccentStore.resolve(null, LegacySwatchArgb.lastIndex))
        assertEquals(10, AccentStore.indexOf(SAGE_ARGB.toInt()))
    }

    @Test fun savedColourWinsAndBadValuesFallBackToSage() {
        val blue = 0xFF3C9BFF.toInt()
        assertEquals(blue, AccentStore.resolve(blue, 14))
        assertEquals(SAGE_ARGB.toInt(), AccentStore.resolve(0x12345678, null))
        assertEquals(SAGE_ARGB.toInt(), AccentStore.resolve(null, 99))
        assertEquals(SAGE_ARGB.toInt(), AccentStore.resolve(null, -1))
    }

    @Test fun legacyListIsFrozenAtFifteenColours() {
        assertEquals(15, LegacySwatchArgb.size)
        assertTrue(LegacySwatchArgb.all { c -> Swatches.any { it.toArgb() == c.toInt() } })
    }
}

class VoiceDedupeTest {
    @Test fun localAndNetworkEntriesOfOneSpeakerShareAKey() {
        assertEquals(speakerKey("en-us-x-tpd-local"), speakerKey("en-us-x-tpd-network"))
        assertFalse(speakerKey("en-us-x-tpd-local") == speakerKey("en-us-x-iol-local"))
    }

    @Test fun hashVariantsStayDistinctButLocalAndNetworkOfTheSameVariantMerge() {
        assertEquals(speakerKey("en-us-x-sfg#female_1-local"), speakerKey("en-us-x-sfg#female_1-network"))
        assertFalse(speakerKey("en-us-x-sfg#female_1-local") == speakerKey("en-us-x-sfg#female_2-local"))
        assertFalse(speakerKey("en-us-x-sfg-local") == speakerKey("en-us-x-sfg#male_1-local"))
        assertFalse(speakerKey("en-us-x-sfg#female_1-local") == speakerKey("en-us-x-tpd#female_1-local"))
        assertFalse(speakerKey("en-gb-x-fis-local") == speakerKey("en-us-x-fis-local"))
    }

    @Test fun bugReportLinksStayShortEvenForWorstCaseText() {
        val c = BugContext("1.4.0", "14 (API 34)", "samsung SM-S928B")
        for (raw in listOf("a b\n".repeat(900), "\uD83D\uDE00".repeat(1500), "\u00e9 ".repeat(1500))) {
            assertTrue(BugReport.githubUrl(raw, c).length < 6500)
            assertTrue(BugReport.formUrl(raw, c).length < 6500)
        }
    }
}

class PillLayoutTest {
    // Pixel maths at 3x density: 16dp side padding = 48px, 6dp gap = 18px.
    private fun per(count: Int, widestDp: Float, availDp: Float) = pillsPerRow(count, widestDp * 3, availDp * 3, 48f, 18f)

    @Test fun fourFitOnOneRowAtNormalSizeOnATypicalPhone() = assertEquals(4, per(4, 49f, 286f))
    @Test fun wrapToTwoByTwoWhenTheyDoNotFit() {
        assertEquals(2, per(4, 49f, 250f))   // narrow screen
        assertEquals(2, per(4, 98f, 286f))   // about 2x font on a normal screen
    }
    @Test fun twoByTwoStillFitsAtTheLargestFontOn320dp() = assertEquals(2, per(4, 98f, 264f))
    @Test fun fallBackToOnePerRowRatherThanClip() = assertEquals(1, per(4, 140f, 264f))
    @Test fun aPairStaysOnOneRowUnlessItReallyCannotFit() {
        assertEquals(2, per(2, 60f, 286f))
        assertEquals(1, per(2, 140f, 264f))
    }
}

class VoiceListTest {
    private val v = listOf("en-us-x-sfg-local" to "Voice 1", "en-us-x-tpd-local" to "Voice 2")
    @Test fun phoneDefaultIsDroppedWhenItMatchesAListedVoice() {
        assertEquals(v, voiceChoices(v, speakerKey("en-us-x-tpd-network")))
        assertEquals("en-us-x-tpd-local", selectedVoiceKey("", v, speakerKey("en-us-x-tpd-network")))
    }
    @Test fun phoneDefaultStaysWhenItMatchesNothing() {
        assertEquals(listOf("" to "Phone default") + v, voiceChoices(v, speakerKey("en-gb-x-abc-local")))
        assertEquals(listOf("" to "Phone default") + v, voiceChoices(v, null))
        assertEquals("", selectedVoiceKey("", v, null))
    }
    @Test fun anExplicitChoiceIsNeverChanged() = assertEquals("en-us-x-sfg-local", selectedVoiceKey("en-us-x-sfg-local", v, speakerKey("en-us-x-tpd-local")))
}

class BugReportTest {
    private val c = BugContext("1.4.0", "14 (API 34)", "Acme Phone 9")
    @Test fun carriesOnlyTheTextAndTheThreeVersions() {
        val u = BugReport.formUrl("it crashed", c)
        assertTrue(u.startsWith("$BUG_FORM_URL?usp=pp_url&entry.2115677078=it%20crashed"))
        assertTrue(u.contains("entry.2070006552=Acme%20Phone%209") && u.contains("entry.1192035271=14%20%28API%2034%29") && u.contains("entry.1144301841=1.4.0"))
        val g = BugReport.githubUrl("it crashed\nmore", c)
        assertTrue(g.startsWith("$REPO_ISSUES/new?title="))
        assertFalse(g.contains("lat") || g.contains("lon"))
    }
    @Test fun previewShowsWhatIsSent() {
        val p = BugReport.preview("it crashed", c)
        assertTrue(p.contains("it crashed") && p.contains("1.4.0") && p.contains("Acme Phone 9"))
    }
    @Test fun longTextIsCut() = assertEquals(3000, BugReport.clean("x".repeat(5000)).length)
}
