package com.emp.predictor;

import java.util.ArrayList;
import java.util.List;

/** Checks tickets against the draw results and works out prize tiers and fixed prizes. */
public final class TicketChecker {
    /** Prize for a tier that isn't a fixed amount (jackpots, EuroMillions and Powerball tiers). */
    public static final int VARIABLE = -1;

    private TicketChecker() {}

    /** How one line did in one draw (Lotto has two draws a night since June 2026). */
    public static final class Result {
        public final Draw draw;
        public final int mainMatches;
        /** Lucky Stars / Life Ball / Thunderball / Powerball matched; for Lotto, 1 if the bonus ball matched. */
        public final int extraMatches;
        /** e.g. "Match 3 + Thunderball"; null when this isn't a winning combination. */
        public final String tier;
        /** Fixed prize in pence, {@link #VARIABLE}, or 0 when there's no prize. */
        public final int prizePence;
        /** Description for prizes paid other than as cash (Set For Life's monthly prizes). */
        public final String prizeNote;

        Result(Draw draw, int mainMatches, int extraMatches, String tier, int prizePence, String prizeNote) {
            this.draw = draw;
            this.mainMatches = mainMatches;
            this.extraMatches = extraMatches;
            this.tier = tier;
            this.prizePence = prizePence;
            this.prizeNote = prizeNote;
        }

        public boolean isWin() {
            return tier != null;
        }
    }

    /** Results for the ticket's draw night; empty while the draw hasn't happened or isn't stored yet. */
    public static List<Result> check(Ticket t, List<Draw> draws) {
        Game g = t.game();
        List<Result> out = new ArrayList<>();
        for (Draw d : draws) if (d.date.equals(t.drawDate)) out.add(check(g, t, d));
        return out;
    }

    static Result check(Game g, Ticket t, Draw d) {
        int main = count(t.main, d.main);
        int extra = g.extraPicked ? count(t.extra, d.extra) : count(t.main, d.extra);
        if (g == Game.LOTTO) return lotto(d, main, extra);
        if (g == Game.THUNDERBALL) return thunderball(d, main, extra);
        if (g == Game.SET_FOR_LIFE) return setForLife(d, main, extra);
        if (g == Game.EUROMILLIONS) return euroMillions(d, main, extra);
        return powerball(d, main, extra);
    }

    private static Result lotto(Draw d, int main, int bonus) {
        boolean newFormat = d.date.compareTo("2026-06-10") >= 0;
        if (main == 6) return win(d, main, bonus, "Match 6 – jackpot", VARIABLE);
        if (main == 5 && bonus == 1) return win(d, main, bonus, "Match 5 + Bonus Ball", 100_000_000);
        if (main == 5) return win(d, main, bonus, "Match 5", newFormat ? 100_000 : 175_000);
        if (main == 4) return win(d, main, bonus, "Match 4", newFormat ? 5_000 : 14_000);
        if (main == 3) return win(d, main, bonus, "Match 3", newFormat ? 1_000 : 3_000);
        if (main == 2) {
            return newFormat ? win(d, main, bonus, "Match 2", 100)
                    : new Result(d, main, bonus, "Match 2", 200, "Free Lucky Dip");
        }
        return none(d, main, bonus);
    }

    private static Result thunderball(Draw d, int main, int tb) {
        int[][] table = { // main, thunderball, prize pence
                {5, 1, 50_000_000}, {5, 0, 500_000}, {4, 1, 25_000}, {4, 0, 10_000}, {3, 1, 2_000},
                {3, 0, 1_000}, {2, 1, 1_000}, {1, 1, 500}, {0, 1, 300}};
        for (int[] row : table) {
            if (main == row[0] && tb == row[1]) {
                return win(d, main, tb, "Match " + main + (tb == 1 ? " + Thunderball" : ""), row[2]);
            }
        }
        return none(d, main, tb);
    }

