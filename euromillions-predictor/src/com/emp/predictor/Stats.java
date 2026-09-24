package com.emp.predictor;

import java.util.Arrays;
import java.util.List;

/** Per-number statistics over every draw in the history. */
public final class Stats {
    public static final int MAIN_MAX = 50;
    public static final int STAR_MAX = 12;
    /** How many of the latest draws count as "recent form". */
    public static final int RECENT_WINDOW = 50;

    public final int totalDraws;
    public final String firstDate;
    public final String lastDate;

    /** Index 1..50: times drawn, times drawn in the last RECENT_WINDOW draws, draws since last seen. */
    public final int[] mainCount = new int[MAIN_MAX + 1];
    public final int[] mainRecent = new int[MAIN_MAX + 1];
    public final int[] mainGap = new int[MAIN_MAX + 1];

    /** Index 1..12. starEligible = draws where that star was in the pool. */
    public final int[] starCount = new int[STAR_MAX + 1];
    public final int[] starRecent = new int[STAR_MAX + 1];
    public final int[] starGap = new int[STAR_MAX + 1];
    public final int[] starEligible = new int[STAR_MAX + 1];
    /** Sum over eligible draws of 2/poolSize: the count a perfectly fair machine would give. */
    public final double[] starExpected = new double[STAR_MAX + 1];

    /** Sorted sums of the five main balls in each draw, for pattern filtering. */
    public final int[] mainSums;

    public Stats(List<Draw> draws) {
        totalDraws = draws.size();
        firstDate = draws.isEmpty() ? "" : draws.get(0).date;
        lastDate = draws.isEmpty() ? "" : draws.get(totalDraws - 1).date;
        Arrays.fill(mainGap, totalDraws);
        Arrays.fill(starGap, totalDraws);
        mainSums = new int[totalDraws];

        for (int i = 0; i < totalDraws; i++) {
            Draw d = draws.get(i);
            boolean recent = i >= totalDraws - RECENT_WINDOW;
            int sinceThis = totalDraws - 1 - i;
            int sum = 0;
            for (int n : d.main) {
                mainCount[n]++;
                if (recent) mainRecent[n]++;
                mainGap[n] = sinceThis;
                sum += n;
            }
            mainSums[i] = sum;
            int pool = d.starPoolSize();
            for (int s = 1; s <= pool; s++) {
                starEligible[s]++;
                starExpected[s] += 2.0 / pool;
            }
            for (int s : d.stars) {
                starCount[s]++;
                if (recent) starRecent[s]++;
                starGap[s] = sinceThis;
            }
        }
        Arrays.sort(mainSums);
    }

    /** Historical chance (0..1) that a main number appears in a draw. Fair value is 5/50 = 10%. */
    public double mainProbability(int n) {
        return totalDraws == 0 ? 0.1 : (double) mainCount[n] / totalDraws;
    }

    /**
     * Historical chance (0..1) that a Lucky Star appears in a draw with today's 12-star pool.
     * Each star's hit rate is scaled against what was possible in its era, then renormalised so
     * the twelve values add up to 2 stars per draw.
     */
    public double starProbability(int s) {
        double total = 0;
        for (int i = 1; i <= STAR_MAX; i++) total += starIndex(i);
        return total == 0 ? 2.0 / STAR_MAX : 2.0 * starIndex(s) / total;
    }

    /** Actual / expected appearances for a star: 1.0 means exactly as often as chance predicts. */
    public double starIndex(int s) {
        return starExpected[s] == 0 ? 1 : starCount[s] / starExpected[s];
    }

    /** Percentile of historical main-ball sums, 0..1. */
    public int sumPercentile(double p) {
        if (mainSums.length == 0) return p < 0.5 ? 15 : 240;
        int idx = (int) Math.round(p * (mainSums.length - 1));
        return mainSums[Math.max(0, Math.min(mainSums.length - 1, idx))];
    }
}
