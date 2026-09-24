package com.emp.predictor;

import java.util.Arrays;

/** One EuroMillions draw: date (yyyy-MM-dd), five main balls and two Lucky Stars, both sorted. */
public final class Draw implements Comparable<Draw> {
    public final String date;
    public final int[] main;
    public final int[] stars;

    public Draw(String date, int[] main, int[] stars) {
        this.date = date;
        this.main = main.clone();
        this.stars = stars.clone();
        Arrays.sort(this.main);
        Arrays.sort(this.stars);
    }

    /** Size of the Lucky Star pool in force on this draw's date. */
    public int starPoolSize() {
        return starPoolSizeOn(date);
    }

    /**
     * Lucky Stars were 1-9 at launch, 1-11 from 10 May 2011 and 1-12 from 27 Sep 2016.
     * Needed so stars 10-12 are not judged against draws where they could not appear.
     */
    public static int starPoolSizeOn(String isoDate) {
        if (isoDate.compareTo("2016-09-27") >= 0) return 12;
        if (isoDate.compareTo("2011-05-10") >= 0) return 11;
        return 9;
    }

    public boolean isValid() {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        if (main.length != 5 || stars.length != 2) return false;
        for (int i = 0; i < 5; i++) {
            if (main[i] < 1 || main[i] > 50) return false;
            if (i > 0 && main[i] == main[i - 1]) return false;
        }
        int pool = starPoolSize();
        return stars[0] >= 1 && stars[1] <= pool && stars[0] != stars[1];
    }

    @Override
    public int compareTo(Draw o) {
        return date.compareTo(o.date);
    }
}
