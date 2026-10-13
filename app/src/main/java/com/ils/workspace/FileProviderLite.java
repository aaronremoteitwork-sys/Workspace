package com.ils.workspace;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Shares files from the app's private storage with the camera app (to save a photo)
 * and with share targets (barcode labels). Only files/photos and files/labels are exposed.
 */
public class FileProviderLite extends ContentProvider {

    static final String AUTHORITY = "com.ils.workspace.files";

    static Uri uriFor(String folder, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(folder).appendPath(name).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2) throw new FileNotFoundException(uri.toString());
        String folder = seg.get(0);
        String name = seg.get(1);
        if (!folder.equals("photos") && !folder.equals("labels") && !folder.equals("updates")) {
            throw new FileNotFoundException(uri.toString());
        }
        if (name.contains("/") || name.contains("..")) throw new FileNotFoundException(uri.toString());
        File dir = new File(getContext().getFilesDir(), folder);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getPath();
        if (p == null) return null;
        if (p.endsWith(".apk")) return "application/vnd.android.package-archive";
        return p.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
