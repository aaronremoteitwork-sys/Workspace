package com.ils.workspace;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import java.util.Locale;

/**
 * Reads the device serial number.
 *
 * Android 10+: Zebra's OEMInfo provider. Workspace must be granted access once with a StageNow
 * AccessMgr step (Service Identifier = the URI below, Caller Package = com.ils.workspace,
 * Caller Signature = ils.crt).
 * Android 8/9: Build.getSerial() with the READ_PHONE_STATE permission.
 */
final class Serial {

    private Serial() {}

    static final String OEMINFO_URI = "content://oem_info/oem.zebra.secure/build_serial";

    /** Saved after the first successful read; a serial number never changes. */
    static String cached(Context c) {
        return c.getSharedPreferences("workspace", Context.MODE_PRIVATE).getString("serial", null);
    }

    static void save(Context c, String serial) {
        c.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().putString("serial", serial).apply();
    }

    /** Call off the main thread. Returns null if the serial can't be read yet. */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    static String read(Context c) {
        try (Cursor cur = c.getContentResolver().query(Uri.parse(OEMINFO_URI), null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String s = cur.getString(0);
                if (valid(s)) return s.trim();
            }
        } catch (Exception ignored) {
            // Not granted yet, or provider not present.
        }
        if (Build.VERSION.SDK_INT < 29
                && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                String s = Build.getSerial();
                if (valid(s)) return s.trim();
            } catch (SecurityException ignored) {
            }
        }
        return null;
    }

    /** On Android 8/9 the app can ask for permission itself; on 10+ it needs the StageNow step. */
    static boolean canAskPermission(Context c) {
        return Build.VERSION.SDK_INT < 29
                && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED;
    }

    static String last4(String serial) {
        String s = serial.trim().toUpperCase(Locale.US);
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    private static boolean valid(String s) {
        return s != null && !s.trim().isEmpty() && !"unknown".equalsIgnoreCase(s.trim());
    }
}
