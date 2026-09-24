package com.emp.predictor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads results in the formats the app meets: its own CSVs, the National Lottery downloads
 * (DrawDate,Ball 1..,Lucky Star 1../Bonus Ball), other CSVs found by their header (n1.., ball1..,
 * white_balls "1|2|3|4|5", red_ball / powerball ...), and the merseyworld archive pages
 * (No., Day,DD,MMM,YYYY, N1.., then the extra balls).
 */
public final class DrawParser {
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final Pattern EXTRA_COL = Pattern.compile(
            "(lucky )?stars?( ?\\d+)?|s\\d+|l\\d+|e\\d+|bonus( ball)?|bn|powerball|pb|red_?ball");
    private static final Pattern MAIN_COL = Pattern.compile(
            "(ball|n|wb|number) ?_?\\d+|white_?balls|winning numbers");
    private static final Pattern MERSEY_HEADER = Pattern.compile("^\\s*No\\.,\\s*Day,\\s*DD,\\s*MMM,\\s*YYYY,");
    private static final Pattern MERSEY_ROW = Pattern.compile("^\\s*\\d+,\\s*[A-Za-z]{3},\\s*\\d+,\\s*[A-Za-z]{3},\\s*\\d{4},");

    private DrawParser() {}

    public static List<Draw> parse(Reader in, Game game) throws IOException {
        BufferedReader br = new BufferedReader(in);
        String line;
        while ((line = br.readLine()) != null) {
            String clean = stripBom(line).trim();
            if (clean.isEmpty() || clean.startsWith("<") && !clean.contains(",")) continue;
            if (MERSEY_HEADER.matcher(clean).find()) return parseMersey(br, game);
            boolean looksLikeHeader = clean.split(",").length >= 3 && !clean.contains("<");
            if (looksLikeHeader && clean.toLowerCase(Locale.ROOT).contains("date")) return parseCsv(clean, br, game);
        }
        throw new IOException("No results found for " + game.name);
    }

    private static List<Draw> parseCsv(String header, BufferedReader br, Game game) throws IOException {
        String[] cols = split(header);
        int dateCol = -1;
        List<Integer> mainCols = new ArrayList<>();
        List<Integer> extraCols = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().toLowerCase(Locale.ROOT);
            if (dateCol < 0 && c.contains("date")) dateCol = i;
            else if (EXTRA_COL.matcher(c).matches()) extraCols.add(i);
            else if (MAIN_COL.matcher(c).matches()) mainCols.add(i);
        }
        if (dateCol < 0 || mainCols.isEmpty()) throw new IOException("Unrecognised results format: " + header);

