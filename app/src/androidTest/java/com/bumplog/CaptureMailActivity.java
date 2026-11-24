package com.bumplog;

import android.content.Intent;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;



import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Stands in for the user's mail app in device tests: records what the handoff
 * carried and reads the photo attachments through the URI grant while the grant
 * is live, the way a real mail app would when attaching them.
 *
 * This class is JAVA, not Kotlin, on purpose: it runs in the TEST package's own
 * process, whose classpath is only the test APK - and AGP dedupes library classes
 * the tested app already provides (all of kotlin-stdlib) out of the test APK.
 * Kotlin compilation itself injects stdlib references (jvm.internal.Intrinsics,
 * DefaultConstructorMarker), so a Kotlin version can never load in this process:
 * the class-load crashes with NoClassDefFoundError (CI runs 45306846156,
 * 45309923688 logcat). Java keeps the entire class dependency closure inside
 * the framework.
 */
public final class CaptureMailActivity extends Activity {

    public static volatile CountDownLatch latch;
    public static volatile Intent received;
    public static volatile Map<String, Integer> photoBytes = Collections.emptyMap();
    public static volatile String readFailure;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        received = getIntent();
        Intent intent = getIntent();
        ArrayList<Uri> uris = new ArrayList<>();
        if (intent != null) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                uris.addAll(many);
            }
            if (uris.isEmpty()) {
                Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) {
                    uris.add(one);
                }
            }
        }
        Map<String, Integer> sizes = new LinkedHashMap<>();
        try {
            for (Uri u : uris) {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    int n = 0;
                    byte[] buf = new byte[8192];
                    int r;
                    while (in != null && (r = in.read(buf)) != -1) {
                        n += r;
                    }
                    sizes.put(String.valueOf(u), n);
                }
            }
            photoBytes = sizes;
        } catch (Exception e) {
            readFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        CountDownLatch l = latch;
        if (l != null) {
            l.countDown();
        }
        finish();
    }
}
