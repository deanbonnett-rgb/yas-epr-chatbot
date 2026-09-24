package com.emp.predictor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Reads EuroMillions results CSVs. Columns are located from the header, so both the bundled
 * format (date,n1..n5,s1,s2) and the National Lottery download (DrawDate,Ball 1..5,Lucky Star 1,2,...)
 * are understood.
 */
public final class DrawParser {
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"};

    private DrawParser() {}

    public static List<Draw> parse(Reader in) throws IOException {
        BufferedReader br = new BufferedReader(in);
        String header = br.readLine();
        if (header == null) return new ArrayList<>();
        String[] cols = splitCsv(header);
        int dateCol = -1;
        List<Integer> mainCols = new ArrayList<>();
        List<Integer> starCols = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().toLowerCase(Locale.ROOT).replace("﻿", "");
            if (dateCol < 0 && c.contains("date")) dateCol = i;
            else if (c.contains("star") || c.matches("s\\d")) starCols.add(i);
            else if (c.startsWith("ball") || c.matches("n\\d")) mainCols.add(i);
        }
        if (dateCol < 0 || mainCols.size() != 5 || starCols.size() != 2) {
            throw new IOException("Unrecognised results format: " + header);
        }

        List<Draw> out = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] f = splitCsv(line);
            try {
                String date = normaliseDate(f[dateCol]);
                int[] main = new int[5];
                int[] stars = new int[2];
                for (int i = 0; i < 5; i++) main[i] = Integer.parseInt(f[mainCols.get(i)].trim());
                for (int i = 0; i < 2; i++) stars[i] = Integer.parseInt(f[starCols.get(i)].trim());
                Draw d = new Draw(date, main, stars);
                if (d.isValid()) out.add(d);
            } catch (RuntimeException ignored) {
                // Skip malformed rows rather than failing the whole file.
            }
        }
        return out;
    }

    /** Accepts yyyy-MM-dd, dd-MMM-yyyy and dd/MM/yyyy. */
    static String normaliseDate(String raw) {
        String s = raw.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;
        String[] p = s.split("[-/ ]");
        if (p.length == 3) {
            int day = Integer.parseInt(p[0]);
            int year = Integer.parseInt(p[2]);
            if (year < 100) year += 2000;
            int month;
            if (p[1].matches("\\d+")) {
                month = Integer.parseInt(p[1]);
            } else {
                month = -1;
                String m = p[1].toLowerCase(Locale.ROOT);
                for (int i = 0; i < 12; i++) if (m.startsWith(MONTHS[i])) month = i + 1;
            }
            if (month >= 1) return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
        }
        throw new IllegalArgumentException("Bad date: " + raw);
    }

    private static String[] splitCsv(String line) {
        return line.replace("\"", "").split(",", -1);
    }

    /**
     * Adds draws from {@code extra} that are newer than everything in {@code base}. Older rows are
     * never replaced, because the bundled history has been cross-checked against two sources.
     */
    public static List<Draw> appendNewer(List<Draw> base, List<Draw> extra) {
        String last = base.isEmpty() ? "" : base.get(base.size() - 1).date;
        TreeMap<String, Draw> byDate = new TreeMap<>();
        for (Draw d : extra) if (d.date.compareTo(last) > 0) byDate.put(d.date, d);
        List<Draw> out = new ArrayList<>(base);
        for (Draw d : byDate.values()) {
            // Some sources repeat a draw under a wrong date; drop exact repeats of the previous draw.
            Draw prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && Arrays.equals(prev.main, d.main) && Arrays.equals(prev.stars, d.stars)) continue;
            out.add(d);
        }
        return out;
    }

    public static void write(List<Draw> draws, Writer w) throws IOException {
        w.write("date,n1,n2,n3,n4,n5,s1,s2\n");
        for (Draw d : draws) {
            StringBuilder sb = new StringBuilder(d.date);
            for (int n : d.main) sb.append(',').append(n);
            for (int s : d.stars) sb.append(',').append(s);
            w.write(sb.append('\n').toString());
        }
        w.flush();
    }
}
