package com.bumplog

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Photos for a damage report live in the app's private storage until the report is deleted with all data. */
object DamagePhotos {
    private fun dir(ctx: Context, reportId: String) = File(ctx.filesDir, "damage/" + reportId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

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

    fun files(ctx: Context, reportId: String): List<File> = dir(ctx, reportId).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    fun uris(ctx: Context, reportId: String): List<Uri> = files(ctx, reportId).map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
    fun wipeAll(ctx: Context) { File(ctx.filesDir, "damage").deleteRecursively() }
}