        List<Draw> out = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] f = split(line);
            try {
                List<Integer> main = numbers(f, mainCols);
                List<Integer> extra = numbers(f, extraCols);
                if (extra.isEmpty() && main.size() == game.mainCount + game.extraCount) {
                    // One combined column, e.g. "Winning Numbers" = "08 12 21 51 62 23".
                    extra = new ArrayList<>(main.subList(game.mainCount, main.size()));
                    main = new ArrayList<>(main.subList(0, game.mainCount));
                }
                add(out, game, normaliseDate(f[dateCol], game.usDates), main, extra);
            } catch (RuntimeException ignored) {
                // Skip malformed rows rather than failing the whole file.
            }
        }
        return out;
    }

    private static List<Draw> parseMersey(BufferedReader br, Game game) throws IOException {
        List<Draw> out = new ArrayList<>();
        int balls = game.mainCount + game.extraCount;
        String line;
        while ((line = br.readLine()) != null) {
            if (!MERSEY_ROW.matcher(line).find()) continue;
            String[] f = line.split(",");
            try {
                String date = normaliseDate(f[2].trim() + "-" + f[3].trim() + "-" + f[4].trim(), false);
                List<Integer> main = new ArrayList<>();
                List<Integer> extra = new ArrayList<>();
                for (int i = 0; i < balls; i++) {
                    int n = Integer.parseInt(f[5 + i].trim());
                    if (i < game.mainCount) main.add(n); else extra.add(n);
                }
                add(out, game, date, main, extra);
            } catch (RuntimeException ignored) {
                // Not a results row.
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void add(List<Draw> out, Game game, String date, List<Integer> main, List<Integer> extra) {
        if (game.snapToDrawDay) date = DrawSchedule.snapToDrawDay(game, date);
        Draw d = new Draw(date, toArray(main), toArray(extra));
        if (d.isValid(game)) out.add(d);
    }

    private static List<Integer> numbers(String[] fields, List<Integer> cols) {
        List<Integer> out = new ArrayList<>();
        for (int c : cols) {
            for (String part : fields[c].trim().split("[|\\s]+")) {
                if (!part.isEmpty()) out.add(Integer.parseInt(part));
            }
        }
        return out;
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** Accepts yyyy-MM-dd, dd-MMM-yyyy, and numeric dates (d/M/y, or M/d/y when {@code monthFirst}). */
    static String normaliseDate(String raw, boolean monthFirst) {
        String s = raw.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return s.substring(0, 10);
        String[] p = s.split("[-/ ]+");
        if (p.length >= 3) {
            int year = Integer.parseInt(p[2].length() > 4 ? p[2].substring(0, 4) : p[2]);
            if (year < 100) year += 2000;
            int day, month = -1;
            if (p[1].matches("\\d+")) {
                day = Integer.parseInt(monthFirst ? p[1] : p[0]);
                month = Integer.parseInt(monthFirst ? p[0] : p[1]);
            } else {
                day = Integer.parseInt(p[0]);
                String m = p[1].toLowerCase(Locale.ROOT);
                for (int i = 0; i < 12; i++) if (m.startsWith(MONTHS[i])) month = i + 1;
            }
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
            }
        }
        throw new IllegalArgumentException("Bad date: " + raw);
    }

    private static String stripBom(String s) {
        return s.replace("﻿", "");
    }

    private static String[] split(String line) {
        return stripBom(line).replace("\"", "").split(",", -1);
    }

    /**
     * Merges downloaded draws into {@code base}. Stored draws are never replaced. A download that
     * repeats a neighbouring draw's numbers under another date is ignored.
     */
    public static List<Draw> merge(List<Draw> base, List<Draw> incoming, Game.Mode mode) {
        TreeMap<String, Draw> byDate = new TreeMap<>();
        for (Draw d : base) byDate.put(d.date, d);
        String last = byDate.isEmpty() ? "" : byDate.lastKey();
        List<Draw> sorted = new ArrayList<>(incoming);
        Collections.sort(sorted);
        for (Draw d : sorted) {
            if (byDate.containsKey(d.date)) continue;
            if (mode == Game.Mode.APPEND_NEWER && d.date.compareTo(last) <= 0) continue;
            Map.Entry<String, Draw> before = byDate.lowerEntry(d.date);
            Map.Entry<String, Draw> after = byDate.higherEntry(d.date);
            if (before != null && before.getValue().sameNumbers(d)) continue;
            if (after != null && after.getValue().sameNumbers(d)) continue;
            byDate.put(d.date, d);
        }
        return new ArrayList<>(byDate.values());
    }

    public static void write(List<Draw> draws, Game game, Writer w) throws IOException {
        StringBuilder header = new StringBuilder("date");
        for (int i = 1; i <= game.mainCount; i++) header.append(",n").append(i);
        for (int i = 1; i <= game.extraCount; i++) header.append(",e").append(i);
        w.write(header.append('\n').toString());
        for (Draw d : draws) {
            StringBuilder sb = new StringBuilder(d.date);
            for (int n : d.main) sb.append(',').append(n);
            for (int e : d.extra) sb.append(',').append(e);
            w.write(sb.append('\n').toString());
        }
        w.flush();
    }
}
