package com.bumplog;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * 45309923688 logcat). Java keeps the entire dependency closure inside the
 * framework (dexdump-verified: 22 types, all android or java).
 *
 * What it receives is recorded into CaptureProvider - this activity runs in a
 * DIFFERENT process from the test classes, so static fields cannot carry the
 * observation back (CI run 45309923688: "Process: com.bumplog.test").
 */
public final class CaptureMailActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent received = getIntent();
        Map<String, String> obs = new LinkedHashMap<>();
        if (received != null) {
            obs.put("action", String.valueOf(received.getAction()));
            obs.put("data", received.getDataString());
            String[] emails = received.getStringArrayExtra(Intent.EXTRA_EMAIL);
            if (emails != null) {
                StringBuilder sb = new StringBuilder();
                for (String e : emails) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(e);
                }
                obs.put("email", sb.toString());
            }
            obs.put("subject", received.getStringExtra(Intent.EXTRA_SUBJECT));
            obs.put("body", received.getStringExtra(Intent.EXTRA_TEXT));
        }
        ArrayList<Uri> uris = new ArrayList<>();
        if (received != null) {
            ArrayList<Uri> many = received.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                uris.addAll(many);
            }
            if (uris.isEmpty()) {
                Uri one = received.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) {
                    uris.add(one);
                }
            }
        }
        StringBuilder sizes = new StringBuilder();
        String readFailure = null;
        try {
            for (Uri u : uris) {
                int n = 0;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while (in != null && (r = in.read(buf)) != -1) {
                        n += r;
                    }
                }
                if (sizes.length() > 0) sizes.append('\n');
                sizes.append(u).append('=').append(n);
            }
        } catch (Exception e) {
            readFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (sizes.length() > 0) {
            obs.put("photoBytes", sizes.toString());
        }
        if (readFailure != null) {
            obs.put("readFailure", readFailure);
        }
        obs.put("handlerPid", String.valueOf(android.os.Process.myPid()));
        CaptureProvider.record(obs);
        finish();
    }
}
