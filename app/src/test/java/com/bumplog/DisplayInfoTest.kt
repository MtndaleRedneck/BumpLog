package com.bumplog

import org.junit.Assert.*
import org.junit.Test

class DisplayInfoTest {
    @Test fun buckets() {
        assertEquals("mdpi", DisplayInfo.bucket(160)); assertEquals("xhdpi", DisplayInfo.bucket(320))
        assertEquals("xxhdpi", DisplayInfo.bucket(440)); assertEquals("xxxhdpi", DisplayInfo.bucket(560))
    }
    @Test fun sizeClasses() {
        assertEquals("compact", DisplayInfo.sizeClass(599)); assertEquals("medium", DisplayInfo.sizeClass(600))
        assertEquals("medium", DisplayInfo.sizeClass(839)); assertEquals("expanded", DisplayInfo.sizeClass(840))
    }
    @Test fun describesNumbersOnly() {
        val d = DisplayInfo.describe(1.15f, 440, 1080, 1920)
        assertEquals("font scale 1.15, 440 dpi (xxhdpi), screen 393x698 dp (1080x1920 px), compact width", d)
    }
    @Test fun feedbackCarriesDisplayLine() {
        val c = BugContext("1.10.0", "14 (API 34)", "Acme Phone", "font scale 1.00, 420 dpi (xxhdpi), screen 411x914 dp (1080x2400 px), compact width")
        assertTrue(BugReport.preview("it crashed", c).contains("\nDisplay: font scale 1.00, 420 dpi"))
        assertTrue(java.net.URLDecoder.decode(BugReport.githubUrl("it crashed", c), "UTF-8").contains("Display: font scale 1.00"))
        assertTrue(java.net.URLDecoder.decode(BugReport.formUrl("it crashed", c), "UTF-8").contains("Display: font scale 1.00"))
        assertFalse(BugReport.preview("x", BugContext("1", "2", "3")).contains("Display:"))
    }
    @Test fun sideBySideRule() {
        assertTrue(sideBySide(392f, 1.15f)); assertTrue(sideBySide(360f, 1.0f)); assertFalse(sideBySide(340f, 1.0f)); assertFalse(sideBySide(412f, 1.5f)); assertTrue(sideBySide(360f, 1.49f))
    }
}
