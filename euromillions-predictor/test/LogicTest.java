import com.emp.predictor.Draw;
import com.emp.predictor.DrawParser;
import com.emp.predictor.DrawSchedule;
import com.emp.predictor.Game;
import com.emp.predictor.Predictor;
import com.emp.predictor.Stats;

import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/** Plain-JVM checks for parsing, statistics, prediction and scheduling. Run via test/run.sh. */
public class LogicTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static long at(String zone, String t) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        f.setTimeZone(TimeZone.getTimeZone(zone));
        return f.parse(t).getTime();
    }

    static List<Draw> file(String path, Game game) throws IOException {
        try (FileReader r = new FileReader(path)) {
            return DrawParser.parse(r, game);
        }
    }

    static String nums(Draw d) {
        StringBuilder sb = new StringBuilder(d.date);
        for (int n : d.main) sb.append(' ').append(n);
        sb.append(" +");
        for (int e : d.extra) sb.append(' ').append(e);
        return sb.toString();
    }

    static final String L0 = "Europe/London";

    public static void main(String[] args) throws Exception {
        int[] expectedCounts = {1983, 3089, 785, 314, 3859};
        String[] firstDates = {"2004-02-13", "1994-11-19", "2019-03-18", "2021-12-21", "1992-04-22"};
        for (int gi = 0; gi < Game.ALL.length; gi++) {
            Game g = Game.ALL[gi];
            List<Draw> draws = file("assets/" + g.id + ".csv", g);
            check(draws.size() == expectedCounts[gi], g.name + ": bundled history loads (" + draws.size() + " draws)");
            check(draws.get(0).date.equals(firstDates[gi]), g.name + ": history starts with the first draw");
            Set<String> dates = new HashSet<>();
            for (Draw d : draws) dates.add(d.date);
            check(dates.size() == draws.size(), g.name + ": no duplicate draw dates");
            if (g.historyNote == null) check(!DrawSchedule.hasGap(draws), g.name + ": no gaps in bundled history");

            // Round trip through the app's own save format.
            StringWriter w = new StringWriter();
            DrawParser.write(draws, g, w);
            List<Draw> again = DrawParser.parse(new StringReader(w.toString()), g);
            check(again.size() == draws.size() && nums(again.get(again.size() - 1)).equals(nums(draws.get(draws.size() - 1))),
                    g.name + ": saved file reads back identically");

            Stats st = new Stats(g, draws);
            double mainSum = 0, extraSum = 0;
            for (int n = 1; n <= st.main.pool; n++) mainSum += st.main.probability(n);
            for (int n = 1; n <= st.extra.pool; n++) extraSum += st.extra.probability(n);
            check(Math.abs(mainSum - g.mainCount) < 1e-9 && Math.abs(extraSum - g.extraCount) < 1e-9,
                    g.name + ": probabilities add up to the balls drawn per draw");
            check(st.main.eligible[st.main.pool] <= st.main.eligible[1], g.name + ": newer numbers only judged on draws where they existed");

            Predictor p = new Predictor(st, new Random(42));
            for (int strat = 0; strat < Predictor.STRATEGY_NAMES.length; strat++) {
                List<Predictor.Line> lines = p.generate(10, strat, true);
                boolean ok = lines.size() == 10;
                Set<String> keys = new HashSet<>();
                for (Predictor.Line l : lines) {
                    ok &= l.main.length == g.mainCount && l.extra.length == (g.extraPicked ? g.extraCount : 0);
                    for (int n : l.main) ok &= n >= 1 && n <= g.currentMainPool();
                    for (int e : l.extra) ok &= e >= 1 && e <= g.currentExtraPool();
                    keys.add(l.toClipboardText());
                }
                check(ok && keys.size() == 10, g.name + ": strategy " + strat + " gives 10 valid distinct lines, e.g. "
                        + lines.get(0).toClipboardText());
            }
            double[] w2 = p.mainWeights(Predictor.HOT);
            int best = 1, worst = 1;
            for (int n = 1; n < w2.length; n++) {
                if (w2[n] > w2[best]) best = n;
                if (w2[n] < w2[worst]) worst = n;
            }
            int[] hits = new int[w2.length];
            for (Predictor.Line l : p.generate(4000, Predictor.HOT, false)) for (int n : l.main) hits[n]++;
            check(hits[best] > hits[worst], g.name + ": hot strategy favours most frequent number (" + best + ": "
                    + hits[best] + " vs " + worst + ": " + hits[worst] + ")");
        }

        // Download formats.
        List<Draw> emMersey = file("test/fixtures/merseyworld_euromillions.html", Game.EUROMILLIONS);
        check(emMersey.size() == 15 && nums(emMersey.get(emMersey.size() - 1)).equals("2025-07-29 5 6 42 44 46 + 4 8"),
                "EuroMillions: reads merseyworld archive page");
        List<Draw> lottoMersey = file("test/fixtures/merseyworld_lotto.html", Game.LOTTO);
        check(lottoMersey.size() == 15 && nums(lottoMersey.get(0)).equals("1994-11-19 3 5 14 22 30 44 + 10"),
                "Lotto: reads merseyworld archive page (" + lottoMersey.size() + " draws)");
        List<Draw> emNl = file("test/fixtures/national_lottery_euromillions.csv", Game.EUROMILLIONS);
        check(emNl.size() == 2 && nums(emNl.get(1)).equals("2026-09-25 1 9 20 33 47 + 4 12"), "EuroMillions: reads National Lottery CSV");
        List<Draw> lottoNl = file("test/fixtures/national_lottery_lotto.csv", Game.LOTTO);
        check(lottoNl.size() == 2 && nums(lottoNl.get(1)).equals("2026-09-19 4 11 23 35 47 58 + 19"),
                "Lotto: reads National Lottery CSV (Ball Set column ignored)");
        List<Draw> pbJ = file("test/fixtures/powerball_jbaranski.csv", Game.POWERBALL);
        check(pbJ.size() == 6 && nums(pbJ.get(5)).equals("2026-09-23 5 15 26 29 30 + 14"), "Powerball: reads white_balls|red_ball CSV with US dates");
        List<Draw> pb92 = file("test/fixtures/powerball_1992.csv", Game.POWERBALL);
        check(pb92.size() == 3 && nums(pb92.get(2)).equals("2019-10-16 1 5 25 63 67 + 3"), "Powerball: reads ball1..powerball CSV");
        List<Draw> combined = DrawParser.parse(new StringReader("Draw Date,Winning Numbers,Multiplier\n09/23/2026,05 15 26 29 30 14,5\n"), Game.POWERBALL);
        check(combined.size() == 1 && nums(combined.get(0)).equals("2026-09-23 5 15 26 29 30 + 14"), "Powerball: reads combined Winning Numbers column");

        // Merging.
        List<Draw> lotto = file("assets/lotto.csv", Game.LOTTO);
        List<Draw> recent = DrawParser.merge(lotto, lottoNl, Game.LOTTO, Game.Mode.APPEND_NEWER);
        check(recent.size() == lotto.size() + 2, "Lotto: newer draws appended");
        check(DrawSchedule.hasGap(recent), "Lotto: gap between bundled history and latest download is detected");
        List<Draw> wrongDate = new ArrayList<>();
        Draw last = lotto.get(lotto.size() - 1);
        wrongDate.add(new Draw("2025-08-02", last.main, last.extra));
        check(DrawParser.merge(lotto, wrongDate, Game.LOTTO, Game.Mode.APPEND_NEWER).size() == lotto.size(), "repeat of previous draw under a new date is ignored");
        List<Draw> hole = new ArrayList<>(lotto);
        Draw removed = hole.remove(1500);
        List<Draw> filled = DrawParser.merge(hole, lotto, Game.LOTTO, Game.Mode.FILL);
        check(filled.size() == lotto.size() && filled.get(1500).date.equals(removed.date), "FILL restores a missing draw");
        check(DrawParser.merge(hole, lotto, Game.LOTTO, Game.Mode.APPEND_NEWER).size() == hole.size(), "APPEND_NEWER never back-fills");
        check(DrawParser.merge(lotto, lottoMersey, Game.LOTTO, Game.Mode.FILL).size() == lotto.size(), "FILL never replaces stored draws");

        // Rules by era.
        check(Game.LOTTO.mainPool("2015-10-07") == 49 && Game.LOTTO.mainPool("2015-10-10") == 59, "Lotto: 59-ball matrix from 10 Oct 2015");
        check(Game.POWERBALL.extraPool("2015-10-03") == 35 && Game.POWERBALL.extraPool("2015-10-07") == 26, "Powerball: 26 Powerballs from 7 Oct 2015");
        check(!new Draw("2026-09-19", new int[]{1, 2, 3, 4, 5, 6}, new int[]{6}).isValid(Game.LOTTO), "Lotto: bonus ball can't repeat a main number");

        // UK Powerball: a draw listed under the UK date (the morning after) keeps its US date.
        List<Draw> ukPb = DrawParser.parse(new StringReader("DrawDate,Ball 1,Ball 2,Ball 3,Ball 4,Ball 5,Powerball,DrawNumber\n"
                + "24-Sep-2026,5,15,26,29,30,14,1\n27-Sep-2026,1,2,3,4,5,6,2\n"), Game.POWERBALL);
        check(ukPb.size() == 2 && ukPb.get(0).date.equals("2026-09-23") && ukPb.get(1).date.equals("2026-09-26"),
                "Powerball: UK-dated results move back to the US draw date");
        List<Draw> pbAll = file("assets/powerball.csv", Game.POWERBALL);
        check(DrawParser.merge(pbAll, ukPb.subList(0, 1), Game.POWERBALL, Game.Mode.APPEND_NEWER).size() == pbAll.size(),
                "Powerball: UK copy of a stored draw isn't added twice");
        List<Draw> emOdd = DrawParser.parse(new StringReader("date,n1,n2,n3,n4,n5,s1,s2\n2026-09-23,1,2,3,4,5,1,2\n"), Game.EUROMILLIONS);
        check(emOdd.size() == 1 && emOdd.get(0).date.equals("2026-09-23"), "other games' dates are left alone");
        TimeZone uk = TimeZone.getTimeZone("Europe/London");
        check(DrawSchedule.isQuietHours(at(L0, "2026-09-24 04:30"), uk) && DrawSchedule.isQuietHours(at(L0, "2026-09-24 23:15"), uk)
                && !DrawSchedule.isQuietHours(at(L0, "2026-09-24 07:05"), uk) && !DrawSchedule.isQuietHours(at(L0, "2026-09-24 21:45"), uk),
                "background checks are quiet 11pm-7am");

        // Set For Life and Thunderball formats.
        List<Draw> sfl = file("test/fixtures/set_for_life_github.csv", Game.SET_FOR_LIFE);
        check(sfl.size() == 3 && nums(sfl.get(2)).equals("2026-09-21 24 28 37 40 42 + 7"), "Set For Life: reads GitHub CSV (d/m/y dates, Life column)");
        List<Draw> sflNl = file("test/fixtures/national_lottery_set_for_life.csv", Game.SET_FOR_LIFE);
        check(sflNl.size() == 2 && nums(sflNl.get(1)).equals("2026-09-21 24 28 37 40 42 + 7"), "Set For Life: reads National Lottery CSV (Life Ball)");
        List<Draw> tbNl = file("test/fixtures/national_lottery_thunderball.csv", Game.THUNDERBALL);
        check(tbNl.size() == 3 && nums(tbNl.get(2)).equals("2026-09-23 8 18 21 24 34 + 1"), "Thunderball: reads National Lottery CSV");
        List<Draw> tbAll = file("assets/thunderball.csv", Game.THUNDERBALL);
        check(DrawParser.merge(tbAll, tbNl, Game.THUNDERBALL, Game.Mode.APPEND_NEWER).size() == tbAll.size(), "Thunderball: stored draws not duplicated");

        // Lotto's two rounds per night since 10 Jun 2026.
        List<Draw> twoRows = DrawParser.parse(new StringReader("DrawDate,Ball 1,Ball 2,Ball 3,Ball 4,Ball 5,Ball 6,Bonus Ball,Ball Set,Machine,DrawNumber\n"
                + "23-Sep-2026,1,2,3,4,5,6,7,1,Arthur,3310\n23-Sep-2026,11,12,13,14,15,16,17,2,Merlin,3311\n"), Game.LOTTO);
        check(twoRows.size() == 2, "Lotto: two rounds on separate rows both read");
        List<Draw> oneRow = DrawParser.parse(new StringReader("DrawDate,Round 1 Ball 1,Round 1 Ball 2,Round 1 Ball 3,Round 1 Ball 4,Round 1 Ball 5,Round 1 Ball 6,"
                + "Round 1 Bonus Ball,Round 2 Ball 1,Round 2 Ball 2,Round 2 Ball 3,Round 2 Ball 4,Round 2 Ball 5,Round 2 Ball 6,Round 2 Bonus Ball,DrawNumber\n"
                + "23-Sep-2026,1,2,3,4,5,6,7,11,12,13,14,15,16,17,3310\n"), Game.LOTTO);
        check(oneRow.size() == 2 && nums(oneRow.get(1)).equals("2026-09-23 11 12 13 14 15 16 + 17"), "Lotto: two rounds on one row split into two draws");
        List<Draw> withRounds = DrawParser.merge(lotto, twoRows, Game.LOTTO, Game.Mode.APPEND_NEWER);
        check(withRounds.size() == lotto.size() + 2, "Lotto: both rounds of a night are stored");
        List<Draw> third = new ArrayList<>(twoRows);
        third.add(new Draw("2026-09-23", new int[]{21, 22, 23, 24, 25, 26}, new int[]{27}));
        check(DrawParser.merge(lotto, third, Game.LOTTO, Game.Mode.APPEND_NEWER).size() == lotto.size() + 2, "Lotto: never more than two draws a night");
        List<Draw> oldDay = new ArrayList<>();
        oldDay.add(new Draw("2025-07-30", new int[]{21, 22, 23, 24, 25, 26}, new int[]{27}));
        check(DrawParser.merge(lotto, oldDay, Game.LOTTO, Game.Mode.FILL).size() == lotto.size(), "Lotto: one draw a night before June 2026");

        // Reminders the day before a draw (phone in UK time).
        TimeZone ukTz = TimeZone.getTimeZone("Europe/London");
        check("2026-09-26".equals(DrawSchedule.reminderFor(Game.LOTTO, at(L0, "2026-09-25 18:10"), ukTz, 18, null)), "reminder: Friday 6pm for Saturday's Lotto");
        check(DrawSchedule.reminderFor(Game.LOTTO, at(L0, "2026-09-25 17:50"), ukTz, 18, null) == null, "reminder: not before the chosen time");
        check(DrawSchedule.reminderFor(Game.LOTTO, at(L0, "2026-09-25 19:10"), ukTz, 18, "2026-09-26") == null, "reminder: only once per draw");
        check(DrawSchedule.reminderFor(Game.LOTTO, at(L0, "2026-09-24 18:10"), ukTz, 18, null) == null, "reminder: none when tomorrow has no draw");
        check("2026-09-28".equals(DrawSchedule.reminderFor(Game.SET_FOR_LIFE, at(L0, "2026-09-27 18:00"), ukTz, 18, null)), "reminder: Sunday for Monday's Set For Life");
        check("2026-09-26".equals(DrawSchedule.reminderFor(Game.POWERBALL, at(L0, "2026-09-25 20:00"), ukTz, 18, null)), "reminder: Friday for Saturday's Powerball ticket day");

        // Draw timing.
        String L = "Europe/London", NY = "America/New_York";
        check(DrawSchedule.latestExpectedDraw(Game.EUROMILLIONS, at(L, "2026-09-22 21:00")).equals("2026-09-18"), "EuroMillions: before Tuesday's results");
        check(DrawSchedule.latestExpectedDraw(Game.EUROMILLIONS, at(L, "2026-09-22 21:45")).equals("2026-09-22"), "EuroMillions: after Tuesday's results");
        check(DrawSchedule.latestExpectedDraw(Game.LOTTO, at(L, "2026-09-24 10:00")).equals("2026-09-23"), "Lotto: Thursday expects Wednesday's draw");
        check(DrawSchedule.latestExpectedDraw(Game.LOTTO, at(L, "2026-09-26 20:00")).equals("2026-09-23"), "Lotto: Saturday before results");
        check(DrawSchedule.latestExpectedDraw(Game.POWERBALL, at(NY, "2026-09-21 23:50")).equals("2026-09-21"), "Powerball: Monday night results");
        check(DrawSchedule.latestExpectedDraw(Game.POWERBALL, at(L, "2026-09-22 03:00")).equals("2026-09-19"), "Powerball: UK early hours before Monday's results");
        check(!DrawSchedule.updateDue(Game.POWERBALL, "2026-09-23", at(NY, "2026-09-25 12:00")), "Powerball: no check needed Friday");
        check(DrawSchedule.drawsMissing(Game.LOTTO, lotto, at(L, "2026-09-24 12:00")), "Lotto: bundled history ending Jul 2025 triggers a full fill");
        check(!DrawSchedule.drawsMissing(Game.POWERBALL, file("assets/powerball.csv", Game.POWERBALL), at(NY, "2026-09-24 12:00")),
                "Powerball: up-to-date history needs no fill");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
