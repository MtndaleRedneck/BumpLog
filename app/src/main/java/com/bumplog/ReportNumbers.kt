package com.bumplog

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The running report number, shown as "Report #<n>-MM/DD/YYYY" (n from one shared counter, the date
 * is the hit date). A number is assigned once, when a spot is confirmed or a damage report is
 * created, and is never reused, even if the report is dismissed or deleted. Reports from before
 * this scheme keep their old BL-/BD-/PS- codes as their public ref; the internal report_id never
 * changes. The counter lives in the database's meta table: "Delete all data" keeps it, a reinstall
 * without a backup restarts it at 1.
 */
object ReportNumbers {
    /** First number after an upgrade: one past the count of reports the owner already has. */
    fun initialNext(existing: Int): Int = existing + 1

    /**
     * After a restore: past every number the backup used, past the stored counter, and past the owner's
     * existing confirmed reports (a pre-numbering backup carries no numbers but the reports still count).
     * bumpCounterTo applies the never-down rule on top.
     */
    fun restoreNext(highestRestored: Int, stored: Int, existing: Int): Int = maxOf(highestRestored + 1, stored, existing + 1)

    fun ref(no: Int, hitTs: Long): String =
        "Report #" + no + "-" + SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date(hitTs))

    /** The alphabet the app has always used for the random part of old codes (no 0, 1, I, L, O). */
    private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val oldId = Regex("^(BL|BD|PS)-(\\d{8})-([A-Z2-9]{4})$")

    /** Strict old-style code: right prefix, a real date, four generator-alphabet characters. */
    fun isOldId(id: String): Boolean {
        val m = oldId.matchEntire(id) ?: return false
        if (m.groupValues[3].any { it !in CODE_CHARS }) return false
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }
        return try {
            val d = fmt.parse(m.groupValues[2]) ?: return false
            val c = java.util.Calendar.getInstance(); c.time = d
            c.get(java.util.Calendar.YEAR) in 2025..2100
        } catch (e: Exception) { false }
    }

    fun validNo(no: Long): Boolean = no in 1..999_999_999

    /** Spots CSV has the post-1.14 layout when a report_no column follows report_id (12 pre-1.14 columns, 15+ after). */
    fun spotsCsvHasNumbers(header: List<String>): Boolean = header.size >= 15 && header[0].trim() == "report_id" && header[1].trim() == "report_no"

    /** Same check for the damage section of the CSV (8+ columns). */
    fun damageCsvHasNumbers(header: List<String>): Boolean = header.size >= 8 && header[0].trim() == "damage_report_id" && header[1].trim() == "report_no"

    /** Spot report status as of 1.14: Filed is gone, old FILED values read as SENT. */
    fun mapStatus(s: String): String = when (s) { "SENT", "FILED" -> "SENT"; else -> "NONE" }

    /** Same for damage records, which also know QUEUED. */
    fun mapDamageStatus(s: String): String = when (s) { "SENT", "FILED" -> "SENT"; "QUEUED" -> "QUEUED"; else -> "NONE" }
}
