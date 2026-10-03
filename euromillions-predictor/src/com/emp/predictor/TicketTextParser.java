package com.emp.predictor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a ticket from text: OCR of a screenshot of The National Lottery app's "Your ticket" screen,
 * or text pasted from Google Lens. It finds the game, the lines ("Line 1: 16 20 30 36 49 03 04"),
 * Lucky Dip marks, the draw dates and the cost per line, and copes with common OCR slips: O for 0,
 * l for 1, and lost spaces ("1620303649" — the app always shows two digits per number).
 */
public final class TicketTextParser {
    public static final class Line {
        public final int number;
        public final int[] main;
        public final int[] extra;
        public final boolean luckyDip;
        /** What was read, for lines that couldn't be turned into valid numbers; null when valid. */
        public final String problem;

        Line(int number, int[] main, int[] extra, boolean luckyDip, String problem) {
            this.number = number;
            this.main = main;
            this.extra = extra;
            this.luckyDip = luckyDip;
            this.problem = problem;
        }
    }

    public static final class Result {
        public Game game;
        /** True when the game came from the text, not just the screen it was imported from. */
        public boolean gameFromText;
        public final List<Line> lines = new ArrayList<>();
        public final List<String> drawDates = new ArrayList<>();
        /** Price of one line in one draw, or -1 if not found. */
        public int costPerLinePence = -1;
        /** Something the player should know, e.g. an unsupported game; null if none. */
        public String warning;

