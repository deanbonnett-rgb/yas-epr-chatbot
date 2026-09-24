package com.emp.predictor;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads the draw history: the copy bundled in assets, plus any newer draws downloaded since and
 * saved to app storage.
 */
public final class ResultsStore {
    private static final String BUNDLED = "euromillions.csv";
    private static final String SAVED = "euromillions_updated.csv";

    /** Tried in order; newer draws from each are merged in. */
    private static final String[] SOURCES = {
            "https://www.national-lottery.co.uk/results/euromillions/draw-history/csv",
            "https://raw.githubusercontent.com/daowa89/lottery-archive/main/eu/euromillions/results.csv",
    };

    private final Context context;

    public ResultsStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<Draw> load() throws IOException {
        List<Draw> bundled;
        try (InputStreamReader r = new InputStreamReader(context.getAssets().open(BUNDLED), StandardCharsets.UTF_8)) {
            bundled = DrawParser.parse(r);
        }
        File saved = new File(context.getFilesDir(), SAVED);
        if (saved.exists()) {
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(saved), StandardCharsets.UTF_8)) {
                return DrawParser.appendNewer(bundled, DrawParser.parse(r));
            } catch (IOException e) {
                saved.delete();
            }
        }
        return bundled;
    }

    /**
     * Downloads the latest results and returns the merged history. Throws only if every source
     * failed; the number of new draws is the size difference from {@code current}.
     */
    public List<Draw> update(List<Draw> current) throws IOException {
        List<Draw> merged = current;
        IOException lastError = null;
        boolean anyOk = false;
        for (String source : SOURCES) {
            try {
                merged = DrawParser.appendNewer(merged, download(source));
                anyOk = true;
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (!anyOk) throw lastError != null ? lastError : new IOException("No sources");
        if (merged.size() > current.size()) save(merged);
        return merged;
    }

    private static List<Draw> download(String source) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EuroMillionsPredictor");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode() + " from " + source);
            try (InputStreamReader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return DrawParser.parse(r);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void save(List<Draw> draws) throws IOException {
        File tmp = new File(context.getFilesDir(), SAVED + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            DrawParser.write(draws, w);
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), SAVED))) throw new IOException("Could not save results");
    }
}
