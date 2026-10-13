package com.ils.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
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
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Workspace home screen.
 * - Shows your own apps (anything signed with your key) plus Camera, Chrome and Wi-Fi.
 * - Top-right gear: background color and updates (anyone can use it).
 * - Three quick taps in the top-left corner: admin menu to leave Workspace.
 */
public class HomeActivity extends Activity {

    private static final int DEFAULT_BG = Color.WHITE;
    private static final int[] SWATCHES = {
            0xFFFFFFFF, 0xFFF1F3F6, 0xFFF5EFE6, 0xFFDDEBF7, 0xFFE3F1E4,
            0xFF1F3A5F, 0xFF2B2F36, 0xFF000000
    };

    private SharedPreferences prefs;
    private LinearLayout root;
    private ImageButton gear;
    private TextView emptyView;
    private Button setDefaultBanner;
    private AppsAdapter adapter;

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

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBackground();
        reloadApps();
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

    // ---------- layout ----------

    private View buildUi() {
        root = Ui.vertical(this);

        // Top bar: invisible admin corner on the left, settings gear on the right.
        FrameLayout top = new FrameLayout(this);
        View corner = new View(this);
        corner.setOnClickListener(v -> onCornerTap());
        top.addView(corner, new FrameLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 64), Gravity.START | Gravity.TOP));

        gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_gear);
        gear.setContentDescription("Settings");
        int gp = Ui.dp(this, 12);
        gear.setPadding(gp, gp, gp, gp);
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setOnClickListener(v -> showSettings());
        top.addView(gear, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.END | Gravity.TOP));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));

        // Shown until Workspace is set as the default home screen.
        setDefaultBanner = new Button(this);
        setDefaultBanner.setText("Make Workspace the home screen");
        setDefaultBanner.setAllCaps(false);
        setDefaultBanner.setTextColor(Color.WHITE);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(Ui.PRIMARY);
        bannerBg.setCornerRadius(Ui.dp(this, 10));
        setDefaultBanner.setBackground(bannerBg);
        setDefaultBanner.setOnClickListener(v -> openHomeSettings());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        int m = Ui.dp(this, 16);
        blp.setMargins(m, 0, m, Ui.dp(this, 8));
        root.addView(setDefaultBanner, blp);

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
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
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

    // ---------- background ----------

    private int bgColor() {
        return prefs.getInt("bg", DEFAULT_BG);
    }

    @SuppressWarnings("deprecation")
    private void applyBackground() {
        int bg = bgColor();
        boolean light = Ui.isLight(bg);
        root.setBackgroundColor(bg);
        gear.setBackground(new RippleDrawable(ColorStateList.valueOf(light ? 0x22000000 : 0x33FFFFFF), null,
                Ui.circle(Color.BLACK, 0, 0)));
        gear.setColorFilter(light ? 0xFF3C4148 : 0xFFE6E9ED);
        emptyView.setTextColor(light ? Ui.MUTED : 0xFFB8C0C8);

        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        int flags = 0;
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        adapter.setLabelColor(light ? Ui.TEXT : Color.WHITE);
    }

    private void setBackground(int color) {
        prefs.edit().putInt("bg", color).apply();
        applyBackground();
    }

    // ---------- settings gear ----------

    private void showSettings() {
        LinearLayout box = Ui.vertical(this);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        box.addView(Ui.text(this, "Background", 15, Ui.TEXT, true));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        int size = Ui.dp(this, 40);
        for (int color : SWATCHES) {
            View sw = new View(this);
            boolean selected = color == bgColor();
            sw.setBackground(Ui.circle(color, Ui.dp(this, selected ? 3 : 1), selected ? 0xFFF2A900 : 0xFFB0B6BD));
            sw.setOnClickListener(v -> {
                setBackground(color);
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    int c = SWATCHES[i];
                    boolean sel = c == color;
                    child.setBackground(Ui.circle(c, Ui.dp(this, sel ? 3 : 1), sel ? 0xFFF2A900 : 0xFFB0B6BD));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = Ui.dp(this, 10);
            row.addView(sw, lp);
        }
        scroll.addView(row);
        box.addView(scroll);

        Button custom = new Button(this);
        custom.setText("Custom color…");
        custom.setAllCaps(false);
        box.addView(custom, Ui.matchWrap(this, 6));

        Button updates = new Button(this);
        updates.setText("Check for updates");
        updates.setAllCaps(false);
        box.addView(updates, Ui.matchWrap(this, 4));

        TextView version = Ui.text(this, "Workspace " + Updater.installedName(this), 13, Ui.MUTED, false);
        version.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(version);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Workspace settings")
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
        custom.setOnClickListener(v -> {
            d.dismiss();
            askCustomColor();
        });
        updates.setOnClickListener(v -> {
            d.dismiss();
            Updater.checkNow(this);
        });
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
                int c = Color.parseColor(s);
                setBackground(c | 0xFF000000);
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
                        "Open Settings",
                        "Change the default home app"
                }, (d, which) -> {
                    if (which == 0) openNormalHome();
                    else if (which == 1) openIntent(new Intent(Settings.ACTION_SETTINGS));
                    else openHomeSettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openNormalHome() {
        Intent other = Apps.otherHome(this);
        if (other == null) {
            Toast.makeText(this, "No other home screen found", Toast.LENGTH_SHORT).show();
            openHomeSettings();
            return;
        }
        openIntent(other);
    }

    private void openHomeSettings() {
        openIntent(new Intent(Settings.ACTION_HOME_SETTINGS));
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
            cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null,
                    roundedMask()));

            ImageView icon = new ImageView(HomeActivity.this);
            icon.setImageDrawable(e.icon);
            int s = Ui.dp(HomeActivity.this, 60);
            cell.addView(icon, new LinearLayout.LayoutParams(s, s));

            TextView label = Ui.text(HomeActivity.this, e.label, 13, labelColor, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setPadding(0, Ui.dp(HomeActivity.this, 6), 0, 0);
            cell.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return cell;
        }

        private GradientDrawable roundedMask() {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.BLACK);
            g.setCornerRadius(Ui.dp(HomeActivity.this, 14));
            return g;
        }
    }
}
