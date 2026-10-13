package com.ils.workspace;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which apps appear on the Workspace home screen:
 * every installed app signed with the same key as Workspace (ILS, Printers, anything you build later),
 * followed by Camera, Chrome and a Wi-Fi settings shortcut.
 */
final class Apps {

    private Apps() {}

    static final class Entry {
        String label;
        Drawable icon;
        Intent intent;
    }

    static final String CHROME = "com.android.chrome";

    static List<Entry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Entry> mine = new ArrayList<>();
        Set<String> myKeys = signers(pm, c.getPackageName());

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            Set<String> keys = signers(pm, pkg);
            if (myKeys.isEmpty() || !myKeys.equals(keys)) continue;
            Entry e = new Entry();
            e.label = String.valueOf(ri.loadLabel(pm));
            e.icon = ri.loadIcon(pm);
            e.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            mine.add(e);
        }
        Collections.sort(mine, (a, b) -> a.label.toLowerCase(Locale.US).compareTo(b.label.toLowerCase(Locale.US)));

        List<Entry> out = new ArrayList<>(mine);

        // Camera
        Intent cam = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        ResolveInfo camInfo = pm.resolveActivity(cam, 0);
        if (camInfo != null) {
            Entry e = new Entry();
            e.label = "Camera";
            e.icon = camInfo.loadIcon(pm);
            e.intent = cam.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(e);
        }

        // Chrome
        Intent chrome = pm.getLaunchIntentForPackage(CHROME);
        if (chrome != null) {
            Entry e = new Entry();
            e.label = "Chrome";
            try {
                e.icon = pm.getApplicationIcon(CHROME);
            } catch (PackageManager.NameNotFoundException ex) {
                e.icon = pm.getDefaultActivityIcon();
            }
            e.intent = chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(e);
        }

        // Wi-Fi settings page only (not all of Settings)
        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        ResolveInfo wifiInfo = pm.resolveActivity(wifi, 0);
        if (wifiInfo != null) {
            Entry e = new Entry();
            e.label = "Wi-Fi";
            e.icon = c.getDrawable(R.drawable.ic_wifi);
            e.intent = wifi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(e);
        }
        return out;
    }

    /** True when Workspace is the device's default home screen. */
    static boolean isDefaultHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** The phone's regular home screen (Zebra's launcher), or null if none is found. */
    static Intent otherHome(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            if (ri.activityInfo.name.endsWith("FallbackHome")) continue; // Settings' boot placeholder
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    static Set<String> signers(PackageManager pm, String pkg) {
        Set<String> out = new HashSet<>();
        try {
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo p = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = p.signingInfo;
                if (si == null) return out;
                sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs != null) for (Signature s : sigs) out.add(s.toCharsString());
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return out;
    }
}
