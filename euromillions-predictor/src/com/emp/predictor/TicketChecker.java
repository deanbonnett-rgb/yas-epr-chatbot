package com.emp.predictor;

import java.util.ArrayList;
import java.util.List;

/** Checks tickets against the draw results and works out prize tiers and fixed prizes. */
public final class TicketChecker {
    /** Prize for a tier that isn't a fixed amount (jackpots, EuroMillions and Powerball tiers). */
    public static final int VARIABLE = Prizes.VARIABLE;

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
        Prizes.Tier tier = Prizes.tierFor(g, d.date, main, extra);
        if (tier == null) return new Result(d, main, extra, null, 0, null);
        // Notes describe how a prize is paid (Free Lucky Dip, monthly prizes); a jackpot's note isn't needed on a ticket.
        boolean jackpot = tier.prizePence == VARIABLE && (tier.note == null || !tier.note.contains("a month"));
        return new Result(d, main, extra, tier.name, tier.prizePence, jackpot ? null : tier.note);
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
