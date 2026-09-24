package com.emp.predictor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Generates lines by weighted random sampling, with weights taken from the draw history. */
public final class Predictor {
    public static final int MIXED = 0;
    public static final int HOT = 1;
    public static final int RECENT = 2;
    public static final int OVERDUE = 3;

    public static final String[] STRATEGY_NAMES = {
            "Mixed (all-time + recent + overdue)",
            "Hot – all-time frequency",
            "Hot – recent form (last " + Stats.RECENT_WINDOW + " draws)",
            "Overdue – longest since drawn",
    };

    /** Squaring the weights makes favoured numbers noticeably more likely than the rest. */
    private static final double EMPHASIS = 2.0;
    private static final int MAX_ATTEMPTS = 500;

    public static final class Line {
        public final int[] main;
        public final int[] stars;
        /** Average historical appearance rate of the chosen numbers (0..1). */
        public final double mainRate;
        public final double starRate;

        Line(int[] main, int[] stars, double mainRate, double starRate) {
            this.main = main;
            this.stars = stars;
            this.mainRate = mainRate;
            this.starRate = starRate;
        }

        /** Plain text for the clipboard, e.g. "3, 17, 22, 41, 48 | Lucky Stars: 5, 11". */
        public String toClipboardText() {
            return join(main) + " | Lucky Stars: " + join(stars);
        }

        private static String join(int[] nums) {
            StringBuilder sb = new StringBuilder();
            for (int n : nums) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(n);
            }
            return sb.toString();
        }

        String key() {
            return Arrays.toString(main) + Arrays.toString(stars);
        }
    }

    private final Stats stats;
    private final Random random;

    public Predictor(Stats stats, Random random) {
        this.stats = stats;
        this.random = random;
    }

    public double[] mainWeights(int strategy) {
        double[] hot = new double[Stats.MAIN_MAX + 1];
        double[] recent = new double[Stats.MAIN_MAX + 1];
        double[] overdue = new double[Stats.MAIN_MAX + 1];
        for (int n = 1; n <= Stats.MAIN_MAX; n++) {
            hot[n] = stats.mainProbability(n);
            recent[n] = stats.mainRecent[n] + 1;
            overdue[n] = stats.mainGap[n] + 1;
        }
        return finish(strategy, hot, recent, overdue);
    }

    public double[] starWeights(int strategy) {
        double[] hot = new double[Stats.STAR_MAX + 1];
        double[] recent = new double[Stats.STAR_MAX + 1];
        double[] overdue = new double[Stats.STAR_MAX + 1];
        for (int s = 1; s <= Stats.STAR_MAX; s++) {
            hot[s] = stats.starProbability(s);
            recent[s] = stats.starRecent[s] + 1;
            overdue[s] = stats.starGap[s] + 1;
        }
        return finish(strategy, hot, recent, overdue);
    }

    private static double[] finish(int strategy, double[] hot, double[] recent, double[] overdue) {
        double[] w;
        switch (strategy) {
            case HOT: w = normalise(hot); break;
            case RECENT: w = normalise(recent); break;
            case OVERDUE: w = normalise(overdue); break;
            default:
                double[] a = normalise(hot), b = normalise(recent), c = normalise(overdue);
                w = new double[a.length];
                for (int i = 1; i < w.length; i++) w[i] = (a[i] + b[i] + c[i]) / 3;
        }
        for (int i = 1; i < w.length; i++) w[i] = Math.pow(w[i] * (w.length - 1), EMPHASIS);
        return normalise(w);
    }

    /** Scales entries 1..n to sum to 1 (index 0 is unused). */
    private static double[] normalise(double[] v) {
        double sum = 0;
        for (int i = 1; i < v.length; i++) sum += v[i];
        double[] out = new double[v.length];
        for (int i = 1; i < v.length; i++) out[i] = sum == 0 ? 1.0 / (v.length - 1) : v[i] / sum;
        return out;
    }

    /**
     * Generates {@code count} distinct lines. With {@code typicalOnly}, lines whose ball total falls
     * outside the middle 90% of past draws, or which are all odd / all even, are redrawn.
     */
    public List<Line> generate(int count, int strategy, boolean typicalOnly) {
        double[] mw = mainWeights(strategy);
        double[] sw = starWeights(strategy);
        int lowSum = stats.sumPercentile(0.05);
        int highSum = stats.sumPercentile(0.95);

        List<Line> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int attempts = 0;
        while (lines.size() < count) {
            attempts++;
            int[] main = pick(mw, 5);
            int[] stars = pick(sw, 2);
            boolean relaxed = attempts > MAX_ATTEMPTS * count;
            if (typicalOnly && !relaxed && !isTypical(main, lowSum, highSum)) continue;
            double mr = 0, sr = 0;
            for (int n : main) mr += stats.mainProbability(n) / 5;
            for (int s : stars) sr += stats.starProbability(s) / 2;
            Line line = new Line(main, stars, mr, sr);
            if (seen.add(line.key())) lines.add(line);
        }
        return lines;
    }

    static boolean isTypical(int[] main, int lowSum, int highSum) {
        int sum = 0, odd = 0;
        for (int n : main) {
            sum += n;
            if (n % 2 == 1) odd++;
        }
        return sum >= lowSum && sum <= highSum && odd >= 1 && odd <= 4;
    }

    /** Weighted sampling without replacement; returns sorted numbers. */
    private int[] pick(double[] weights, int k) {
        double[] w = weights.clone();
        int[] out = new int[k];
        for (int j = 0; j < k; j++) {
            double total = 0;
            for (int i = 1; i < w.length; i++) total += w[i];
            double r = random.nextDouble() * total;
            int chosen = w.length - 1;
            for (int i = 1; i < w.length; i++) {
                r -= w[i];
                if (r < 0 && w[i] > 0) {
                    chosen = i;
                    break;
                }
            }
            while (w[chosen] == 0) chosen--; // guard against rounding at the end of the range
            out[j] = chosen;
            w[chosen] = 0;
        }
        Arrays.sort(out);
        return out;
    }

    public static String percent(double p) {
        return String.format(Locale.UK, "%.1f%%", p * 100);
    }
}
