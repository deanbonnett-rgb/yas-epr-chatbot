package com.emp.predictor;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Knows when EuroMillions results should be available, so background checks only go online when needed. */
public final class DrawSchedule {
    public static final TimeZone UK = TimeZone.getTimeZone("Europe/London");
    /** Draws are made at about 20:45 UK time; allow time for the results to be published. */
    static final int RESULTS_HOUR = 21;
    static final int RESULTS_MINUTE = 30;

    private DrawSchedule() {}

    /** Date (yyyy-MM-dd) of the most recent Tuesday/Friday draw whose results should be out by {@code nowMillis}. */
    public static String latestExpectedDraw(long nowMillis) {
        Calendar c = Calendar.getInstance(UK, Locale.UK);
        c.setTimeInMillis(nowMillis);
        int minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (minutes < RESULTS_HOUR * 60 + RESULTS_MINUTE) c.add(Calendar.DAY_OF_MONTH, -1);
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.TUESDAY && c.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** True when a draw should have been published that we don't have yet. */
    public static boolean updateDue(String lastKnownDraw, long nowMillis) {
        return latestExpectedDraw(nowMillis).compareTo(lastKnownDraw) > 0;
    }
}
