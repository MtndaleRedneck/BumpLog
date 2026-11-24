package com.bumplog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.CountDownLatch

/**
 * Stands in for the user's mail app in device tests: records what the handoff
 * carried and reads the photo attachments through the URI grant while the grant
 * is live, the way a real mail app would when attaching them.
 */
class CaptureMailActivity : Activity() {

    companion object {
        @Volatile var latch: CountDownLatch? = null
        @Volatile var received: Intent? = null
        @Volatile var photoBytes: Map<String, Int> = emptyMap()
        @Volatile var readFailure: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        received = intent
        val sizes = LinkedHashMap<String, Int>()
        val uris: List<Uri> =
            intent?.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList()
                ?: listOfNotNull(intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        for (u in uris) {
            try {
                val n = contentResolver.openInputStream(u)?.use { it.readBytes().size } ?: -1
                sizes[u.toString()] = n
            } catch (e: Exception) {
                readFailure = e.javaClass.simpleName + ": " + e.message
            }
        }
        photoBytes = sizes
        latch?.countDown()
        finish()
    }
}
