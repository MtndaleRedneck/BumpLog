package com.bumplog

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A published release that is newer than the installed app. */
data class UpdateInfo(
    val versionName: String,
    val notes: List<String>,
    val apkUrl: String,
    val size: Long,
    /** Lowercase hex SHA-256 from the release, or null when GitHub did not publish one. */
    val sha256: String?,
)

/**
 * Update check against this app's public GitHub releases. It only runs when the user has it switched on,
 * only talks to github.com and githubusercontent.com over HTTPS, and every failure ends quietly or with a plain message.
 */
object Updater {
    const val API = "https://api.github.com/repos/MtndaleRedneck/BumpLog/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/MtndaleRedneck/BumpLog/releases/download/"
    private const val MAX_APK = 150L * 1024 * 1024

    /** "v1.8.0" or "1.8.0" as numbers, or null when it is not a plain dotted version. */
    fun parseVersion(s: String): List<Int>? {
        val t = s.trim().removePrefix("v").removePrefix("V")
        if (!Regex("""\d{1,4}(\.\d{1,4}){0,3}""").matches(t)) return null
        return t.split('.').map { it.toInt() }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Bullet lines and section headings from the release notes, with simple markdown removed. A heading comes back as "## Name". Other lines are ignored. */
    fun notesBullets(body: String?): List<String> = body.orEmpty().lines().map { it.trim() }
        .mapNotNull {
            when {
                it.startsWith("- ") || it.startsWith("* ") -> it.drop(2).replace("**", "").replace("`", "").trim().take(200)
                it.startsWith("#") -> it.trimStart('#').replace("**", "").replace("`", "").trim().take(60).let { h -> if (h.isEmpty()) "" else "## $h" }
                else -> null
            }
        }
        .filter { it.isNotEmpty() }.take(16)

    /** The offer for a release JSON, or null when it is a draft, a prerelease, has no usable APK, or is not newer than [current]. */
    fun parseRelease(json: String, current: String): UpdateInfo? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name", "")
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false) || parseVersion(tag) == null || !isNewer(tag, current)) null
        else {
            val assets = o.optJSONArray("assets")
            var found: UpdateInfo? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                val size = a.optLong("size", 0)
                if (found == null && name.startsWith("BumpLog") && name.endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX) && size in 1..MAX_APK) {
                    val d = a.optString("digest", "")
                    val hex = if (d.startsWith("sha256:") && Regex("[0-9a-fA-F]{64}").matches(d.removePrefix("sha256:"))) d.removePrefix("sha256:").lowercase() else null
                    found = UpdateInfo(tag.trim().removePrefix("v").removePrefix("V"), notesBullets(o.optString("body", "")), url, size, hex)
                }
            }
            found
        }
    } catch (e: Exception) { null }

    fun hostAllowed(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && (u.host == "github.com" || u.host == "githubusercontent.com" || u.host.endsWith(".githubusercontent.com"))
    } catch (e: Exception) { false }

    /** Asks GitHub for the latest release. Null on any failure, offline, rate limit or odd response. Call off the main thread. */
    /** Same as [check] but throws on a connection/HTTP failure instead of returning null,
     * so a manual check can tell "no network" apart from "you're up to date". */
    fun checkOrThrow(current: String): UpdateInfo? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "BumpLog")
        if (c.responseCode != 200) throw java.io.IOException("HTTP " + c.responseCode)
        val body = c.inputStream.use { it.readBytes(512 * 1024) }
        return parseRelease(String(body, Charsets.UTF_8), current)
    }

    fun check(current: String): UpdateInfo? = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "BumpLog")
        if (c.responseCode != 200) null else {
            val body = c.inputStream.use { it.readBytes(512 * 1024) }
            parseRelease(String(body, Charsets.UTF_8), current)
        }
    } catch (e: Exception) { null }

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n = read(buf); var t = 0
        while (n >= 0) { t += n; if (t > max) throw java.io.IOException("too large"); out.write(buf, 0, n); n = read(buf) }
        return out.toByteArray()
    }

    class Failure(val msg: String) : Exception(msg)

    fun updateDir(ctx: Context) = File(ctx.cacheDir, "updates")
    fun clean(ctx: Context) { updateDir(ctx).listFiles()?.forEach { it.delete() } }

    /** Downloads the APK, following only HTTPS redirects inside github.com and githubusercontent.com. Throws [Failure]. */
    fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit, cancelled: () -> Boolean): File {
        clean(ctx)
        val dir = updateDir(ctx); dir.mkdirs()
        val out = File(dir, "BumpLog-update.apk")
        try {
            var url = info.apkUrl
            var conn: HttpURLConnection? = null
            for (hop in 0..5) {
                if (!hostAllowed(url)) throw Failure("The download address was not trusted, so it was stopped.")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "BumpLog")
                val code = c.responseCode
                if (code in 301..308) { url = c.getHeaderField("Location") ?: throw Failure("The download could not start."); c.disconnect(); continue }
                if (code != 200) throw Failure("The download failed (error $code).")
                conn = c; break
            }
            val c = conn ?: throw Failure("The download was redirected too many times.")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { ins -> out.outputStream().use { os ->
                val buf = ByteArray(32 * 1024); var n = ins.read(buf)
                while (n >= 0) {
                    if (cancelled()) throw Failure("cancelled")
                    total += n; if (total > info.size) throw Failure("The file was larger than expected.")
                    md.update(buf, 0, n); os.write(buf, 0, n); progress(total.toFloat() / info.size)
                    n = ins.read(buf)
                }
            } }
            if (total != info.size) throw Failure("The download was incomplete.")
            if (info.sha256 != null && toHex(md.digest()) != info.sha256) throw Failure("The file did not match the published checksum, so it was deleted.")
            return out
        } catch (e: Failure) { out.delete(); throw e }
        catch (e: Exception) { out.delete(); throw Failure("The download failed. Check your connection and try again.") }
    }

    /**
     * Same signer set, or a key rotation: the installed signer appears in the new file's signing history (Android's lineage).
     * Android still makes the final call at install time.
     */
    fun signersAcceptable(installed: Set<List<Byte>>, incoming: Set<List<Byte>>, incomingHistory: Set<List<Byte>>): Boolean {
        if (installed.isEmpty() || incoming.isEmpty()) return false
        if (installed == incoming) return true
        return installed.size == 1 && incoming.size == 1 && incomingHistory.containsAll(installed)
    }

    fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Confirms the file is this app, newer, and signed with the same key as the installed copy. Throws [Failure]. */
    fun verify(ctx: Context, f: File) {
        try { verifyInner(ctx, f) }
        catch (e: Failure) { throw e }
        catch (e: Exception) { throw Failure("The downloaded file could not be checked, so it was not installed.") }
    }

    private fun verifyInner(ctx: Context, f: File) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val arch = pm.getPackageArchiveInfo(f.path, flags) ?: throw Failure("The downloaded file is not a valid app.")
        if (arch.packageName != ctx.packageName) throw Failure("The downloaded file is a different app.")
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        val cur = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val next = arch.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val history = arch.signingInfo?.signingCertificateHistory?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        if (!signersAcceptable(cur, next, history)) throw Failure("The file is not signed by the same key as your installed BumpLog, so it was not installed.")
        if (arch.longVersionCode <= installed.longVersionCode) throw Failure("The downloaded file is not newer than this version.")
    }
}
