package com.emp.predictor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Reads the text in a ticket screenshot on the phone, using Tesseract (no internet needed). */
public final class Ocr {
    private static final String ASSET = "tessdata/eng.traineddata";

    private Ocr() {}

    /** The text in the image at {@code uri}. Slow (a few seconds): call off the main thread. */
    public static String read(Context context, Uri uri) throws IOException {
        Bitmap bitmap = decode(context, uri);
        File dir = ensureData(context);
        TessBaseAPI api;
        try {
            api = new TessBaseAPI();
        } catch (Throwable e) {
            throw new IOException("Text recognition isn't available on this phone", e);
        }
        try {
            if (!api.init(dir.getAbsolutePath(), "eng")) throw new IOException("Could not start text recognition");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            api.setImage(bitmap);
            String text = api.getUTF8Text();
            return text == null ? "" : text;
        } finally {
            api.end();
            bitmap.recycle();
        }
    }

    private static Bitmap decode(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0) throw new IOException("That file isn't a picture");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        // Screenshots are ~1080 px wide; keep big images around that size so OCR stays quick.
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1000) opts.inSampleSize *= 2;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, opts);
        }
        if (b == null) throw new IOException("Could not open the picture");
        if (b.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
            b.recycle();
            b = c;
        }
        return b;
    }

    /** Tesseract needs its language data as a file: copied out of the app once. */
    private static File ensureData(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "tesseract");
        File data = new File(root, ASSET);
        if (data.exists() && data.length() > 1_000_000) return root;
        File dir = data.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare text recognition");
        File tmp = new File(dir, "eng.traineddata.tmp");
        try (InputStream in = context.getAssets().open(ASSET); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(data)) throw new IOException("Could not prepare text recognition");
        return root;
    }
}
