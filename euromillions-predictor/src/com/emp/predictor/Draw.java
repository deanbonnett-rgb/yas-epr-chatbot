package com.emp.predictor;

import java.util.Arrays;

/**
 * One draw: date (yyyy-MM-dd), the main numbers and the extra balls (Lucky Stars, Powerball or
 * Lotto's bonus ball), each sorted.
 */
public final class Draw implements Comparable<Draw> {
    public final String date;
    public final int[] main;
    public final int[] extra;

    public Draw(String date, int[] main, int[] extra) {
        this.date = date;
        this.main = main.clone();
        this.extra = extra.clone();
        Arrays.sort(this.main);
        Arrays.sort(this.extra);
    }

    public boolean isValid(Game game) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        if (main.length != game.mainCount || extra.length != game.extraCount) return false;
        if (!distinctInRange(main, game.mainPool(date))) return false;
        if (!distinctInRange(extra, game.extraPool(date))) return false;
        if (game.extraFromMainDrum) {
            for (int e : extra) if (Arrays.binarySearch(main, e) >= 0) return false;
        }
        return true;
    }

    /** Same numbers as another draw, used to spot a draw repeated under a wrong date. */
    public boolean sameNumbers(Draw o) {
        return Arrays.equals(main, o.main) && Arrays.equals(extra, o.extra);
    }

    private static boolean distinctInRange(int[] sorted, int pool) {
        for (int i = 0; i < sorted.length; i++) {
            if (sorted[i] < 1 || sorted[i] > pool) return false;
            if (i > 0 && sorted[i] == sorted[i - 1]) return false;
        }
        return true;
    }

    @Override
    public int compareTo(Draw o) {
        int c = date.compareTo(o.date);
        return c != 0 ? c : (Arrays.toString(main) + Arrays.toString(extra)).compareTo(Arrays.toString(o.main) + Arrays.toString(o.extra));
    }
}
