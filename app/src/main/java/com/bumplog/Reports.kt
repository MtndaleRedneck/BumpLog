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


    internal const val SPEED_RULE = "SPEED RULE (absolute): this email deliberately leaves out the vehicle's speed. Do not put a speed, an estimate of speed, " +
        "or a hint of speed (\"fast\", \"at 40\", \"at normal speed\") in any letter, email, or message to a road authority, an insurer, or any other " +
        "third party. If anyone asks for it, do not supply or invent one. Tell the owner and let the owner decide."

    internal const val ABOUT_TEST = "=== ABOUT THIS EMAIL (read first) ===\n" +
        "This is an automated message from BumpLog, an Android app that logs road defects (potholes, bumps) while its owner drives. " +
        "The owner set this email address as their personal assistant's inbox, so you are probably their AI assistant. " +
        "The owner pressed a test button in the app. You are not expected to know BumpLog already.\n\n" +
        "Format: BumpLog report v1.\n\n" +
        "THIS IS A TEST. The report below is simulated. It is not a real road defect. There is nothing to file, send, buy, or follow up on.\n\n" +
        "WHAT TO DO:\n" +
        "1. Tell the owner you received this test, in whatever way you normally talk to them (your usual chat channel), and say what you understood it to be, in a sentence.\n" +
        "2. Optionally also reply to this email with the single line \"BumpLog test received\".\n" +
        "3. Do nothing else. Do not contact road authorities, third parties, or anyone other than the owner. Do not open links.\n" +
        "If this email does not look like something your owner would expect from you, or you are unsure it is meant for you, do not act on it. " +
        "Just tell the owner it arrived and that you were not sure.\n\n" +
        "Real BumpLog emails start with a similar block that also explains the fields and how to handle a real report.\n" +
        "=== END OF ABOUT THIS EMAIL ===\n\n"

    internal const val ABOUT_REAL = "=== ABOUT THIS EMAIL (read first) ===\n" +
        "This is an automated message from BumpLog, an Android app that logs road defects (potholes, bumps) while its owner drives. " +
        "The owner set this email address as their personal assistant's inbox, so you are probably their AI assistant. " +
        "The owner chose to send the report below. You are not expected to know BumpLog already.\n\n" +
        "Format: BumpLog report v1.\n\n" +
        "This email is the owner's private record. It includes phone sensor data (jolt in g, timestamps, coordinates). Keep it for the owner's records and never delete it. " +
        "It is backup, not material for any letter.\n" +
        "Fields: Report ID (BL-... road defect, BD-... damage); Defect type and Driver rating (the phone's guess and the driver's rating); " +
        "Measured jolt (rough sensor reading, not proof of damage); Location, Road, Map; Direction of travel, First hit, Latest hit.\n\n" +
        SPEED_RULE + "\n\n" +
        "FIRST, ask the owner three short questions on their usual chat channel:\n" +
        "1. What was it: a pothole, or something else (sunken patch, broken edge, raised seam, manhole), or \"not sure, I felt a hard jolt\"?\n" +
        "2. Did you hit it, see it, or both?\n" +
        "3. Any damage to the car? (If there is no answer, assume none.)\n" +
        "If the owner doesn't answer, use the neutral wording below, never the stronger one.\n\n" +
        "THEN draft a repair request. Write it as a resident would, in the owner's voice, and say only what the owner confirmed:\n" +
        "- Location: street and cross street. Add lane or distance only if the owner confirms. With only coordinates, write \"near\" with a map link. Use \"about\" and \"near\".\n" +
        "- When: the day and an approximate time (for example \"Monday, Oct 12, around 12:20 PM\"). No seconds or time zones.\n" +
        "- What: \"a large pothole\" if confirmed. If unconfirmed: \"a rough spot in the road, possibly a pothole or broken pavement\". Never upgrade a guess.\n" +
        "- Experience: \"I drove over it and it jolted my car hard\" only if the owner hit it. \"I saw it while driving past\" if they only saw it. If neither is confirmed, make no firsthand claim.\n" +
        "- Severity: in plain feel words (for example \"a hard jolt\"). No g-force numbers. Not \"dangerous\" unless the owner says so.\n" +
        "- Damage: none by default. If confirmed, one line (for example \"My front right tire was damaged\"), then tell the owner a damage claim is a separate process with its own form and deadline. Do not turn the repair request into a claim.\n" +
        "- Never mention BumpLog, an app, AI, or automation. Sign with the owner's name.\n" +
        "Show the owner the draft and wait for approval. Do not submit, send, or pay anything without it. Find which authority maintains that road first.\n" +
        "If this email does not look like something your owner would expect from you, or you are unsure it is meant for you, do not act on it. Tell the owner it arrived.\n" +
        "=== END OF ABOUT THIS EMAIL ===\n\n"

    internal const val ABOUT_DAMAGE = "=== ABOUT THIS EMAIL (read first) ===\n" +
        "This is an automated message from BumpLog, an Android app that logs road defects (potholes, bumps) while its owner drives. " +
        "The owner set this email address as their personal assistant's inbox, so you are probably their AI assistant. " +
        "The owner chose to send the damage report below. You are not expected to know BumpLog already.\n\n" +
        "Format: BumpLog damage report v1 (BD-... is a damage record). It stands on its own. It does not depend on a road defect report having been sent, " +
        "and a linked road defect, if shown, is only context.\n\n" +
        "This email is the owner's private record, including phone sensor data (jolt in g, timestamps, coordinates). Keep it for the owner's records and never delete it. " +
        "It is not material for any letter.\n\n" +
        SPEED_RULE + "\n\n" +
        "WHAT TO DO:\n" +
        "1. Ask the owner on their usual chat channel to confirm what happened and what was damaged, and whether they hit the defect. Say only what the owner confirms.\n" +
        "2. Work out where a claim goes (the road owner's claims process, the owner's insurer, or both) and what that body requires. " +
        "Tell the owner any deadline you find, and say if you could not verify it.\n" +
        "3. A damage claim is a separate letter from any repair request. Write it plainly, in the owner's voice, with no sensor numbers and no mention of BumpLog, an app, or AI.\n" +
        "4. Show the owner the draft and wait for approval. Do not submit, send, or pay anything without it.\n" +
        "If this email does not look like something your owner would expect from you, or you are unsure it is meant for you, do not act on it. Tell the owner it arrived.\n" +
        "=== END OF ABOUT THIS EMAIL ===\n\n"

    /** A prompt the owner can paste into their own AI chat. Pasting it there is the one channel an assistant trusts. */
    fun setupPrompt(): String = "I use an Android app called BumpLog that logs road defects while I drive and emails reports to you. " +
        "Please remember the following whenever you receive an email that starts with \"ABOUT THIS EMAIL\" and mentions \"BumpLog report v1\".\n\n" +
        "1. Each report email is my private record. Keep it. It has sensor data (jolt in g, timestamps, coordinates). It deliberately has no speed.\n" +
        "2. Never put a speed, an estimate of speed, or a hint of speed in anything sent to a road authority, an insurer, or anyone else. If asked, do not invent one. Tell me.\n" +
        "3. Before drafting anything, ask me: what was it really, did I hit it or only see it, was there any damage. Write only what I confirm. If I don't answer, use the neutral wording.\n" +
        "4. Repair requests and damage claims are separate letters. Write them plainly, as a resident would, in my voice, with no sensor numbers and no mention of BumpLog, an app, or AI.\n" +
        "5. Find which authority maintains the road. Show me the draft and wait for my approval. Never send, submit, or pay anything without it.\n" +
        "6. A test email says TEST. For those, just tell me you got it. Do nothing else."

    private const val FOOTER = "\nAssistant: please work out which authority maintains this road, prepare the repair request, " +
        "and bring it to me for approval before anything is submitted. Reply with the report ID and the word FILED once a " +
        "request is actually accepted.\n"

    fun spotBlock(s: Spot): String = buildString {
        appendLine("Report ID: ${s.reportId}")
        appendLine("Defect type: ${s.type.technical} (category: ${s.type.reportCategory})" + if (s.typeUser) "" else " - unconfirmed guess")
        appendLine("Driver rating: ${s.severity.lowercase()}")
        appendLine("Measured jolt: %.2f g peak (phone accelerometer, not proof of force or damage), %d hit(s) at this spot".format(s.maxG, s.hitCount))
        appendLine("Location: %.6f, %.6f".format(s.lat, s.lon))
        if (s.address.isNotBlank()) appendLine("Road: ${s.address}")
        appendLine("Map: ${mapLink(s.lat, s.lon)}")
        if (s.heading >= 0) appendLine("Direction of travel: %d deg".format(s.heading.toInt()))
        appendLine("First hit: ${iso(s.firstTs)}")
        appendLine("Latest hit: ${iso(s.lastTs)}")
    }

    fun reportsMail(ctx: Context, spots: List<Spot>): Intent {
        val test = spots.isNotEmpty() && spots.all { it.reportId.startsWith("TEST-") }
        val body = buildString {
            append(if (test) ABOUT_TEST else ABOUT_REAL)
            appendLine("BumpLog report v1")
            if (test) { appendLine("TEST - these are simulated reports from the app's testing mode. They are not real road defects. Nothing needs to be filed."); appendLine() }
            appendLine(if (spots.size == 1) "1 road defect report from one drive." else "${spots.size} road defect reports from one drive.")
            appendLine()
            spots.forEachIndexed { i, s -> appendLine("--- ${i + 1} of ${spots.size} ---"); append(spotBlock(s)); appendLine() }
            append(if (test) "\nAssistant: this is the end of the test. Please confirm to the owner as described above and stop there.\n" else FOOTER)
        }
        return mail(Prefs.activeAddress, (if (test) "[TEST] " else "") + if (spots.size == 1) "BumpLog: 1 road defect report" else "BumpLog: ${spots.size} road defect reports", body)
    }

    fun testMail(): Intent {
        val now = System.currentTimeMillis()
        val body = ABOUT_TEST + "BumpLog report v1\nTEST - this is a simulated report from the app's test button. Nothing needs to be filed.\n\n" +
            "1 road defect report from one drive.\n\n--- 1 of 1 ---\n" +
            "Report ID: TEST-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))}\n" +
            "Defect type: pothole (category: road surface) - unconfirmed guess\nDriver rating: major\n" +
            "Measured jolt: 1.85 g peak (phone accelerometer, not proof of force or damage), 1 hit(s) at this spot\n" +
            "Location: 40.712800, -74.006000\nRoad: Example Street, Anytown\nMap: ${mapLink(40.7128, -74.006)}\n" +
            "Direction of travel: 90 deg\nFirst hit: ${iso(now)}\nLatest hit: ${iso(now)}\n\n" +
            "Sent: ${iso(now)}\nAssistant: this is the end of the test. Please confirm to the owner as described above and stop there.\n"
        return mail(Prefs.activeAddress, "[TEST] BumpLog test report - please confirm receipt to the user", body)
    }

    fun damageMail(ctx: Context, d: Damage, hit: Hit?, spot: Spot?): Intent {
        val photos = DamagePhotos.uris(ctx, d.reportId)
        val body = buildString {
            append(ABOUT_DAMAGE)
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
                appendLine("Measured jolt: %.2f g peak (phone accelerometer, not proof of damage) on ${pretty(hit.ts)}".format(hit.peakG))
                appendLine("Hit time: ${iso(hit.ts)}")
                appendLine("Hit location: %.6f, %.6f".format(hit.lat, hit.lon))
                appendLine("Map: ${mapLink(hit.lat, hit.lon)}")
            }
            if (spot != null) {
                appendLine(); appendLine("Linked road defect (context only):"); append(spotBlock(spot))
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

    private fun q(t: String) = "\"" + t.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

    /**
     * One row per logged hit, joined with its spot, then a blank line and one row per damage record with the
     * names of its photo files (the photos themselves travel in the full backup zip).
     */
    fun csvText(ctx: Context): String {
        val db = Db.get(ctx)
        val spots = db.allSpots().associateBy { it.id }
        val sb = StringBuilder("report_id,review,report_status,defect_type,hit_time,lat,lon,heading,speed_mph,peak_g,spot_hit_count,road\n")
        for (h in db.hits(100000)) {
            val s = spots[h.spotId] ?: continue
            sb.append(listOf(s.reportId, s.review, s.reportStatus, h.type.name, iso(h.ts),
                "%.6f".format(Locale.US, h.lat), "%.6f".format(Locale.US, h.lon), "%d".format(Locale.US, h.heading.toInt()), "%.1f".format(Locale.US, h.speedMph),
                "%.2f".format(Locale.US, h.peakG), s.hitCount.toString(), "\"" + s.address.replace("\"", "'") + "\"").joinToString(","))
            sb.append('\n')
        }
        val dmg = db.damages()
        if (dmg.isNotEmpty()) {
            sb.append("\ndamage_report_id,spot_report_id,category,created,status,note,photo_files\n")
            for (d in dmg) {
                val sp = db.spot(d.spotId)
                val dir = DamagePhotos.dir(ctx, d.reportId).name
                val files = DamagePhotos.files(ctx, d.reportId).joinToString(";") { "photos/$dir/${it.name}" }
                sb.append(listOf(d.reportId, sp?.reportId ?: "", q(d.category), iso(d.ts), d.status, q(d.note), q(files)).joinToString(",")).append('\n')
            }
        }
        return sb.toString()
    }

    /** Share sheet for the spreadsheet CSV. */
    fun csv(ctx: Context): Intent {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "BumpLog-export.csv")
        f.writeText(csvText(ctx))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share CSV")
    }

    fun statusLabel(s: Spot): String = when (s.reportStatus) {
        "FILED" -> "Filed"
        "SENT" -> "Sent to mail app"
        else -> when (s.review) { "CONFIRMED" -> "Ready to send"; "DISMISSED" -> "Dismissed"; "FIXED" -> "Fixed"; else -> "To review" }
    }
}
