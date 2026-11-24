package com.bumplog

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Photos for a damage report live in the app's private storage until the report is deleted with all data. */
object DamagePhotos {
    fun dir(ctx: Context, reportId: String) = File(ctx.filesDir, "damage/" + reportId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    /** Persists the current pick list idempotently. Photo identity is the CONTENT hash, so
     *  re-picks, reordered picks and same-size lookalikes can neither duplicate nor collide.
     *  A manifest - not a delete - decides what the report attaches: a pick removed before a
     *  rebuild simply leaves the manifest, its file kept on disk, never data loss. */
    @Throws(java.io.IOException::class)
    fun syncDraft(ctx: Context, reportId: String, uris: List<Uri>) {
        val d = dir(ctx, reportId)
        // Every pick is read and stored up front: a pick that cannot be read or stored
        // fails the WHOLE sync, so a shortened set never silently becomes the report.
        val names = uris.map { u ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val tmp = File(d, INCOMING)
            try {
                ctx.contentResolver.openInputStream(u)?.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(65536); var n: Int
                        while (inp.read(buf).also { n = it } >= 0) { digest.update(buf, 0, n); out.write(buf, 0, n) }
                    }
                } ?: throw java.io.IOException("a picked photo could not be opened")
            } catch (e: Exception) { tmp.delete(); throw java.io.IOException("a picked photo could not be read", e) }
            val name = "photo-" + digest.digest().joinToString("") { "%02x".format(it) } + ".jpg"
            val target = File(d, name)
            if (target.isFile && target.length() == tmp.length()) tmp.delete()
            else if (!tmp.renameTo(target)) { tmp.delete(); throw java.io.IOException("a picked photo could not be stored") }
            name
        }
        // The manifest is replaced only whole and only after every photo is on disk:
        // a torn write leaves the LAST VALID selection in force, and one content picked
        // twice attaches once.
        val tmpM = File(d, MANIFEST_TMP)
        tmpM.writeText(names.distinct().joinToString("\n"))
        if (!tmpM.renameTo(File(d, MANIFEST))) { tmpM.delete(); throw java.io.IOException("the photo selection could not be saved") }
    }

    private const val MANIFEST = "selected.txt"
    private const val MANIFEST_TMP = ".selected.tmp"
    private const val INCOMING = ".incoming"

    fun add(ctx: Context, reportId: String, uris: List<Uri>): Int {
        var n = 0
        val d = dir(ctx, reportId)
        val manifest = File(d, MANIFEST).takeIf { it.isFile }
        val added = mutableListOf<String>()
        for (u in uris) {
            try {
                val f = File(d, "photo-${System.currentTimeMillis()}-${n}.jpg")
                ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: continue
                if (f.length() > 0) { n++; added.add(f.name) } else f.delete()
            } catch (e: Exception) { }
        }
        // A draft with a manifest attaches only what the manifest lists: later one-shot
        // adds (photos taken after the sheet) join it instead of silently not sending.
        if (manifest != null && added.isNotEmpty()) manifest.appendText("\n" + added.joinToString("\n"))
        return n
    }

    /** Real photos only: a restore's staging copy (killed mid-restore) is never shown or backed up. */
    /** The photos the next report attaches: the manifest when one exists, every file
     *  otherwise (older drafts and one-shot queue adds carry no manifest). */
    fun files(ctx: Context, reportId: String): List<File> {
        val d = dir(ctx, reportId)
        val all = d.listFiles()?.filter { it.isFile && !it.name.endsWith(Backup.RESTORE_TMP) && it.name != MANIFEST && it.name != MANIFEST_TMP && it.name != INCOMING } ?: emptyList()
        val listed = File(d, MANIFEST).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: return all.sortedBy { it.name }
        val byName = all.associateBy { it.name }
        return listed.mapNotNull { byName[it] }
    }
    fun uris(ctx: Context, reportId: String): List<Uri> = files(ctx, reportId).map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
    fun wipeAll(ctx: Context) { File(ctx.filesDir, "damage").deleteRecursively() }
}