        public int validLines() {
            int n = 0;
            for (Line l : lines) if (l.problem == null) n++;
            return n;
        }
    }

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final Pattern WORD_DATE = Pattern.compile(
            "\\b([0-3Oo]?[0-9Oo])(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?,?\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM_DATE = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b");
    private static final Pattern LINE_LABEL = Pattern.compile("^\\s*(?:line|ln|[|l1I]ine)\\s*([0-9OoIl|]{1,2})?\\s*[:;.,]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LUCKY_DIP = Pattern.compile("luck[yv]\\s*d[i1l!|]p", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST = Pattern.compile("(\\d+)\\s*lines?\\s*[x×*]\\s*£?\\s*(\\d+[.,]\\d{2})", Pattern.CASE_INSENSITIVE);

    private TicketTextParser() {}

    public static Result parse(String text, Game fallback) {
        Result r = new Result();
        String lower = text.toLowerCase(Locale.ROOT);
        r.game = detectGame(lower);
        r.gameFromText = r.game != null;
        if (r.game == null) r.game = fallback;
        if (lower.contains("hotpicks") || lower.contains("hot picks")) {
            r.warning = "This looks like a HotPicks ticket, which the app doesn't track – please check the numbers.";
        }
        Game g = r.game;
        int extras = g.extraPicked ? g.extraCount : 0;

        String[] rows = text.split("\\r?\\n");
        int fallbackNumber = 0;
        for (String row : rows) {
            Matcher m = LINE_LABEL.matcher(row);
            if (!m.matches()) continue;
            String rest = m.group(2);
            if (!rest.matches(".*[0-9].*")) continue;
            fallbackNumber++;
            int number = m.group(1) != null ? toInt(fixDigits(m.group(1)), fallbackNumber) : fallbackNumber;
            r.lines.add(readLine(number, rest, g, extras));
        }
        if (r.lines.isEmpty()) {
            // Pasted text without "Line" labels: any row holding exactly one line's worth of numbers.
            for (String row : rows) {
                if (WORD_DATE.matcher(row).find() || NUM_DATE.matcher(row).find() || row.contains("£")) continue;
                Line l = readLine(r.lines.size() + 1, row, g, extras);
                if (l.problem == null) r.lines.add(l);
            }
        }

        readDrawDates(rows, g, r.drawDates);
        Matcher cost = COST.matcher(text);
        if (cost.find()) r.costPerLinePence = Ticket.parsePence(cost.group(2).replace(',', '.'));
        return r;
    }

    static Game detectGame(String lower) {
        if (lower.contains("euromillion") || lower.contains("euro millions") || lower.contains("lucky star")) return Game.EUROMILLIONS;
        if (lower.contains("thunderball")) return Game.THUNDERBALL;
        if (lower.contains("set for life") || lower.contains("setforlife") || lower.contains("life ball")) return Game.SET_FOR_LIFE;
        if (lower.contains("powerball")) return Game.POWERBALL;
        if (lower.matches("(?s).*\\blotto\\b.*") || lower.contains("bonus ball")) return Game.LOTTO;
        return null;
    }

    private static Line readLine(int number, String rest, Game g, int extras) {
        boolean dip = LUCKY_DIP.matcher(rest).find();
        String cleaned = LUCKY_DIP.matcher(rest).replaceAll(" ");
        List<Integer> nums = new ArrayList<>();
        for (String token : cleaned.split("[^0-9A-Za-z|]+")) {
            String t = fixDigits(token);
            if (!t.matches("\\d+")) continue;
            if (t.length() <= 2) {
                nums.add(Integer.parseInt(t));
            } else if (t.length() % 2 == 0) {
                for (int i = 0; i < t.length(); i += 2) nums.add(Integer.parseInt(t.substring(i, i + 2)));
            } else {
                return new Line(number, new int[0], new int[0], dip, rest.trim());
            }
        }
        int need = g.mainCount + extras;
        if (nums.size() != need) return new Line(number, new int[0], new int[0], dip, rest.trim());
        int[] main = new int[g.mainCount], extra = new int[extras];
        for (int i = 0; i < g.mainCount; i++) main[i] = nums.get(i);
        for (int i = 0; i < extras; i++) extra[i] = nums.get(g.mainCount + i);
        Arrays.sort(main);
        Arrays.sort(extra);
        if (!valid(main, g.currentMainPool()) || !valid(extra, g.currentExtraPool())) {
            return new Line(number, new int[0], new int[0], dip, rest.trim());
        }
        return new Line(number, main, extra, dip, null);
    }

    /** Fixes letters OCR puts in place of digits, but only in tokens that are mostly digits. */
    static String fixDigits(String token) {
        int digits = 0;
        for (char c : token.toCharArray()) if (Character.isDigit(c)) digits++;
        if (token.isEmpty() || digits * 2 < token.length()) return token;
        return token.replace('O', '0').replace('o', '0').replace('D', '0').replace('Q', '0')
                .replace('l', '1').replace('I', '1').replace('|', '1').replace('i', '1')
                .replace('S', '5').replace('B', '8').replace('Z', '2');
    }

    private static boolean valid(int[] sorted, int pool) {
        for (int i = 0; i < sorted.length; i++) if (sorted[i] < 1 || sorted[i] > pool || i > 0 && sorted[i] == sorted[i - 1]) return false;
        return true;
    }

    /**
     * Draw dates: the list under "Draw dates", else "Draw date:" lines, else every draw day in the
     * "Draw summary" range. The purchase date is ignored.
     */
    private static void readDrawDates(String[] rows, Game g, List<String> out) {
        boolean inList = false;
        for (String row : rows) {
            String low = row.toLowerCase(Locale.ROOT);
            if (low.contains("purchase")) { inList = false; continue; }
            if (low.matches(".*draw\\s*dates?\\s*:.*")) {
                inList = true;
                addDates(row, out);
                continue;
            }
            if (inList) {
                if (row.trim().isEmpty()) continue;
                if (!addDates(row, out)) inList = false;
            }
        }
        if (out.isEmpty()) {
            for (String row : rows) {
                if (!row.toLowerCase(Locale.ROOT).contains("draw summary")) continue;
                List<String> range = new ArrayList<>();
                addDates(row, range);
                if (range.size() == 2) expandRange(g, range.get(0), range.get(1), out);
                else out.addAll(range);
            }
        }
        if (out.isEmpty()) {
            for (String row : rows) {
                String low = row.toLowerCase(Locale.ROOT);
                if (low.contains("purchase") || low.contains("bought")) continue;
                if (low.contains("draw")) addDates(row, out);
            }
        }
        // Keep draw days only, in order, without repeats.
        List<String> clean = new ArrayList<>();
        for (String d : out) if (!clean.contains(d) && isDrawDay(g, d)) clean.add(d);
        out.clear();
        out.addAll(clean);
    }

    private static boolean addDates(String row, List<String> out) {
        boolean found = false;
        Matcher m = WORD_DATE.matcher(row);
        while (m.find()) {
            int day = toInt(fixDigits(m.group(1)), -1);
            int month = Arrays.asList(MONTHS).indexOf(m.group(2).toLowerCase(Locale.ROOT)) + 1;
            if (day >= 1 && day <= 31) {
                out.add(String.format(Locale.ROOT, "%s-%02d-%02d", m.group(3), month, day));
                found = true;
            }
        }
        Matcher n = NUM_DATE.matcher(row);
        while (n.find()) {
            int day = Integer.parseInt(n.group(1)), month = Integer.parseInt(n.group(2));
            if (day >= 1 && day <= 31 && month >= 1 && month <= 12) {
                out.add(String.format(Locale.ROOT, "%s-%02d-%02d", n.group(3), month, day));
                found = true;
            }
        }
        return found;
    }

    private static void expandRange(Game g, String from, String to, List<String> out) {
        Calendar c = calendar(from);
        for (int i = 0; i < 400 && iso(c).compareTo(to) <= 0; i++) {
            if (g.isDrawDay(c.get(Calendar.DAY_OF_WEEK))) out.add(iso(c));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private static boolean isDrawDay(Game g, String iso) {
        return g.isDrawDay(calendar(iso).get(Calendar.DAY_OF_WEEK));
    }

    private static Calendar calendar(String iso) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.UK);
        c.clear();
        c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10)));
        return c;
    }

    private static String iso(Calendar c) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static int toInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
