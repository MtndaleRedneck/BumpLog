package com.bumplog

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Photos for a damage report live in the app's private storage until the report is deleted with all data. */
object DamagePhotos {
    fun dir(ctx: Context, reportId: String) = File(ctx.filesDir, "damage/" + reportId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    /** Adds each pick once per draft: the file name carries the pick position and the source
     *  size, so rebuilding the sheet rewrites nothing and only genuinely new picks land. No
     *  deletes: a pick that was removed before a rebuild stays as an orphan, never data loss. */
    fun addOnce(ctx: Context, reportId: String, uris: List<Uri>) {
        val d = dir(ctx, reportId)
        uris.forEachIndexed { i, u ->
            val size = try { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
            val f = File(d, "photo-$i-$size.jpg")
            if (size >= 0 && f.isFile && f.length() == size) return@forEachIndexed
            try { ctx.contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } } catch (e: Exception) { }
        }
    }

    fun add(ctx: Context, reportId: String, uris: List<Uri>): Int {
        var n = 0
        val d = dir(ctx, reportId)
        for (u in uris) {
            try {
                val f = File(d, "photo-${System.currentTimeMillis()}-${n}.jpg")
                ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: continue
                if (f.length() > 0) n++ else f.delete()
            } catch (e: Exception) { }
        }
        return n
    }

    /** Real photos only: a restore's staging copy (killed mid-restore) is never shown or backed up. */
    fun files(ctx: Context, reportId: String): List<File> =
        dir(ctx, reportId).listFiles()?.filter { it.isFile && !it.name.endsWith(Backup.RESTORE_TMP) }?.sortedBy { it.name } ?: emptyList()
    fun uris(ctx: Context, reportId: String): List<Uri> = files(ctx, reportId).map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
    fun wipeAll(ctx: Context) { File(ctx.filesDir, "damage").deleteRecursively() }
}
