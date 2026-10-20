package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ReportNumbersTest {
    @Test fun counterStartsOnePastTheExistingCount() {
        assertEquals(1, ReportNumbers.initialNext(0))
        assertEquals(12, ReportNumbers.initialNext(11))
    }

    @Test fun restoreNeverCountsDownAndNeverReissues() {
        assertEquals(9, ReportNumbers.afterRestore(9, 3))
        assertEquals(4, ReportNumbers.afterRestore(2, 3))
    }

    @Test fun refIsReportNumberDashHitDate() {
        val ts = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-10-19")!!.time
        assertEquals("Report #12-10/19/2026", ReportNumbers.ref(12, ts))
        assertEquals("Report #3-01/05/2026", ReportNumbers.ref(3, SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-01-05")!!.time))
    }

    @Test fun oldCodesNeedARealDateAndTheGeneratorAlphabet() {
        assertTrue(ReportNumbers.isOldId("BL-20250612-A3KQ"))
        assertTrue(ReportNumbers.isOldId("BD-20260101-2345"))
        assertTrue(ReportNumbers.isOldId("PS-20261031-ZZZ9"))
        assertFalse(ReportNumbers.isOldId("BL-20250230-A3KQ")) // Feb 30 is not a date
        assertFalse(ReportNumbers.isOldId("BL-20250612-A3K0")) // 0 is not in the alphabet
        assertFalse(ReportNumbers.isOldId("BL-20250612-A3KI")) // neither is I
        assertFalse(ReportNumbers.isOldId("BL-20250612-A3K"))  // too short
        assertFalse(ReportNumbers.isOldId("BL-20250612-A3KQQ")) // too long
        assertFalse(ReportNumbers.isOldId("bl-20250612-A3KQ")) // wrong case
        assertFalse(ReportNumbers.isOldId("BL-20240612-A3KQ")) // before the app existed
        assertFalse(ReportNumbers.isOldId("BL-21010612-A3KQ")) // absurd future
        assertFalse(ReportNumbers.isOldId("TEST-1"))
        assertFalse(ReportNumbers.isOldId(""))
        assertFalse(ReportNumbers.isOldId("Report #12-10/19/2026"))
    }

    @Test fun numbersStayInRange() {
        assertFalse(ReportNumbers.validNo(0))
        assertTrue(ReportNumbers.validNo(1))
        assertTrue(ReportNumbers.validNo(999_999_999))
        assertFalse(ReportNumbers.validNo(1_000_000_000))
    }

    @Test fun csvHeaderDetectionRoundTripsBothLayouts() {
        val old12 = "report_id,review,report_status,defect_type,hit_time,lat,lon,heading,speed_mph,peak_g,spot_hit_count,road".split(",")
        val new16 = "report_id,report_no,review,report_status,defect_type,hit_time,lat,lon,heading,speed_mph,peak_g,spot_hit_count,road,town,county,report_ts".split(",")
        assertFalse(ReportNumbers.spotsCsvHasNumbers(old12))
        assertTrue(ReportNumbers.spotsCsvHasNumbers(new16))
        assertFalse(ReportNumbers.spotsCsvHasNumbers(new16.dropLast(2))) // 14 cols is neither layout
        val oldDmg = "damage_report_id,spot_report_id,category,created,status,note,photo_files".split(",")
        val newDmg = "damage_report_id,report_no,spot_report_id,category,created,status,note,photo_files,report_ts".split(",")
        assertFalse(ReportNumbers.damageCsvHasNumbers(oldDmg))
        assertTrue(ReportNumbers.damageCsvHasNumbers(newDmg))
        assertFalse(ReportNumbers.damageCsvHasNumbers(new16)) // wrong section never matches
    }

    @Test fun filedReadsAsSentEverywhere() {
        assertEquals("SENT", ReportNumbers.mapStatus("FILED"))
        assertEquals("SENT", ReportNumbers.mapStatus("SENT"))
        assertEquals("NONE", ReportNumbers.mapStatus("NONE"))
        assertEquals("NONE", ReportNumbers.mapStatus("junk"))
        assertEquals("SENT", ReportNumbers.mapDamageStatus("FILED"))
        assertEquals("QUEUED", ReportNumbers.mapDamageStatus("QUEUED"))
        assertEquals("NONE", ReportNumbers.mapDamageStatus("junk"))
    }
}
