package com.emp.predictor;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Knows when each game's results should be available, so background checks only go online when needed. */
public final class DrawSchedule {
    private DrawSchedule() {}

    /** Date (yyyy-MM-dd) of the most recent regular draw whose results should be out by {@code nowMillis}. */
    public static String latestExpectedDraw(Game game, long nowMillis) {
        Calendar c = Calendar.getInstance(game.timeZone, Locale.UK);
        c.setTimeInMillis(nowMillis);
        int minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (minutes < game.resultsHour * 60 + game.resultsMinute) c.add(Calendar.DAY_OF_MONTH, -1);
        while (!game.isDrawDay(c.get(Calendar.DAY_OF_WEEK))) c.add(Calendar.DAY_OF_MONTH, -1);
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** True when a draw should have been published that we don't have yet. */
    public static boolean updateDue(Game game, String lastKnownDraw, long nowMillis) {
        return latestExpectedDraw(game, nowMillis).compareTo(lastKnownDraw) > 0;
    }

    /**
     * True if two consecutive stored draws are more than {@link #MAX_GAP_DAYS} apart, which never
     * happens in a complete history (the longest regular gap is a week).
     */
    public static boolean hasGap(List<Draw> draws) {
        for (int i = 1; i < draws.size(); i++) {
            if (daysBetween(draws.get(i - 1).date, draws.get(i).date) > MAX_GAP_DAYS) return true;
        }
        return false;
    }

    public static final int MAX_GAP_DAYS = 10;

    /** Draws are missing: a hole in the history, or it ends well before the latest draw. */
    public static boolean drawsMissing(Game game, List<Draw> draws, long nowMillis) {
        if (draws.isEmpty() || hasGap(draws)) return true;
        String last = draws.get(draws.size() - 1).date;
        return daysBetween(last, latestExpectedDraw(game, nowMillis)) > MAX_GAP_DAYS;
    }

    public static long daysBetween(String a, String b) {
        return (epochDay(b) - epochDay(a));
    }

    private static long epochDay(String iso) {
        return utcDate(iso).getTimeInMillis() / 86400000L;
    }

    private static Calendar utcDate(String iso) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.UK);
        c.clear();
        c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10)));
        return c;
    }

    /** Moves a date that isn't a draw day, but follows one, back to that draw day. */
    public static String snapToDrawDay(Game game, String iso) {
        Calendar c = utcDate(iso);
        if (game.isDrawDay(c.get(Calendar.DAY_OF_WEEK))) return iso;
        c.add(Calendar.DAY_OF_MONTH, -1);
        if (!game.isDrawDay(c.get(Calendar.DAY_OF_WEEK))) return iso;
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Background checks stay quiet overnight so results (Powerball's arrive about 4am UK) don't wake anyone. */
    public static boolean isQuietHours(long nowMillis, TimeZone local) {
        Calendar c = Calendar.getInstance(local, Locale.UK);
        c.setTimeInMillis(nowMillis);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        return hour >= QUIET_FROM_HOUR || hour < QUIET_UNTIL_HOUR;
    }

    public static final int QUIET_FROM_HOUR = 23;
    public static final int QUIET_UNTIL_HOUR = 7;

    /**
     * The draw date (yyyy-MM-dd, phone's calendar) to remind the player about, or null. A reminder
     * is due from {@code reminderHour} on the day before a draw, once per draw.
     */
    public static String reminderFor(Game game, long nowMillis, TimeZone local, int reminderHour, String lastReminded) {
        Calendar c = Calendar.getInstance(local, Locale.UK);
        c.setTimeInMillis(nowMillis);
        if (c.get(Calendar.HOUR_OF_DAY) < reminderHour) return null;
        c.add(Calendar.DAY_OF_MONTH, 1);
        if (!game.isDrawDay(c.get(Calendar.DAY_OF_WEEK))) return null;
        String drawDate = String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        return drawDate.equals(lastReminded) ? null : drawDate;
    }
}
