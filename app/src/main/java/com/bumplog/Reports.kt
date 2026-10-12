package com.bumplog

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the report emails. The app never sends mail itself: it opens the user's mail app with the
 * message filled in, and "Sent" only means that handoff happened. A report is "Filed" only after the
 * assistant confirms it (see the bumplog://filed link in the README).
 */
object Reports {
    private fun iso(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ts))

    private fun pretty(ts: Long): String = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(ts))

    private fun mapLink(lat: Double, lon: Double) = "https://maps.google.com/?q=%.6f,%.6f".format(lat, lon)

    private const val FOOTER = "\nAssistant: please work out which authority maintains this road, prepare the repair request, " +
        "and bring it to me for approval before anything is submitted. Reply with the report ID and the word FILED once a " +
        "request is actually accepted.\n"

    fun spotBlock(s: Spot): String = buildString {
        appendLine("Report ID: ${s.reportId}")
        appendLine("Defect type: ${s.type.label} (category: ${s.type.reportCategory})" + if (s.typeUser) "" else " - unconfirmed guess")
        appendLine("Driver rating: ${s.severity.lowercase()}")
        appendLine("Measured jolt: %.2f g peak (phone accelerometer, not proof of force or damage), %d hit(s) at this spot".format(s.maxG, s.hitCount))
        appendLine("Location: %.6f, %.6f".format(s.lat, s.lon))
        if (s.address.isNotBlank()) appendLine("Road: ${s.address}")
        appendLine("Map: ${mapLink(s.lat, s.lon)}")
        if (s.heading >= 0) appendLine("Direction of travel: %d deg".format(s.heading.toInt()))
        appendLine("Speed: %d mph".format(s.speedMph.toInt()))
        appendLine("First hit: ${iso(s.firstTs)}")
        appendLine("Latest hit: ${iso(s.lastTs)}")
    }

    fun reportsMail(ctx: Context, spots: List<Spot>): Intent {
        val test = spots.isNotEmpty() && spots.all { it.reportId.startsWith("TEST-") }
        val body = buildString {
            appendLine("BumpLog report v1")
            if (test) { appendLine("TEST - these are simulated reports from the app's testing mode. They are not real road defects. Nothing needs to be filed."); appendLine() }
            appendLine(if (spots.size == 1) "1 road defect report from one drive." else "${spots.size} road defect reports from one drive.")
            appendLine()
            spots.forEachIndexed { i, s -> appendLine("--- ${i + 1} of ${spots.size} ---"); append(spotBlock(s)); appendLine() }
            append(if (test) "\nAssistant: this is a test of the app. Reply to confirm you received it. Do not file anything.\n" else FOOTER)
        }
        return mail(Prefs.activeAddress, (if (test) "[TEST] " else "") + if (spots.size == 1) "BumpLog: 1 road defect report" else "BumpLog: ${spots.size} road defect reports", body)
    }

    fun testMail(): Intent {
        val now = System.currentTimeMillis()
        val body = "BumpLog report v1\nTEST - this is a test report. It is not a real road defect. " +
            "Nothing needs to be filed.\n\nReport ID: TEST-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))}\n" +
            "Sent: ${iso(now)}\n\nAssistant: if you can read this, setup works. Reply to confirm.\n"
        return mail(Prefs.activeAddress, "[TEST] BumpLog test report", body)
    }

    fun damageMail(ctx: Context, d: Damage, hit: Hit?, spot: Spot?): Intent {
        val photos = DamagePhotos.uris(ctx, d.reportId)
        val body = buildString {
            appendLine("BumpLog damage report v1")
            appendLine("DAMAGE CLAIM - not a plain road defect report.")
            appendLine()
            appendLine("Report ID: ${d.reportId}")
            appendLine("Damage: ${d.category}")
            if (d.note.isNotBlank()) appendLine("Notes: ${d.note}")
            appendLine("Created: ${iso(d.ts)}")
            appendLine("Photos attached: ${photos.size}")
            if (hit != null) {
                appendLine()
                appendLine("Measured jolt: %.2f g peak (phone accelerometer, not proof of damage) at %d mph on ${pretty(hit.ts)}".format(hit.peakG, hit.speedMph.toInt()))
                appendLine("Hit time: ${iso(hit.ts)}")
                appendLine("Hit location: %.6f, %.6f".format(hit.lat, hit.lon))
                appendLine("Map: ${mapLink(hit.lat, hit.lon)}")
            }
            if (spot != null) {
                appendLine(); appendLine("Linked road defect:"); append(spotBlock(spot))
                if (spot.reportStatus != "NONE") {
                    appendLine()
                    appendLine("Previously reported: yes. The road defect report ${spot.reportId} was handed to mail on ${iso(spot.sentTs)}.")
                    val ref = Prefs.filedRef(spot.reportId)
                    if (spot.reportStatus == "FILED") appendLine("It was confirmed filed" + (if (ref.isNotEmpty()) ", confirmation reference: $ref." else ", no reference number was recorded."))
                    else appendLine("It has not been confirmed as filed yet.")
                }
            }
            appendLine()
            appendLine("Assistant: please work out where this claim goes (the road owner's claims process, the user's insurer, or both), " +
                "gather what that body requires, and bring it to me for approval before anything is submitted. Time limits can be short, " +
                "so tell me any deadline you find.")
        }
        val subject = "BumpLog: damage report ${d.reportId}"
        if (photos.isEmpty()) return mail(Prefs.activeAddress, subject, body)
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Prefs.activeAddress)); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos))
            clipData = android.content.ClipData.newRawUri("photos", photos[0]).also { c -> photos.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun mail(to: String, subject: String, body: String): Intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

    /** One row per logged hit, joined with its spot, for backup or a spreadsheet. */
    fun csv(ctx: Context): Intent {
        val db = Db.get(ctx)
        val spots = db.allSpots().associateBy { it.id }
        val sb = StringBuilder("report_id,review,report_status,defect_type,hit_time,lat,lon,heading,speed_mph,peak_g,spot_hit_count,road\n")
        for (h in db.hits(100000)) {
            val s = spots[h.spotId]
            sb.append(listOf(s?.reportId ?: "", s?.review ?: "", s?.reportStatus ?: "", h.type.name, iso(h.ts),
                "%.6f".format(Locale.US, h.lat), "%.6f".format(Locale.US, h.lon), "%d".format(Locale.US, h.heading.toInt()), "%.1f".format(Locale.US, h.speedMph),
                "%.2f".format(Locale.US, h.peakG), (s?.hitCount ?: 1).toString(), "\"" + (s?.address ?: "").replace("\"", "'") + "\"").joinToString(","))
            sb.append('\n')
        }
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "BumpLog-export.csv")
        f.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export CSV")
    }

    fun statusLabel(s: Spot): String = when (s.reportStatus) {
        "FILED" -> "Filed"
        "SENT" -> "Sent to mail app"
        else -> when (s.review) { "CONFIRMED" -> "Ready to send"; "DISMISSED" -> "Dismissed"; "FIXED" -> "Fixed"; else -> "To review" }
    }
}