    private static Result setForLife(Draw d, int main, int life) {
        if (main == 5 && life == 1) return new Result(d, main, life, "Match 5 + Life Ball", VARIABLE, "£10,000 a month for 30 years");
        if (main == 5) return new Result(d, main, life, "Match 5", VARIABLE, "£10,000 a month for 1 year");
        int[][] table = {{4, 1, 25_000}, {4, 0, 5_000}, {3, 1, 3_000}, {3, 0, 2_000}, {2, 1, 1_000}, {2, 0, 500}};
        for (int[] row : table) {
            if (main == row[0] && life == row[1]) return win(d, main, life, "Match " + main + (life == 1 ? " + Life Ball" : ""), row[2]);
        }
        return none(d, main, life);
    }

    private static Result euroMillions(Draw d, int main, int stars) {
        // Prize amounts depend on ticket sales and winners, so the player enters what they won.
        int[][] winning = {{5, 2}, {5, 1}, {5, 0}, {4, 2}, {4, 1}, {3, 2}, {4, 0}, {2, 2}, {3, 1}, {3, 0}, {1, 2}, {2, 1}, {2, 0}};
        for (int[] w : winning) {
            if (main == w[0] && stars == w[1]) {
                String tier = "Match " + main + (stars > 0 ? " + " + stars + (stars == 1 ? " Lucky Star" : " Lucky Stars") : "");
                return win(d, main, stars, main == 5 && stars == 2 ? tier + " – jackpot" : tier, VARIABLE);
            }
        }
        return none(d, main, stars);
    }

    private static Result powerball(Draw d, int main, int pb) {
        int[][] winning = {{5, 1}, {5, 0}, {4, 1}, {4, 0}, {3, 1}, {3, 0}, {2, 1}, {1, 1}, {0, 1}};
        for (int[] w : winning) {
            if (main == w[0] && pb == w[1]) {
                String tier = "Match " + main + (pb == 1 ? " + Powerball" : "");
                return win(d, main, pb, main == 5 && pb == 1 ? tier + " – jackpot" : tier, VARIABLE);
            }
        }
        return none(d, main, pb);
    }

    private static Result win(Draw d, int main, int extra, String tier, int pence) {
        return new Result(d, main, extra, tier, pence, null);
    }

    private static Result none(Draw d, int main, int extra) {
        return new Result(d, main, extra, null, 0, null);
    }

    private static int count(int[] picked, int[] drawn) {
        int n = 0;
        for (int p : picked) for (int x : drawn) if (p == x) n++;
        return n;
    }

    /** Winnings for a ticket: what the player entered, else the fixed prizes worked out. */
    public static int winningsPence(Ticket t, List<Result> results) {
        if (t.enteredPrizePence >= 0) return t.enteredPrizePence;
        int sum = 0;
        for (Result r : results) if (r.prizePence > 0) sum += r.prizePence;
        return sum;
    }

    /** True when a winning tier has no fixed amount and the player hasn't entered what they won. */
    public static boolean needsAmount(Ticket t, List<Result> results) {
        if (t.enteredPrizePence >= 0) return false;
        for (Result r : results) if (r.isWin() && r.prizePence == VARIABLE) return true;
        return false;
    }

    /** Totals across tickets: [tickets, spent, won, checked tickets, winning tickets] (money in pence). */
    public static int[] totals(List<Ticket> tickets, List<List<Result>> results) {
        int spent = 0, won = 0, checked = 0, winners = 0;
        for (int i = 0; i < tickets.size(); i++) {
            Ticket t = tickets.get(i);
            List<Result> r = results.get(i);
            spent += Math.max(0, t.costPence);
            won += winningsPence(t, r);
            if (!r.isEmpty()) checked++;
            boolean w = t.enteredPrizePence > 0;
            for (Result x : r) w |= x.isWin();
            if (w) winners++;
        }
        return new int[]{tickets.size(), spent, won, checked, winners};
    }
}
