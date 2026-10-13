package com.ils.workspace;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Workspace home screen.
 * - Top-left: last 4 of the serial number. Three quick taps there open the Admin menu.
 * - Top-right gear: wallpapers, background color, updates (anyone can use it).
 * - Shows your own apps (anything signed with your key) plus Chrome.
 */
public class HomeActivity extends Activity {

    private static final int MODE_COLOR = 0;
    private static final int MODE_PRESET = 1;
    private static final int MODE_PHOTO = 2;

    private static final int REQ_PHOTO = 10;
    private static final int REQ_PHONE_STATE = 11;

    private static final int[] SWATCHES = {
            0xFFFFFFFF, 0xFFF1F3F6, 0xFFF5EFE6, 0xFFDDEBF7, 0xFFE3F1E4,
            0xFF1F3A5F, 0xFF2B2F36, 0xFF000000
    };

    private SharedPreferences prefs;
    private ImageView bgView;
    private LinearLayout content;
    private TextView serialView;
    private ImageButton gear;
    private TextView emptyView;
    private Button setDefaultBanner;
    private AppsAdapter adapter;

    private String appliedBgKey;
    private boolean darkBackground;

    private int cornerTaps;
    private long lastCornerTap;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("workspace", MODE_PRIVATE);
        setContentView(buildUi());
        setupSystemBars();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);

        if (Serial.cached(this) == null && Serial.canAskPermission(this)
                && !prefs.getBoolean("askedPhoneState", false)) {
            prefs.edit().putBoolean("askedPhoneState", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PHONE_STATE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBackground();
        reloadApps();
        refreshSerial();
        setDefaultBanner.setVisibility(Apps.isDefaultHome(this) ? View.GONE : View.VISIBLE);
        Updater.resumePendingInstall(this);
        Updater.maybeCheck(this);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packagesChanged);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    /** A home screen has nowhere to go back to. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Intentionally ignored.
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_PHONE_STATE) refreshSerial();
    }

    // ---------- layout ----------

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        bgView = new ImageView(this);
        bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bgView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = Ui.vertical(this);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: serial number (and the hidden 3-tap admin zone) on the left, gear on the right.
        FrameLayout top = new FrameLayout(this);
        FrameLayout corner = new FrameLayout(this);
        corner.setOnClickListener(v -> onCornerTap());
        serialView = Ui.text(this, "", 16, Ui.TEXT, true);
        serialView.setLetterSpacing(0.08f);
        serialView.setGravity(Gravity.CENTER_VERTICAL);
        serialView.setPadding(Ui.dp(this, 20), 0, 0, 0);
        corner.addView(serialView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        top.addView(corner, new FrameLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 64), Gravity.START | Gravity.TOP));

        gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_gear);
        gear.setContentDescription("Settings");
        int gp = Ui.dp(this, 12);
        gear.setPadding(gp, gp, gp, gp);
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.END | Gravity.CENTER_VERTICAL);
        glp.rightMargin = Ui.dp(this, 4);
        top.addView(gear, glp);
        content.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));

        // Shown until Workspace is the default home screen.
        setDefaultBanner = new Button(this);
        setDefaultBanner.setText("Make Workspace the home screen");
        setDefaultBanner.setAllCaps(false);
        setDefaultBanner.setTextColor(Color.WHITE);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(Ui.PRIMARY);
        bannerBg.setCornerRadius(Ui.dp(this, 10));
        setDefaultBanner.setBackground(bannerBg);
        setDefaultBanner.setOnClickListener(v -> openIntent(new Intent(Settings.ACTION_HOME_SETTINGS)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        int m = Ui.dp(this, 16);
        blp.setMargins(m, 0, m, Ui.dp(this, 8));
        content.addView(setDefaultBanner, blp);

        FrameLayout body = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 96));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 18));
        grid.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 24));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new AppsAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> launch(adapter.get(pos)));
        body.addView(grid);

        emptyView = Ui.text(this, "No apps yet.\nInstall ILS or Printers and they'll show up here.", 16, Ui.MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Draw the wallpaper behind the status and navigation bars; keep the content clear of them.
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        return root;
    }

    private void reloadApps() {
        adapter.set(Apps.load(this));
        emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void launch(Apps.Entry e) {
        try {
            startActivity(e.intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Couldn't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- serial number ----------

    private void refreshSerial() {
        String cached = Serial.cached(this);
        if (cached != null) {
            serialView.setText(Serial.last4(cached));
            serialView.setAlpha(1f);
            return;
        }
        serialView.setText("SN ?");
        serialView.setAlpha(0.6f);
        new Thread(() -> {
            String s = Serial.read(getApplicationContext());
            if (s == null) return;
            Serial.save(getApplicationContext(), s);
            runOnUiThread(() -> {
                serialView.setText(Serial.last4(s));
                serialView.setAlpha(1f);
            });
        }).start();
    }

    // ---------- background ----------

    @SuppressWarnings("deprecation")
    private void setupSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        updateSystemBarIcons();
    }

    @SuppressWarnings("deprecation")
    private void updateSystemBarIcons() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!darkBackground) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int mode() {
        return prefs.getInt("mode", MODE_COLOR);
    }

    private int bgColor() {
        return prefs.getInt("bg", Color.WHITE);
    }

    private int presetIndex() {
        int i = prefs.getInt("preset", 0);
        return (i >= 0 && i < Wallpapers.PRESETS.length) ? i : 0;
    }

    private void applyBackground() {
        int mode = mode();
        String key = mode + ":" + (mode == MODE_COLOR ? bgColor() : mode == MODE_PRESET ? presetIndex()
                : Wallpapers.photoFile(this).lastModified());
        if (key.equals(appliedBgKey)) return;
        appliedBgKey = key;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, dm.heightPixels);
        int h = Math.max(dm.widthPixels, dm.heightPixels);

        if (mode == MODE_PRESET) {
            Wallpapers.Preset p = Wallpapers.PRESETS[presetIndex()];
            bgView.setImageBitmap(Wallpapers.render(p, w, h));
            darkBackground = p.dark;
        } else if (mode == MODE_PHOTO) {
            Bitmap b = Wallpapers.loadPhoto(this, h);
            if (b == null) {
                prefs.edit().putInt("mode", MODE_COLOR).apply();
                appliedBgKey = null;
                applyBackground();
                return;
            }
            bgView.setImageBitmap(b);
            darkBackground = Wallpapers.isDark(b);
        } else {
            bgView.setImageDrawable(new ColorDrawable(bgColor()));
            darkBackground = !Ui.isLight(bgColor());
        }

        int fg = darkBackground ? Color.WHITE : Ui.TEXT;
        serialView.setTextColor(fg);
        setShadow(serialView);
        gear.setColorFilter(darkBackground ? 0xFFF2F4F6 : 0xFF3C4148);
        gear.setBackground(new RippleDrawable(ColorStateList.valueOf(darkBackground ? 0x33FFFFFF : 0x22000000),
                null, Ui.circle(Color.BLACK, 0, 0)));
        emptyView.setTextColor(darkBackground ? 0xFFE0E4E8 : Ui.MUTED);
        setShadow(emptyView);
        adapter.setLabelColor(fg);
        updateSystemBarIcons();
    }

    /** Soft shadow under white text so it stays readable on busy wallpapers. */
    private void setShadow(TextView t) {
        if (darkBackground) t.setShadowLayer(Ui.dp(this, 3), 0, Ui.dp(this, 1), 0x99000000);
        else t.setShadowLayer(0, 0, 0, 0);
    }

    private void useColor(int color) {
        prefs.edit().putInt("mode", MODE_COLOR).putInt("bg", color | 0xFF000000).apply();
        applyBackground();
    }

    private void usePreset(int index) {
        prefs.edit().putInt("mode", MODE_PRESET).putInt("preset", index).apply();
        applyBackground();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxSide = Math.max(dm.widthPixels, dm.heightPixels);
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = Wallpapers.savePhoto(getApplicationContext(), uri, maxSide);
            runOnUiThread(() -> {
                if (!ok) {
                    Toast.makeText(this, "Couldn't use that picture", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putInt("mode", MODE_PHOTO).apply();
                appliedBgKey = null;
                applyBackground();
            });
        }).start();
    }

    // ---------- settings gear ----------

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = Ui.vertical(this);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        scroll.addView(box);

        // Wallpapers
        box.addView(Ui.text(this, "Wallpapers", 15, Ui.TEXT, true));
        HorizontalScrollView wallScroll = new HorizontalScrollView(this);
        wallScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout wallRow = new LinearLayout(this);
        wallRow.setOrientation(LinearLayout.HORIZONTAL);
        wallRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        int tw = Ui.dp(this, 62);
        int th = Ui.dp(this, 108);
        List<View> thumbs = new ArrayList<>();
        for (int i = 0; i < Wallpapers.PRESETS.length; i++) {
            Wallpapers.Preset preset = Wallpapers.PRESETS[i];
            LinearLayout cell = Ui.vertical(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView thumb = new ImageView(this);
            thumb.setImageDrawable(new BitmapDrawable(getResources(), Wallpapers.render(preset, tw, th)));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            thumb.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            cell.addView(thumb, new LinearLayout.LayoutParams(tw, th));
            TextView name = Ui.text(this, preset.name, 12, Ui.MUTED, false);
            name.setPadding(0, Ui.dp(this, 4), 0, 0);
            cell.addView(name);
            final int index = i;
            thumb.setOnClickListener(v -> {
                usePreset(index);
                markThumbs(thumbs, index);
            });
            thumbs.add(thumb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 10);
            wallRow.addView(cell, lp);
        }
        markThumbs(thumbs, mode() == MODE_PRESET ? presetIndex() : -1);
        wallScroll.addView(wallRow);
        box.addView(wallScroll);

        Button photo = new Button(this);
        photo.setText("Use a photo from this device…");
        photo.setAllCaps(false);
        box.addView(photo, Ui.matchWrap(this, 6));

        // Solid colors
        TextView colorsTitle = Ui.text(this, "Solid colors", 15, Ui.TEXT, true);
        colorsTitle.setPadding(0, Ui.dp(this, 16), 0, 0);
        box.addView(colorsTitle);
        HorizontalScrollView colorScroll = new HorizontalScrollView(this);
        colorScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        int size = Ui.dp(this, 40);
        for (int color : SWATCHES) {
            View sw = new View(this);
            sw.setOnClickListener(v -> {
                useColor(color);
                markThumbs(thumbs, -1);
                markSwatches(colorRow, color);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = Ui.dp(this, 10);
            colorRow.addView(sw, lp);
        }
        markSwatches(colorRow, mode() == MODE_COLOR ? bgColor() : 0);
        colorScroll.addView(colorRow);
        box.addView(colorScroll);

        Button custom = new Button(this);
        custom.setText("Custom color…");
        custom.setAllCaps(false);
        box.addView(custom, Ui.matchWrap(this, 4));

        Button updates = new Button(this);
        updates.setText("Check for updates");
        updates.setAllCaps(false);
        box.addView(updates, Ui.matchWrap(this, 16));

        String serial = Serial.cached(this);
        TextView info = Ui.text(this, "Workspace " + Updater.installedName(this) + "\nSerial number: "
                + (serial != null ? serial : "not available yet"), 13, Ui.MUTED, false);
        info.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(info);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Workspace settings")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
        photo.setOnClickListener(v -> {
            d.dismiss();
            pickPhoto();
        });
        custom.setOnClickListener(v -> {
            d.dismiss();
            askCustomColor();
        });
        updates.setOnClickListener(v -> {
            d.dismiss();
            Updater.checkNow(this);
        });
    }

    private void markThumbs(List<View> thumbs, int selected) {
        for (int i = 0; i < thumbs.size(); i++) {
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(Ui.dp(this, 12));
            frame.setColor(Color.TRANSPARENT);
            frame.setStroke(Ui.dp(this, i == selected ? 3 : 1), i == selected ? 0xFFF2A900 : 0xFFD0D5DA);
            thumbs.get(i).setBackground(frame);
        }
    }

    private void markSwatches(LinearLayout row, int selectedColor) {
        for (int i = 0; i < row.getChildCount(); i++) {
            int c = SWATCHES[i];
            boolean sel = c == selectedColor;
            row.getChildAt(i).setBackground(Ui.circle(c, Ui.dp(this, sel ? 3 : 1), sel ? 0xFFF2A900 : 0xFFB0B6BD));
        }
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choose a wallpaper"), REQ_PHOTO);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No photo picker on this device", Toast.LENGTH_LONG).show();
        }
    }

    private void askCustomColor() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("#RRGGBB, e.g. #FFF4E0");
        input.setText(String.format(Locale.US, "#%06X", bgColor() & 0xFFFFFF));
        FrameLayout wrap = new FrameLayout(this);
        int p = Ui.dp(this, 22);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(input);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Custom background")
                .setView(wrap)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Cancel", null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String s = input.getText().toString().trim();
            if (!s.startsWith("#")) s = "#" + s;
            try {
                useColor(Color.parseColor(s));
                d.dismiss();
            } catch (IllegalArgumentException e) {
                input.setError("Use a color like #FFF4E0");
            }
        });
    }

    // ---------- admin exit (3 quick taps, top-left) ----------

    private void onCornerTap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastCornerTap > 700) cornerTaps = 0;
        lastCornerTap = now;
        cornerTaps++;
        if (cornerTaps >= 3) {
            cornerTaps = 0;
            showAdminMenu();
        }
    }

    private void showAdminMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Admin")
                .setItems(new String[]{
                        "Open the normal home screen",
                        "Open Settings"
                }, (d, which) -> {
                    if (which == 0) openNormalHome();
                    else openIntent(new Intent(Settings.ACTION_SETTINGS));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openNormalHome() {
        Intent other = Apps.otherHome(this);
        if (other == null) {
            Toast.makeText(this, "No other home screen found", Toast.LENGTH_SHORT).show();
            return;
        }
        openIntent(other);
    }

    private void openIntent(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "That screen is blocked on this device", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- grid ----------

    private class AppsAdapter extends BaseAdapter {
        private List<Apps.Entry> items = new ArrayList<>();
        private int labelColor = Ui.TEXT;

        void set(List<Apps.Entry> l) {
            items = l;
            notifyDataSetChanged();
        }

        void setLabelColor(int c) {
            labelColor = c;
            notifyDataSetChanged();
        }

        Apps.Entry get(int i) {
            return items.get(i);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Apps.Entry e = items.get(i);
            LinearLayout cell = Ui.vertical(HomeActivity.this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(HomeActivity.this, 6);
            cell.setPadding(p, p, p, p);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.BLACK);
            mask.setCornerRadius(Ui.dp(HomeActivity.this, 14));
            cell.setBackground(new RippleDrawable(ColorStateList.valueOf(darkBackground ? 0x33FFFFFF : 0x22000000), null, mask));

            ImageView icon = new ImageView(HomeActivity.this);
            icon.setImageDrawable(e.icon);
            int s = Ui.dp(HomeActivity.this, 60);
            cell.addView(icon, new LinearLayout.LayoutParams(s, s));

            TextView label = Ui.text(HomeActivity.this, e.label, 13, labelColor, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setPadding(0, Ui.dp(HomeActivity.this, 6), 0, 0);
            setShadow(label);
            cell.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return cell;
        }
    }
}
