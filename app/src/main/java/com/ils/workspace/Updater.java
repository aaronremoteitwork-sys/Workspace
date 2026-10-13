package com.ils.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub for a newer Workspace release, downloads it, verifies it, and hands it to Android's installer.
 *
 * Data safety: Android keeps all app data when an update is installed over the existing app, as long as
 * the update has the same package name, the same signing key and a higher version. This class checks
 * all three before offering the install. If any check fails, nothing is installed and nothing changes.
 */
final class Updater {

    private Updater() {}

    /** GitHub "owner/repo" that the build workflow publishes releases to. */
    static final String REPO = "aaronremoteitwork-sys/Workspace";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String APK_NAME = "Workspace.apk";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    /** A home screen stays open for days, so check on a timer instead of once per launch. */
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private static long lastCheck;
    private static boolean busy;
    private static File pendingApk;

    static final class Release {
        long version;
        String name;
        String url;
        long size;
    }

    // ---------- entry points ----------

    /** Called whenever the home screen is shown. Checks quietly, at most every 6 hours. */
    static void maybeCheck(Activity a) {
        long now = System.currentTimeMillis();
        if (lastCheck != 0 && now - lastCheck < CHECK_EVERY_MS) return;
        lastCheck = now;
        clearDownloads(a);
        check(a, false);
    }

    /** Menu "Check for updates". Reports the result even when there's nothing new. */
    static void checkNow(Activity a) {
        check(a, true);
    }

    /** Called from onResume: continues an install after the user grants the install permission. */
    static void resumePendingInstall(Activity a) {
        if (pendingApk != null && a.getPackageManager().canRequestPackageInstalls()) install(a);
    }

    static long installedVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return versionOf(p);
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------- check ----------

    private static void check(Activity a, boolean manual) {
        if (busy) return;
        busy = true;
        if (manual) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Release r;
            try {
                r = fetchLatest();
            } catch (Exception e) {
                r = null;
            }
            final Release rel = r;
            a.runOnUiThread(() -> {
                busy = false;
                if (!alive(a)) return;
                if (rel == null) {
                    if (manual) Toast.makeText(a, "Couldn't reach the update server. Check Wi-Fi and try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (rel.version <= installedVersion(a)) {
                    if (manual) Toast.makeText(a, "Workspace is up to date (version " + installedName(a) + ")", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(a)
                        .setTitle("Update available")
                        .setMessage("Workspace " + rel.name + " is ready. You have " + installedName(a) + ".\n\n"
                                + "Your settings are kept.")
                        .setPositiveButton("Update", (d, w) -> download(a, rel))
                        .setNegativeButton("Later", null)
                        .show();
            });
        }).start();
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "Workspace-Android");
        try {
            if (con.getResponseCode() != 200) return null;
            JSONObject o;
            try (InputStream in = con.getInputStream()) {
                o = new JSONObject(readAll(in));
            }
            String tag = o.optString("tag_name", "");
            Matcher m = Pattern.compile("(\\d+)$").matcher(tag);
            if (!m.find()) return null;
            Release r = new Release();
            r.version = Long.parseLong(m.group(1));
            r.name = tag.startsWith("v") ? tag.substring(1) : tag;
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (APK_NAME.equals(asset.optString("name"))) {
                    r.url = asset.optString("browser_download_url", "");
                    r.size = asset.optLong("size", 0);
                }
            }
            return (r.url == null || r.url.isEmpty()) ? null : r;
        } finally {
            con.disconnect();
        }
    }

    // ---------- download ----------

    private static void download(Activity a, Release rel) {
        LinearLayout box = Ui.vertical(a);
        int p = Ui.dp(a, 24);
        box.setPadding(p, Ui.dp(a, 12), p, 0);
        TextView status = Ui.text(a, "Downloading Workspace " + rel.name + "…", 15, Ui.TEXT, false);
        box.addView(status);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(rel.size <= 0);
        box.addView(bar, Ui.matchWrap(a, 12));

        final boolean[] cancelled = {false};
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Updating Workspace")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true)
                .show();

        File dir = downloadsDir(a);
        clearDownloads(a);
        File apk = new File(dir, "Workspace-" + rel.version + ".apk");

        new Thread(() -> {
            String error = null;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(rel.url).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent", "Workspace-Android");
                try {
                    int code = con.getResponseCode();
                    if (code != 200) throw new IOException("server answered " + code);
                    long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : rel.size;
                    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                        byte[] buf = new byte[16384];
                        long done = 0;
                        int lastPct = -1;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancelled[0]) throw new IOException("cancelled");
                            out.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                final int pct = (int) Math.min(100, done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    a.runOnUiThread(() -> bar.setProgress(pct));
                                }
                            }
                        }
                    }
                } finally {
                    con.disconnect();
                }
                error = verify(a, apk);
            } catch (Exception e) {
                error = "The download didn't finish (" + e.getMessage() + ").";
            }

            final String err = error;
            a.runOnUiThread(() -> {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
                if (cancelled[0]) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    return;
                }
                if (err != null) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    if (alive(a)) {
                        new AlertDialog.Builder(a)
                                .setTitle("Update not installed")
                                .setMessage(err + "\n\nNothing was changed.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    return;
                }
                pendingApk = apk;
                if (alive(a)) install(a);
            });
        }).start();
    }

    /**
     * Makes sure the downloaded file is a newer Workspace signed with the same key as the installed app.
     * Returns null when it's safe to install, otherwise a message explaining why not.
     */
    private static String verify(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (update == null) return "The downloaded file isn't a valid app.";
        if (!c.getPackageName().equals(update.packageName)) return "The downloaded file isn't Workspace.";
        if (versionOf(update) <= installedVersion(c)) return "The download isn't newer than the version you have.";
        try {
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flags);
            Set<String> a = signers(mine);
            Set<String> b = signers(update);
            if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                return "The update was signed with a different key, so it can't replace this copy of Workspace.";
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) {
        Set<String> out = new HashSet<>();
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            SigningInfo si = p.signingInfo;
            sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        } else if (p.signatures != null) {
            sigs = p.signatures;
        }
        if (sigs != null) for (Signature s : sigs) out.add(s.toCharsString());
        return out;
    }

    // ---------- install ----------

    private static void install(Activity a) {
        if (pendingApk == null || !pendingApk.exists()) {
            pendingApk = null;
            return;
        }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("One-time permission")
                    .setMessage("Android needs you to allow Workspace to install its own updates.\n\n"
                            + "On the next screen, turn on \"Allow from this source\", then press Back.")
                    .setPositiveButton("Continue", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(a, "Couldn't open the setting", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", (d, w) -> pendingApk = null)
                    .show();
            return;
        }
        File apk = pendingApk;
        pendingApk = null;
        Uri uri = FileProviderLite.uriFor("updates", apk.getName());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, APK_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "Couldn't open the installer", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- helpers ----------

    private static File downloadsDir(Context c) {
        File d = new File(c.getFilesDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Removes old downloaded update files (they're only needed until the install finishes). */
    private static void clearDownloads(Context c) {
        if (pendingApk != null) return;
        File[] old = downloadsDir(c).listFiles();
        if (old == null) return;
        for (File f : old) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionOf(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static boolean alive(Activity a) {
        return !a.isFinishing() && !a.isDestroyed();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
