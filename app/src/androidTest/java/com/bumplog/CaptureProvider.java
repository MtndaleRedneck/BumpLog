package com.bumplog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-process observation channel for the controlled mail handler.
 *
 * CaptureMailActivity runs in this TEST package's own process (com.bumplog.test)
 * while the instrumented test classes run in the TARGET app's process
 * (com.bumplog); static fields never cross that boundary (CI run 45309923688:
 * the activity's crash logcat names "Process: com.bumplog.test"). The activity
 * therefore records what it received HERE, in its own process, and the test
 * reads the record back over Binder. Java for the same reason as
 * CaptureMailActivity: this class loads in the test process, whose classpath is
 * only the test APK, and AGP dedupes kotlin-stdlib out of it.
 */
public final class CaptureProvider extends ContentProvider {

    public static final String AUTHORITY = "com.bumplog.test.capture";

    private static final Object LOCK = new Object();
    private static final ArrayList<Map<String, String>> OBSERVATIONS = new ArrayList<>();

    /** Called by CaptureMailActivity, always in this process. */
    static void record(Map<String, String> observation) {
        synchronized (LOCK) {
            OBSERVATIONS.add(new LinkedHashMap<>(observation));
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        synchronized (LOCK) {
            MatrixCursor c = new MatrixCursor(new String[]{
                "n", "action", "data", "email", "subject", "body", "readFailure", "photoBytes", "handlerPid"
            });
            int i = 0;
            for (Map<String, String> o : OBSERVATIONS) {
                c.addRow(new Object[]{
                    i++,
                    o.get("action"), o.get("data"), o.get("email"),
                    o.get("subject"), o.get("body"),
                    o.get("readFailure"), o.get("photoBytes"), o.get("handlerPid")
                });
            }
            return c;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("reset".equals(method)) {
            synchronized (LOCK) {
                OBSERVATIONS.clear();
            }
        }
        return new Bundle();
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
