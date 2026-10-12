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
    @Test fun sageIsTheLastSwatchAndTheDefault() {
        // 0xFF6F9C7E is the muted sage; it must stay the last swatch so the default index (lastIndex) points at it.
        assertEquals(0xFF6F9C7EL.toInt(), Swatches.last().toArgb())
        assertEquals(15, Swatches.size)
    }
}

class VoiceDedupeTest {
    @Test fun localAndNetworkEntriesOfOneSpeakerShareAKey() {
        assertEquals(speakerKey("en-us-x-tpd-local"), speakerKey("en-us-x-tpd-network"))
        assertFalse(speakerKey("en-us-x-tpd-local") == speakerKey("en-us-x-iol-local"))
    }
}
