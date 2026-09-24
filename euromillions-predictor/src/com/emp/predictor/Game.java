package com.emp.predictor;

import java.util.Calendar;
import java.util.TimeZone;

/** Rules, history eras and data sources for one lottery game. */
public final class Game {
    /** How a download is merged into the stored history. */
    public enum Mode {
        /** Only draws newer than the latest stored one are added (sources with some misdated old rows). */
        APPEND_NEWER,
        /** Any missing draw date is filled in (complete, trusted archives). Stored draws always win. */
        FILL
    }

    public static final class Source {
        public final String url;
        public final Mode mode;

        Source(String url, Mode mode) {
            this.url = url;
            this.mode = mode;
        }
    }

    public final String id;
    public final String name;
    public final String country;
    /** Main numbers drawn (and picked). */
    public final int mainCount;
    /** Extra balls drawn: Lucky Stars, the Powerball, or Lotto's bonus ball. */
    public final int extraCount;
    /** Whether players choose the extra balls (not Lotto's bonus ball). */
    public final boolean extraPicked;
    /** Lotto's bonus ball comes from the same drum as the main numbers. */
    public final boolean extraFromMainDrum;
    public final String extraName;
    /** Era start dates (yyyy-MM-dd, ascending) with the pool sizes in force from that date. */
    private final String[] eraStarts;
    private final int[] mainPools;
    private final int[] extraPools;
    /** Calendar.DAY_OF_WEEK values of regular draws. */
    public final int[] drawDays;
    public final TimeZone timeZone;
    /** Local time by which results are normally published. */
    public final int resultsHour, resultsMinute;
    /** Numeric dates in this game's sources are month-first (US). */
    public final boolean usDates;
    /**
     * Some sources list a draw under the date it happened somewhere else (UK Powerball results are
     * dated the morning after the US draw). Such dates are moved back to the previous draw day.
     */
    public final boolean snapToDrawDay;
    /** Extra line about draw times for UK players, or null. */
    public final String drawNote;
    public final String jackpotOdds;
    public final int accent;
    public final int extraColor;
    public final Source[] sources;

    private Game(Builder b) {
        id = b.id; name = b.name; country = b.country;
        mainCount = b.mainCount; extraCount = b.extraCount; extraPicked = b.extraPicked;
        extraFromMainDrum = b.extraFromMainDrum; extraName = b.extraName;
        eraStarts = b.eraStarts; mainPools = b.mainPools; extraPools = b.extraPools;
        drawDays = b.drawDays; timeZone = TimeZone.getTimeZone(b.timeZone);
        resultsHour = b.resultsHour; resultsMinute = b.resultsMinute; usDates = b.usDates;
        snapToDrawDay = b.snapToDrawDay; drawNote = b.drawNote;
        jackpotOdds = b.jackpotOdds; accent = b.accent; extraColor = b.extraColor; sources = b.sources;
    }

    public static final Game EUROMILLIONS = new Builder("euromillions", "EuroMillions", "UK & Europe")
            .balls(5, 2, true, false, "Lucky Stars")
            .eras(new String[]{"2004-02-13", "2011-05-10", "2016-09-27"}, new int[]{50, 50, 50}, new int[]{9, 11, 12})
            .schedule("Europe/London", 21, 30, Calendar.TUESDAY, Calendar.FRIDAY)
            .look("1 in 139,838,160", 0xFF3D7BFF, 0xFFF5C518)
            .sources(new Source("https://www.national-lottery.co.uk/results/euromillions/draw-history/csv", Mode.APPEND_NEWER),
                    new Source("https://raw.githubusercontent.com/daowa89/lottery-archive/main/eu/euromillions/results.csv", Mode.APPEND_NEWER),
                    new Source("https://lottery.merseyworld.com/cgi-bin/lottery?days=20&Machine=Z&Ballset=0&order=1&show=1&year=0&display=CSV", Mode.FILL))
            .build();

    public static final Game LOTTO = new Builder("lotto", "Lotto", "UK National Lottery")
            .balls(6, 1, false, true, "Bonus Ball")
            .eras(new String[]{"1994-11-19", "2015-10-10"}, new int[]{49, 59}, new int[]{49, 59})
            .schedule("Europe/London", 21, 30, Calendar.WEDNESDAY, Calendar.SATURDAY)
            .look("1 in 45,057,474", 0xFFE5007E, 0xFF9AA3C0)
            .sources(new Source("https://www.national-lottery.co.uk/results/lotto/draw-history/csv", Mode.APPEND_NEWER),
                    new Source("https://lotto.merseyworld.com/cgi-bin/lottery?days=2&Machine=Z&Ballset=0&order=1&show=1&year=0&display=CSV", Mode.FILL))
            .build();

