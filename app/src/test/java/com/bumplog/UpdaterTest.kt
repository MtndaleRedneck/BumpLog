package com.bumplog

import org.junit.Assert.*
import org.junit.Test

class UpdaterTest {
    private val base = "https://github.com/MtndaleRedneck/BumpLog/releases/download/v1.8.0/BumpLog-v1.8.0.apk"
    private val hex = "a".repeat(64)

    private fun rel(tag: String = "v1.8.0", draft: Boolean = false, pre: Boolean = false, body: String = "- One\n- Two", asset: String = "BumpLog-v1.8.0.apk", url: String = base, size: Long = 1000, digest: String? = "sha256:$hex"): String {
        val d = if (digest == null) "" else ""","digest":"$digest""""
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":${org.json.JSONObject.quote(body)},"assets":[{"name":"$asset","size":$size,"browser_download_url":"$url$d"}]}"""
            .replace("\"browser_download_url\":\"$url$d\"}", "\"browser_download_url\":\"$url\"" + (if (digest == null) "" else ",\"digest\":\"$digest\"") + "}")
    }

    @Test fun versionParse() {
        assertEquals(listOf(1, 8, 0), Updater.parseVersion("v1.8.0"))
        assertEquals(listOf(1, 10), Updater.parseVersion("1.10"))
        assertNull(Updater.parseVersion("1.8.0-beta"))
        assertNull(Updater.parseVersion("latest"))
        assertNull(Updater.parseVersion(""))
    }

    @Test fun versionCompare() {
        assertTrue(Updater.isNewer("1.8.0", "1.7.0"))
        assertTrue(Updater.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.7.0", "1.7.0"))
        assertFalse(Updater.isNewer("1.7.0", "1.8.0"))
        assertFalse(Updater.isNewer("1.7", "1.7.0"))
        assertFalse(Updater.isNewer("junk", "1.7.0"))
    }

    @Test fun parsesGoodRelease() {
        val r = Updater.parseRelease(rel(), "1.7.0")!!
        assertEquals("1.8.0", r.versionName)
        assertEquals(listOf("One", "Two"), r.notes)
        assertEquals(1000L, r.size)
        assertEquals(hex, r.sha256)
        assertEquals(base, r.apkUrl)
    }

    @Test fun rejectsBadReleases() {
        assertNull(Updater.parseRelease(rel(draft = true), "1.7.0"))
        assertNull(Updater.parseRelease(rel(pre = true), "1.7.0"))
        assertNull(Updater.parseRelease(rel(tag = "nightly"), "1.7.0"))
        assertNull(Updater.parseRelease(rel(tag = "v1.7.0"), "1.7.0"))
        assertNull(Updater.parseRelease(rel(asset = "notes.txt"), "1.7.0"))
        assertNull(Updater.parseRelease(rel(url = "https://evil.example/BumpLog.apk"), "1.7.0"))
        assertNull(Updater.parseRelease(rel(url = base.replace("https", "http")), "1.7.0"))
        assertNull(Updater.parseRelease(rel(size = 0), "1.7.0"))
        assertNull(Updater.parseRelease("""{"tag_name":"v1.8.0"}""", "1.7.0"))
        assertNull(Updater.parseRelease("not json", "1.7.0"))
        assertNull(Updater.parseRelease("", "1.7.0"))
    }

    @Test fun digestIsOptionalAndValidated() {
        assertNull(Updater.parseRelease(rel(digest = null), "1.7.0")!!.sha256)
        assertNull(Updater.parseRelease(rel(digest = "sha256:xyz"), "1.7.0")!!.sha256)
        assertNull(Updater.parseRelease(rel(digest = "md5:$hex"), "1.7.0")!!.sha256)
        assertEquals(hex, Updater.parseRelease(rel(digest = "sha256:" + hex.uppercase()), "1.7.0")!!.sha256)
    }

    @Test fun notesBullets() {
        val body = "## What's new\n- **Update check** inside the app\n* Cleaner `Home` screen\nplain line\n-not a bullet\n- \n"
        assertEquals(listOf("Update check inside the app", "Cleaner Home screen"), Updater.notesBullets(body))
        assertTrue(Updater.notesBullets(null).isEmpty())
        assertEquals(8, Updater.notesBullets((1..20).joinToString("\n") { "- item $it" }).size)
        assertEquals(200, Updater.notesBullets("- " + "x".repeat(500)).first().length)
    }

    @Test fun redirectHosts() {
        assertTrue(Updater.hostAllowed("https://github.com/a/b"))
        assertTrue(Updater.hostAllowed("https://objects.githubusercontent.com/x"))
        assertTrue(Updater.hostAllowed("https://release-assets.githubusercontent.com/x"))
        assertFalse(Updater.hostAllowed("http://github.com/a"))
        assertFalse(Updater.hostAllowed("https://evilgithub.com/a"))
        assertFalse(Updater.hostAllowed("https://github.com.evil.example/a"))
        assertFalse(Updater.hostAllowed("https://notgithubusercontent.com/a"))
        assertFalse(Updater.hostAllowed("ftp://github.com/a"))
        assertFalse(Updater.hostAllowed("garbage"))
    }

    @Test fun signerRules() {
        val a = setOf(listOf<Byte>(1, 2)); val b = setOf(listOf<Byte>(3, 4))
        assertTrue(Updater.signersAcceptable(a, a, emptySet()))
        assertFalse(Updater.signersAcceptable(a, b, emptySet()))
        assertFalse(Updater.signersAcceptable(a, b, b))
        assertTrue(Updater.signersAcceptable(a, b, a + b))
        assertFalse(Updater.signersAcceptable(emptySet(), a, a))
        assertFalse(Updater.signersAcceptable(a, emptySet(), a))
    }

    @Test fun hex() { assertEquals("00ff10", Updater.toHex(byteArrayOf(0, -1, 16))) }
}

class HardestHitTest {
    @Test fun newDriveResetsHardestHit() {
        val s = driveStartState(5, false, 99L)
        assertEquals(0f, s.hardestHitG, 0f)
        assertTrue(s.running); assertEquals(99L, s.driveId); assertEquals(6, s.tick)
    }
    @Test fun keepsTheMaximum() {
        assertEquals(1.4f, hardestAfterHit(1.4f, 0.9f), 0f)
        assertEquals(2.0f, hardestAfterHit(1.4f, 2.0f), 0f)
        assertEquals(0.7f, hardestAfterHit(0f, 0.7f), 0f)
    }
    @Test fun passengerStartIsPaused() { assertTrue(driveStartState(0, true, 1L).paused) }
    @Test fun hitLabelFollowsRunning() {
        assertEquals("HARDEST HIT THIS DRIVE", homeHardestHitLabel(true))
        assertEquals("HARDEST HIT LAST DRIVE", homeHardestHitLabel(false))
    }
    @Test fun labelFollowsRunning() {
        assertEquals("HITS THIS DRIVE", homeDriveLabel(true))
        assertEquals("HITS LAST DRIVE", homeDriveLabel(false))
    }
}
