package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

public final class ScanStorage {

    private static final String PREFS = "plant_scans_prefs";
    private static final String KEY_LIST = "recent_scans";
    private static final int MAX_ITEMS = 30;

    private ScanStorage() {}

    public static File saveScanJpeg(Context context, Bitmap bitmap) throws Exception {
        File dir = new File(context.getFilesDir(), "scans");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Не удалось создать папку scans");
        }

        long ts = System.currentTimeMillis();
        File file = new File(dir, "scan_" + ts + ".jpg");

        // уменьшаем, чтобы не слать огромные фото в сеть
        Bitmap scaled = scaleDown(bitmap, 1024);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        }

        if (scaled != bitmap) {
            scaled.recycle();
        }

        return file;
    }

    public static Bitmap decodeSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
        opts.inJustDecodeBounds = false;

        return BitmapFactory.decodeFile(path, opts);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static Bitmap scaleDown(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxSide) return src;

        float scale = (float) maxSide / (float) max;
        int nw = Math.round(w * scale);
        int nh = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    // --- история с результатами (path + disease + confidence + recs) ---

    public static void addScan(Context context, ScanItem item) {
        ArrayList<ScanItem> list = loadScans(context);

        // удалить дубликат по path
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).imagePath.equals(item.imagePath)) {
                list.remove(i);
                break;
            }
        }

        list.add(0, item);

        while (list.size() > MAX_ITEMS) {
            list.remove(list.size() - 1);
        }

        saveScans(context, list);
    }

    public static ArrayList<ScanItem> loadScans(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_LIST, "[]");
        ArrayList<ScanItem> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new ScanItem(
                        o.optString("path", ""),
                        o.optString("disease", "—"),
                        o.optInt("confidence", 0),
                        o.optString("recs", "—"),
                        o.optLong("ts", 0L)
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static void saveScans(Context context, ArrayList<ScanItem> list) {
        JSONArray arr = new JSONArray();
        try {
            for (ScanItem it : list) {
                JSONObject o = new JSONObject();
                o.put("path", it.imagePath);
                o.put("disease", it.disease);
                o.put("confidence", it.confidencePercent);
                o.put("recs", it.recommendations);
                o.put("ts", it.timestamp);
                arr.put(o);
            }
        } catch (Exception ignored) {}

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIST, arr.toString())
                .apply();
    }

    public static ScanItem getLatest(Context context) {
        ArrayList<ScanItem> list = loadScans(context);
        return list.isEmpty() ? null : list.get(0);
    }
}