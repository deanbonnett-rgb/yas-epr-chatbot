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
        public final Game game;
        public final int[] main;
        /** Empty for Lotto, where players don't choose the bonus ball. */
        public final int[] extra;
        /** Average historical appearance rate of the chosen numbers (0..1). */
        public final double mainRate;
        public final double extraRate;

        Line(Game game, int[] main, int[] extra, double mainRate, double extraRate) {
            this.game = game;
            this.main = main;
            this.extra = extra;
            this.mainRate = mainRate;
            this.extraRate = extraRate;
        }

        /** Plain text for the clipboard, e.g. "3, 17, 22, 41, 48 | Lucky Stars: 5, 11". */
        public String toClipboardText() {
            String s = join(main);
            if (extra.length > 0) s += " | " + game.extraName + ": " + join(extra);
            return s;
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
            return Arrays.toString(main) + Arrays.toString(extra);
        }
    }

    private final Stats stats;
    private final Game game;
    private final Random random;

    public Predictor(Stats stats, Random random) {
        this.stats = stats;
        this.game = stats.game;
        this.random = random;
    }

    public double[] mainWeights(int strategy) {
        return weights(stats.main, strategy);
    }

    public double[] extraWeights(int strategy) {
        return weights(stats.extra, strategy);
    }

    /** Weights for numbers 1..today's pool (index 0 unused), summing to 1. */
    private static double[] weights(Stats.Balls b, int strategy) {
        double[] hot = new double[b.pool + 1];
        double[] recent = new double[b.pool + 1];
        double[] overdue = new double[b.pool + 1];
        for (int n = 1; n <= b.pool; n++) {
            hot[n] = b.probability(n);
            recent[n] = b.recent[n] + 1;
            overdue[n] = b.gap[n] + 1;
        }
        double[] w;
        switch (strategy) {
            case HOT: w = normalise(hot); break;
            case RECENT: w = normalise(recent); break;
            case OVERDUE: w = normalise(overdue); break;
            default:
                double[] x = normalise(hot), y = normalise(recent), z = normalise(overdue);
                w = new double[x.length];
                for (int i = 1; i < w.length; i++) w[i] = (x[i] + y[i] + z[i]) / 3;
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
     * Generates {@code count} distinct lines. With {@code typicalOnly}, lines whose main-number total
     * falls outside the middle 90% of draws under today's rules, or which are all odd / all even,
     * are redrawn.
     */
    public List<Line> generate(int count, int strategy, boolean typicalOnly) {
        double[] mw = mainWeights(strategy);
        double[] ew = extraWeights(strategy);
        int lowSum = stats.sumPercentile(0.05);
        int highSum = stats.sumPercentile(0.95);
        int extraPicks = game.extraPicked ? game.extraCount : 0;

        List<Line> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int attempts = 0;
        while (lines.size() < count) {
            attempts++;
            int[] main = pick(mw, game.mainCount);
            int[] extra = pick(ew, extraPicks);
            boolean relaxed = attempts > MAX_ATTEMPTS * count;
            if (typicalOnly && !relaxed && !isTypical(main, lowSum, highSum)) continue;
            double mr = 0, er = 0;
            for (int n : main) mr += stats.main.probability(n) / main.length;
            for (int e : extra) er += stats.extra.probability(e) / extra.length;
            Line line = new Line(game, main, extra, mr, er);
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
        return sum >= lowSum && sum <= highSum && odd >= 1 && odd <= main.length - 1;
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
