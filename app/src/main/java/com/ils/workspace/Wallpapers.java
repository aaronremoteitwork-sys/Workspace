package com.ils.workspace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Built-in wallpapers, drawn in code as soft "mesh gradient" blends (no image files, crisp at any size),
 * plus a photo the user picks from the device.
 */
final class Wallpapers {

    private Wallpapers() {}

    static final class Preset {
        final String name;
        final int base;
        final boolean dark;
        /** Each blob: color, centerX, centerY, radius (fractions of the screen). */
        final float[][] blobs;
        final int[] colors;

        Preset(String name, int base, boolean dark, int[] colors, float[][] blobs) {
            this.name = name;
            this.base = base;
            this.dark = dark;
            this.colors = colors;
            this.blobs = blobs;
        }
    }

    static final Preset[] PRESETS = {
            new Preset("Peach", 0xFFFDE2D3, false,
                    new int[]{0xFFF7A9A8, 0xFFFFD6A5, 0xFFF4B8E4},
                    new float[][]{{0.10f, 0.15f, 0.65f}, {0.95f, 0.40f, 0.60f}, {0.35f, 0.95f, 0.70f}}),
            new Preset("Lavender", 0xFFE9E3FF, false,
                    new int[]{0xFFC7B8FF, 0xFFF1C6E7, 0xFFB9E0FF},
                    new float[][]{{0.15f, 0.10f, 0.65f}, {0.95f, 0.50f, 0.60f}, {0.25f, 0.95f, 0.65f}}),
            new Preset("Mint", 0xFFE3F6EF, false,
                    new int[]{0xFFB8EBD5, 0xFFCDEBFF, 0xFFF3F0C8},
                    new float[][]{{0.05f, 0.30f, 0.65f}, {0.95f, 0.15f, 0.55f}, {0.60f, 0.95f, 0.65f}}),
            new Preset("Sky", 0xFFDCEBFA, false,
                    new int[]{0xFFA9CCF2, 0xFFE3D9FB, 0xFFFFFFFF},
                    new float[][]{{0.20f, 0.05f, 0.65f}, {0.90f, 0.55f, 0.60f}, {0.25f, 0.90f, 0.55f}}),
            new Preset("Sand", 0xFFF3E9DC, false,
                    new int[]{0xFFE6CBA8, 0xFFF7DCCB, 0xFFEADFC9},
                    new float[][]{{0.10f, 0.25f, 0.65f}, {0.95f, 0.60f, 0.60f}, {0.40f, 0.95f, 0.65f}}),
            new Preset("Ocean", 0xFF0E2A47, true,
                    new int[]{0xFF1D5C8C, 0xFF0F766E, 0xFF312E81},
                    new float[][]{{0.15f, 0.10f, 0.75f}, {0.95f, 0.60f, 0.65f}, {0.30f, 0.95f, 0.65f}}),
            new Preset("Dusk", 0xFF2A1B3D, true,
                    new int[]{0xFF7B2D5B, 0xFFD9805F, 0xFF44318D},
                    new float[][]{{0.10f, 0.20f, 0.70f}, {0.90f, 0.80f, 0.60f}, {0.65f, 0.05f, 0.60f}}),
            new Preset("Forest", 0xFF12261E, true,
                    new int[]{0xFF1F4D3A, 0xFF3F6B3A, 0xFF0E3B43},
                    new float[][]{{0.15f, 0.15f, 0.75f}, {0.90f, 0.70f, 0.65f}, {0.40f, 0.95f, 0.65f}}),
            new Preset("Aurora", 0xFF0B1A2A, true,
                    new int[]{0xFF11C5A0, 0xFF6D5BD0, 0xFF1E88E5},
                    new float[][]{{0.15f, 0.25f, 0.60f}, {0.90f, 0.45f, 0.60f}, {0.40f, 0.95f, 0.65f}}),
            new Preset("Midnight", 0xFF0B1020, true,
                    new int[]{0xFF1E2A5A, 0xFF3B1E5A, 0xFF16324F},
                    new float[][]{{0.15f, 0.10f, 0.75f}, {0.90f, 0.85f, 0.65f}, {0.85f, 0.20f, 0.45f}}),
    };

    static Bitmap render(Preset p, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(p.base);
        float size = Math.max(w, h);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        for (int i = 0; i < p.blobs.length; i++) {
            float cx = p.blobs[i][0] * w;
            float cy = p.blobs[i][1] * h;
            float r = p.blobs[i][2] * size;
            int color = p.colors[i];
            int solid = (color & 0x00FFFFFF) | 0xE6000000;
            int clear = color & 0x00FFFFFF;
            paint.setShader(new RadialGradient(cx, cy, r, new int[]{solid, clear}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
        }
        return bmp;
    }

    // ---------- the user's own photo ----------

    static File photoFile(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    /** Copies the picked image into Workspace's storage, scaled to the screen. Call off the main thread. */
    static boolean savePhoto(Context c, Uri uri, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return false;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return false;
            try (FileOutputStream out = new FileOutputStream(photoFile(c))) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            b.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Bitmap loadPhoto(Context c, int maxSide) {
        File f = photoFile(c);
        if (!f.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    /** Whether a photo is mostly dark, so labels on it should be white. */
    static boolean isDark(Bitmap b) {
        Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
        boolean dark = !Ui.isLight(one.getPixel(0, 0));
        if (one != b) one.recycle();
        return dark;
    }
}
