package com.emp.predictor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A line the player actually bought: which game and draw, the numbers, how it was chosen, cost and winnings. */
public final class Ticket implements Comparable<Ticket> {
    public static final String GENERATED = "Generated";
    public static final String LUCKY_DIP = "Lucky Dip";
    public static final String OWN = "My numbers";

    public final long id;
    public final String gameId;
    public final String drawDate;
    public final int[] main;
    /** Lucky Stars / Life Ball / Thunderball / Powerball; empty for Lotto. */
    public final int[] extra;
    public final String source;
    public final int costPence;
    /** Winnings entered by the player, in pence, or -1 to use the prizes worked out from the results. */
    public final int enteredPrizePence;

    public Ticket(long id, String gameId, String drawDate, int[] main, int[] extra, String source, int costPence, int enteredPrizePence) {
        this.id = id;
        this.gameId = gameId;
        this.drawDate = drawDate;
        this.main = main.clone();
        this.extra = extra.clone();
        Arrays.sort(this.main);
        Arrays.sort(this.extra);
        this.source = source;
        this.costPence = costPence;
        this.enteredPrizePence = enteredPrizePence;
    }

    public Ticket withEnteredPrize(int pence) {
        return new Ticket(id, gameId, drawDate, main, extra, source, costPence, pence);
    }

    public Game game() {
        return Game.byId(gameId);
    }

    /** Same game, draw and numbers. */
    public boolean sameLine(String gameId, String drawDate, int[] main, int[] extra) {
        int[] m = main.clone(), e = extra.clone();
        Arrays.sort(m);
        Arrays.sort(e);
        return this.gameId.equals(gameId) && this.drawDate.equals(drawDate) && Arrays.equals(this.main, m) && Arrays.equals(this.extra, e);
    }

    public boolean isValid() {
        Game g = game();
        if (g == null || !drawDate.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        int extras = g.extraPicked ? g.extraCount : 0;
        return main.length == g.mainCount && extra.length == extras
                && distinctInRange(main, g.mainPool(drawDate)) && distinctInRange(extra, g.extraPool(drawDate));
    }

    private static boolean distinctInRange(int[] sorted, int pool) {
        for (int i = 0; i < sorted.length; i++) {
            if (sorted[i] < 1 || sorted[i] > pool || i > 0 && sorted[i] == sorted[i - 1]) return false;
        }
        return true;
    }

    /** One line per ticket: id|game|date|main|extra|source|cost|prize. */
    public String encode() {
        return id + "|" + gameId + "|" + drawDate + "|" + join(main) + "|" + join(extra) + "|" + source.replace("|", "/")
                + "|" + costPence + "|" + enteredPrizePence;
    }

    public static Ticket decode(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length != 8) throw new IllegalArgumentException("Bad ticket: " + line);
        return new Ticket(Long.parseLong(f[0]), f[1], f[2], split(f[3]), split(f[4]), f[5],
                Integer.parseInt(f[6]), Integer.parseInt(f[7]));
    }

    public static List<Ticket> decodeAll(String text) {
        List<Ticket> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                Ticket t = decode(line.trim());
                if (t.isValid()) out.add(t);
            } catch (RuntimeException ignored) {
                // Skip a damaged line rather than losing the whole log.
            }
        }
        return out;
    }

    public static String encodeAll(List<Ticket> tickets) {
        StringBuilder sb = new StringBuilder();
        for (Ticket t : tickets) sb.append(t.encode()).append('\n');
        return sb.toString();
    }

    private static String join(int[] nums) {
        StringBuilder sb = new StringBuilder();
        for (int n : nums) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        return sb.toString();
    }

    private static int[] split(String s) {
        if (s.isEmpty()) return new int[0];
        String[] p = s.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
        return out;
    }

    /** "£1,234.50" */
    public static String money(int pence) {
        String sign = pence < 0 ? "-" : "";
        int p = Math.abs(pence);
        return String.format(Locale.UK, "%s£%,d.%02d", sign, p / 100, p % 100);
    }

    /** Parses "2.50", "£2.50" or "2" into pence; -1 if blank or not a number. */
    public static int parsePence(String s) {
        String t = s.replace("£", "").replace(",", "").trim();
        if (t.isEmpty()) return -1;
        try {
            return (int) Math.round(Double.parseDouble(t) * 100);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Newest draw first, then newest added. */
    @Override
    public int compareTo(Ticket o) {
        int c = o.drawDate.compareTo(drawDate);
        return c != 0 ? c : Long.compare(o.id, id);
    }
}
