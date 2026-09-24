package com.emp.predictor;

import java.util.Arrays;
import java.util.List;

/** Per-number statistics over every draw in a game's history. */
public final class Stats {
    /** How many of the latest draws count as "recent form". */
    public static final int RECENT_WINDOW = 50;

    /**
     * Counts for one set of balls (main numbers or extras). Pools have changed size over the years,
     * so each number is compared with how often chance alone would have drawn it in the draws where
     * it existed.
     */
    public static final class Balls {
        /** Numbers drawn per draw from this set, and the size of today's pool. */
        public final int picks;
        public final int pool;
        /** Index 1..max pool ever: times drawn, drawn in the last RECENT_WINDOW draws, draws since last seen. */
        public final int[] count;
        public final int[] recent;
        public final int[] gap;
        /** Draws in which the number could have been drawn, and the appearances a fair draw would give. */
        public final int[] eligible;
        public final double[] expected;

        Balls(int picks, int pool, int maxPool, int totalDraws) {
            this.picks = picks;
            this.pool = pool;
            count = new int[maxPool + 1];
            recent = new int[maxPool + 1];
            gap = new int[maxPool + 1];
            eligible = new int[maxPool + 1];
            expected = new double[maxPool + 1];
            Arrays.fill(gap, totalDraws);
        }

        void record(int[] drawn, int drawPool, boolean isRecent, int sinceThis) {
            for (int n = 1; n <= drawPool; n++) {
                eligible[n]++;
                expected[n] += (double) drawn.length / drawPool;
            }
            for (int n : drawn) {
                count[n]++;
                if (isRecent) recent[n]++;
                gap[n] = Math.min(gap[n], sinceThis);
            }
        }

        /** Actual / expected appearances: 1.0 means exactly as often as chance predicts. */
        public double index(int n) {
            return expected[n] == 0 ? 1 : count[n] / expected[n];
        }

        /**
         * Historical chance (0..1) that the number appears in a draw with today's pool: its index
         * scaled so the whole pool adds up to {@code picks} numbers per draw.
         */
        public double probability(int n) {
            double total = 0;
            for (int i = 1; i <= pool; i++) total += index(i);
            return total == 0 ? (double) picks / pool : picks * index(n) / total;
        }

        /** Chance a fair draw gives every number. */
        public double fairProbability() {
            return (double) picks / pool;
        }
    }

    public final Game game;
    public final int totalDraws;
    public final String firstDate;
    public final String lastDate;
    public final Balls main;
    public final Balls extra;
    /** Sorted main-number totals of draws under today's rules, for pattern filtering. */
    public final int[] mainSums;

    public Stats(Game game, List<Draw> draws) {
        this.game = game;
        totalDraws = draws.size();
        firstDate = draws.isEmpty() ? "" : draws.get(0).date;
        lastDate = draws.isEmpty() ? "" : draws.get(totalDraws - 1).date;
        main = new Balls(game.mainCount, game.currentMainPool(), game.maxMainPool(), totalDraws);
        extra = new Balls(game.extraCount, game.currentExtraPool(), game.maxExtraPool(), totalDraws);

        int[] sums = new int[totalDraws];
        int nSums = 0;
        for (int i = 0; i < totalDraws; i++) {
            Draw d = draws.get(i);
            boolean isRecent = i >= totalDraws - RECENT_WINDOW;
            int sinceThis = totalDraws - 1 - i;
            main.record(d.main, game.mainPool(d.date), isRecent, sinceThis);
            extra.record(d.extra, game.extraPool(d.date), isRecent, sinceThis);
            if (game.isCurrentEra(d.date)) {
                int sum = 0;
                for (int n : d.main) sum += n;
                sums[nSums++] = sum;
            }
        }
        mainSums = Arrays.copyOf(sums, nSums);
        Arrays.sort(mainSums);
    }

    /** Percentile (0..1) of main-number totals under today's rules. */
    public int sumPercentile(double p) {
        if (mainSums.length == 0) {
            int mid = game.mainCount * (game.currentMainPool() + 1) / 2;
            return p < 0.5 ? mid / 2 : mid * 3 / 2;
        }
        int idx = (int) Math.round(p * (mainSums.length - 1));
        return mainSums[Math.max(0, Math.min(mainSums.length - 1, idx))];
    }
}