    public static final Game POWERBALL = new Builder("powerball", "Powerball", "USA & UK")
            .balls(5, 1, true, false, "Powerball")
            .eras(new String[]{"1992-04-22", "1997-11-05", "2002-10-09", "2005-08-31", "2009-01-07", "2012-01-15", "2015-10-07"},
                    new int[]{45, 49, 53, 55, 59, 59, 69}, new int[]{45, 42, 42, 42, 39, 35, 26})
            .schedule("America/New_York", 23, 45, Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.SATURDAY)
            .look("1 in 292,201,338", 0xFFE4002B, 0xFFE4002B)
            .usDates()
            .ukTiming("Drawn in the US – results about 4am UK time on Tue, Thu & Sun")
            .sources(new Source("https://raw.githubusercontent.com/jbaranski/jeffs-lottery-utils/main/numbers/powerball.csv", Mode.APPEND_NEWER),
                    new Source("https://www.national-lottery.co.uk/results/powerball/draw-history/csv", Mode.APPEND_NEWER))
            .build();

    public static final Game[] ALL = {EUROMILLIONS, LOTTO, POWERBALL};

    public static Game byId(String id) {
        for (Game g : ALL) if (g.id.equals(id)) return g;
        return null;
    }

    private int era(String isoDate) {
        int e = 0;
        for (int i = 0; i < eraStarts.length; i++) if (isoDate.compareTo(eraStarts[i]) >= 0) e = i;
        return e;
    }

    public int mainPool(String isoDate) { return mainPools[era(isoDate)]; }

    public int extraPool(String isoDate) { return extraPools[era(isoDate)]; }

    public int currentMainPool() { return mainPools[mainPools.length - 1]; }

    public int currentExtraPool() { return extraPools[extraPools.length - 1]; }

    /** Largest pool ever used, for sizing per-number arrays. */
    public int maxMainPool() { return max(mainPools); }

    public int maxExtraPool() { return max(extraPools); }

    /** Draws in the history that use today's rules. */
    public boolean isCurrentEra(String isoDate) { return era(isoDate) == eraStarts.length - 1; }

    public String currentEraStart() { return eraStarts[eraStarts.length - 1]; }

    /** Short description of the current format, e.g. "5 from 50 + 2 Lucky Stars from 12". */
    public String formatDescription() {
        String s = mainCount + " from " + currentMainPool();
        if (extraPicked) s += " + " + (extraCount == 1 ? "" : extraCount + " ") + extraName + " from " + currentExtraPool();
        return s;
    }

    public boolean isDrawDay(int dayOfWeek) {
        for (int d : drawDays) if (d == dayOfWeek) return true;
        return false;
    }

    public String drawDaysText() {
        String[] names = {"", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < drawDays.length; i++) {
            if (i > 0) sb.append(i == drawDays.length - 1 ? " & " : ", ");
            sb.append(names[drawDays[i]]);
        }
        return sb.toString();
    }

    private static int max(int[] a) {
        int m = 0;
        for (int v : a) m = Math.max(m, v);
        return m;
    }

    private static final class Builder {
        final String id, name, country;
        int mainCount, extraCount;
        boolean extraPicked, extraFromMainDrum, usDates, snapToDrawDay;
        String drawNote;
        String extraName, timeZone, jackpotOdds;
        String[] eraStarts;
        int[] mainPools, extraPools, drawDays;
        int resultsHour, resultsMinute, accent, extraColor;
        Source[] sources;

        Builder(String id, String name, String country) { this.id = id; this.name = name; this.country = country; }

        Builder balls(int main, int extra, boolean picked, boolean sameDrum, String extraName) {
            mainCount = main; extraCount = extra; extraPicked = picked; extraFromMainDrum = sameDrum; this.extraName = extraName;
            return this;
        }

        Builder eras(String[] starts, int[] main, int[] extra) { eraStarts = starts; mainPools = main; extraPools = extra; return this; }

        Builder schedule(String tz, int hour, int minute, int... days) {
            timeZone = tz; resultsHour = hour; resultsMinute = minute; drawDays = days;
            return this;
        }

        Builder look(String odds, int accent, int extraColor) { jackpotOdds = odds; this.accent = accent; this.extraColor = extraColor; return this; }

        Builder usDates() { usDates = true; return this; }

        Builder ukTiming(String note) { drawNote = note; snapToDrawDay = true; return this; }

        Builder sources(Source... s) { sources = s; return this; }

        Game build() { return new Game(this); }
    }
}
