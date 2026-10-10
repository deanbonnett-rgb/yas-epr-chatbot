package com.emp.predictor;

import java.util.ArrayList;
import java.util.List;

/**
 * Prize tiers for each game, best first, with exact odds worked out from the game's rules.
 * Ticket checking and the Prizes tab both use these tables.
 */
public final class Prizes {
    /** Prize with no fixed amount: jackpots, and every EuroMillions and Powerball tier. */
    public static final int VARIABLE = -1;
    /** Tier where the extra ball doesn't matter (Lotto Match 2-4). */
    public static final int ANY = -1;

    public static final class Tier {
        public final String name;
        public final int main;
        /** Extra balls needed (Lotto: 1 = bonus ball matched), or {@link #ANY}. */
        public final int extra;
        /** Prize in pence, or {@link #VARIABLE}. */
        public final int prizePence;
        /** How a non-cash or variable prize is paid, e.g. "£10,000 a month for 30 years"; null if cash. */
        public final String note;

        Tier(String name, int main, int extra, int prizePence, String note) {
            this.name = name;
            this.main = main;
            this.extra = extra;
            this.prizePence = prizePence;
            this.note = note;
        }

        public boolean matches(int mainMatches, int extraMatches) {
            return main == mainMatches && (extra == ANY || extra == extraMatches);
        }
    }

    private Prizes() {}

    /** Tiers in force for a draw on {@code isoDate}, best first. */
    public static List<Tier> tiers(Game g, String isoDate) {
        List<Tier> t = new ArrayList<>();
        if (g == Game.LOTTO) {
            boolean twoRounds = isoDate.compareTo("2026-06-10") >= 0;
            t.add(new Tier("Match 6 – jackpot", 6, ANY, VARIABLE, "Jackpot, shared between winners"));
            t.add(new Tier("Match 5 + Bonus Ball", 5, 1, 100_000_000, null));
            t.add(new Tier("Match 5", 5, 0, twoRounds ? 100_000 : 175_000, null));
            t.add(new Tier("Match 4", 4, ANY, twoRounds ? 5_000 : 14_000, null));
            t.add(new Tier("Match 3", 3, ANY, twoRounds ? 1_000 : 3_000, null));
            t.add(twoRounds ? new Tier("Match 2", 2, ANY, 100, null) : new Tier("Match 2", 2, ANY, 200, "Free Lucky Dip"));
        } else if (g == Game.THUNDERBALL) {
            int[][] rows = {{5, 1, 50_000_000}, {5, 0, 500_000}, {4, 1, 25_000}, {4, 0, 10_000}, {3, 1, 2_000},
                    {3, 0, 1_000}, {2, 1, 1_000}, {1, 1, 500}, {0, 1, 300}};
            for (int[] r : rows) t.add(new Tier(name(r[0], r[1], "Thunderball"), r[0], r[1], r[2], null));
        } else if (g == Game.SET_FOR_LIFE) {
            t.add(new Tier("Match 5 + Life Ball", 5, 1, VARIABLE, "£10,000 a month for 30 years"));
            t.add(new Tier("Match 5", 5, 0, VARIABLE, "£10,000 a month for 1 year"));
            int[][] rows = {{4, 1, 25_000}, {4, 0, 5_000}, {3, 1, 3_000}, {3, 0, 2_000}, {2, 1, 1_000}, {2, 0, 500}};
            for (int[] r : rows) t.add(new Tier(name(r[0], r[1], "Life Ball"), r[0], r[1], r[2], null));
        } else if (g == Game.EUROMILLIONS) {
            // Amounts depend on ticket sales and the number of winners in each draw.
            int[][] rows = {{5, 2}, {5, 1}, {5, 0}, {4, 2}, {4, 1}, {3, 2}, {4, 0}, {2, 2}, {3, 1}, {3, 0}, {1, 2}, {2, 1}, {2, 0}};
            for (int[] r : rows) {
                String n = "Match " + r[0] + (r[1] > 0 ? " + " + r[1] + (r[1] == 1 ? " Lucky Star" : " Lucky Stars") : "");
                t.add(new Tier(r[0] == 5 && r[1] == 2 ? n + " – jackpot" : n, r[0], r[1], VARIABLE, null));
            }
        } else {
            int[][] rows = {{5, 1}, {5, 0}, {4, 1}, {4, 0}, {3, 1}, {3, 0}, {2, 1}, {1, 1}, {0, 1}};
            for (int[] r : rows) {
                String n = name(r[0], r[1], "Powerball");
                t.add(new Tier(r[0] == 5 && r[1] == 1 ? n + " – jackpot" : n, r[0], r[1], VARIABLE, null));
            }
        }
        return t;
    }

    private static String name(int main, int extra, String extraName) {
        return "Match " + main + (extra == 1 ? " + " + extraName : "");
    }

    /** The tier a line's matches win, or null. */
    public static Tier tierFor(Game g, String isoDate, int mainMatches, int extraMatches) {
        for (Tier t : tiers(g, isoDate)) if (t.matches(mainMatches, extraMatches)) return t;
        return null;
    }

    /** Exact chance (0..1) that one line wins this tier in one draw under today's rules. */
    public static double probability(Game g, Tier t) {
        int n = g.currentMainPool(), k = g.mainCount;
        double main = c(k, t.main) * c(n - k, k - t.main) / c(n, k);
        if (g == Game.LOTTO) {
            // The bonus ball comes from the 53 balls left after the six main numbers.
            int left = n - k, mine = k - t.main;
            if (t.extra == 1) return main * mine / left;
            if (t.extra == 0) return main * (left - mine) / left;
            return main;
        }
        int en = g.currentExtraPool(), ek = g.extraCount;
        return main * c(ek, t.extra) * c(en - ek, ek - t.extra) / c(en, ek);
    }

    /** Chance of winning any prize with one line in one draw. */
    public static double anyPrize(Game g, String isoDate) {
        double p = 0;
        for (Tier t : tiers(g, isoDate)) p += probability(g, t);
        return p;
    }

    /** "1 in 45,057,474", rounded up like the official tables; one decimal place for short odds. */
    public static String oddsText(double p) {
        double n = 1 / p;
        if (n < 20) return String.format(java.util.Locale.UK, "1 in %.1f", Math.ceil(n * 10 - 1e-6) / 10);
        return String.format(java.util.Locale.UK, "1 in %,d", (long) Math.ceil(n - 1e-6));
    }

    private static double c(int n, int r) {
        if (r < 0 || r > n) return 0;
        double v = 1;
        for (int i = 1; i <= r; i++) v = v * (n - r + i) / i;
        return v;
    }
}
