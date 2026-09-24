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
 * Loads a game's draw history: the copy bundled in assets, plus draws downloaded since and saved
 * to app storage.
 */
public final class ResultsStore {
    private final Context context;
    public final Game game;

    public ResultsStore(Context context, Game game) {
        this.context = context.getApplicationContext();
        this.game = game;
    }

    private File savedFile() {
        return new File(context.getFilesDir(), game.id + "_updated.csv");
    }

    public List<Draw> load() throws IOException {
        List<Draw> bundled;
        try (InputStreamReader r = new InputStreamReader(context.getAssets().open(game.id + ".csv"), StandardCharsets.UTF_8)) {
            bundled = DrawParser.parse(r, game);
        }
        File saved = savedFile();
        if (saved.exists()) {
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(saved), StandardCharsets.UTF_8)) {
                return DrawParser.merge(bundled, DrawParser.parse(r, game), Game.Mode.FILL);
            } catch (IOException e) {
                saved.delete();
            }
        }
        return bundled;
    }

    /** Whether opening the game should go online: a draw is due, or there is a hole to fill. */
    public boolean updateWanted(List<Draw> draws, long nowMillis) {
        if (draws.isEmpty()) return true;
        return DrawSchedule.updateDue(game, draws.get(draws.size() - 1).date, nowMillis) || DrawSchedule.drawsMissing(game, draws, nowMillis);
    }

    /**
     * Downloads the latest results and returns the merged history. Complete archives (FILL sources,
     * listed last) are only fetched while draws are still missing. Throws only if every source tried failed.
     */
    public List<Draw> update(List<Draw> current) throws IOException {
        List<Draw> merged = current;
        IOException lastError = null;
        boolean anyOk = false;
        for (Game.Source source : game.sources) {
            if (source.mode == Game.Mode.FILL && !DrawSchedule.drawsMissing(game, merged, System.currentTimeMillis())) continue;
            try {
                merged = DrawParser.merge(merged, download(source.url), source.mode);
                anyOk = true;
            } catch (IOException | RuntimeException e) {
                lastError = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        if (!anyOk) throw lastError != null ? lastError : new IOException("No sources");
        if (merged.size() > current.size()) save(merged);
        return merged;
    }

    private List<Draw> download(String source) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LotteryPredictor");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode() + " from " + source);
            try (InputStreamReader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.ISO_8859_1)) {
                return DrawParser.parse(r, game);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void save(List<Draw> draws) throws IOException {
        File tmp = new File(context.getFilesDir(), game.id + "_updated.csv.tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            DrawParser.write(draws, game, w);
        }
        if (!tmp.renameTo(savedFile())) throw new IOException("Could not save results");
    }
}
