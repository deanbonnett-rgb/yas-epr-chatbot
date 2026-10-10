import com.emp.predictor.Draw;
import com.emp.predictor.DrawParser;
import com.emp.predictor.DrawSchedule;
import com.emp.predictor.Game;
import com.emp.predictor.Predictor;
import com.emp.predictor.Prizes;
import com.emp.predictor.Stats;
import com.emp.predictor.Ticket;
import com.emp.predictor.TicketChecker;
import com.emp.predictor.TicketTextParser;

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

        // Tickets: save format, checking and prizes.
        Ticket tk = new Ticket(1L, "thunderball", "2026-09-23", new int[]{34, 8, 2, 3, 4}, new int[]{1}, Ticket.GENERATED, 100, -1);
        Ticket back = Ticket.decode(tk.encode());
        check(back.encode().equals(tk.encode()) && back.isValid(), "ticket saves and loads");
        check(Ticket.decodeAll("garbage\n" + tk.encode() + "\n").size() == 1, "damaged ticket lines are skipped");
        // Thunderball 23 Sep 2026: 8 18 21 24 34 + TB 1 -> this ticket has 8, 34 and the Thunderball.
        List<TicketChecker.Result> tr = TicketChecker.check(tk, tbAll);
        check(tr.size() == 1 && tr.get(0).mainMatches == 2 && tr.get(0).extraMatches == 1
                && "Match 2 + Thunderball".equals(tr.get(0).tier) && tr.get(0).prizePence == 1000, "Thunderball: Match 2 + Thunderball wins £10");
        check(TicketChecker.winningsPence(tk, tr) == 1000 && TicketChecker.winningsPence(tk.withEnteredPrize(2500), tr) == 2500,
                "entered winnings override the worked-out prize");
        Ticket pending = new Ticket(2L, "thunderball", "2026-10-30", new int[]{1, 2, 3, 4, 5}, new int[]{1}, Ticket.LUCKY_DIP, 100, -1);
        check(TicketChecker.check(pending, tbAll).isEmpty(), "ticket for a future draw is pending");
        Ticket lotto2 = new Ticket(3L, "lotto", "2026-09-23", new int[]{1, 2, 3, 11, 12, 30}, new int[0], Ticket.OWN, 200, -1);
        List<TicketChecker.Result> lr = TicketChecker.check(lotto2, withRounds);
        check(lr.size() == 2 && "Match 3".equals(lr.get(0).tier) && "Match 2".equals(lr.get(1).tier)
                && TicketChecker.winningsPence(lotto2, lr) == 1100, "Lotto: each round checked (Match 3 £10 + Match 2 £1)");
        Ticket lottoBonus = new Ticket(4L, "lotto", "2026-09-23", new int[]{1, 2, 3, 4, 5, 7}, new int[0], Ticket.OWN, 200, -1);
        check("Match 5 + Bonus Ball".equals(TicketChecker.check(lottoBonus, withRounds).get(0).tier), "Lotto: bonus ball counted for Match 5");
        Ticket em = new Ticket(5L, "euromillions", "2026-09-22", new int[]{13, 14, 1, 2, 3}, new int[]{10, 1}, Ticket.LUCKY_DIP, 250, -1);
        List<TicketChecker.Result> er = TicketChecker.check(em, file("assets/euromillions.csv", Game.EUROMILLIONS));
        check(er.size() == 1 && "Match 2 + 1 Lucky Star".equals(er.get(0).tier) && TicketChecker.needsAmount(em, er),
                "EuroMillions: Match 2 + 1 Star is a win whose amount the player enters");
        Ticket sflT = new Ticket(6L, "set_for_life", "2026-09-21", new int[]{24, 28, 37, 1, 2}, new int[]{7}, Ticket.GENERATED, 150, -1);
        check(TicketChecker.check(sflT, file("assets/set_for_life.csv", Game.SET_FOR_LIFE)).get(0).prizePence == 3000, "Set For Life: Match 3 + Life Ball £30");
        List<Ticket> all = new ArrayList<>();
        all.add(tk); all.add(pending); all.add(lotto2);
        List<List<TicketChecker.Result>> res = new ArrayList<>();
        res.add(tr); res.add(TicketChecker.check(pending, tbAll)); res.add(lr);
        int[] tot = TicketChecker.totals(all, res);
        check(tot[0] == 3 && tot[1] == 400 && tot[2] == 2100 && tot[3] == 2 && tot[4] == 2, "totals: 3 tickets, £4 spent, £21 won");
        check(Ticket.money(123456).equals("£1,234.56") && Ticket.money(-250).equals("-£2.50") && Ticket.parsePence("£2.50") == 250
                && Ticket.parsePence("") == -1, "money formatting and parsing");
        check(DrawSchedule.nextDrawDate(Game.LOTTO, at(L0, "2026-10-03 12:00")).equals("2026-10-03")
                && DrawSchedule.nextDrawDate(Game.LOTTO, at(L0, "2026-10-03 22:00")).equals("2026-10-07"), "next Lotto draw for a ticket");
        List<String> around = DrawSchedule.drawDatesAround(Game.LOTTO, at(L0, "2026-10-03 12:00"), 2, 2);
        check(around.toString().equals("[2026-10-07, 2026-10-03, 2026-09-30, 2026-09-26]"), "draw dates for the ticket form: " + around);

        // Importing a ticket from screenshot text (OCR of a real National Lottery "Your ticket" screen).
        for (String engine : new String[]{"tess5", "tess3"}) {
            String ocr = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("test/fixtures/ocr_euromillions_" + engine + ".txt")), "UTF-8");
            TicketTextParser.Result pr = TicketTextParser.parse(ocr, Game.LOTTO);
            boolean ok = pr.game == Game.EUROMILLIONS && pr.lines.size() == 2 && pr.validLines() == 2
                    && java.util.Arrays.toString(pr.lines.get(0).main).equals("[16, 20, 30, 36, 49]")
                    && java.util.Arrays.toString(pr.lines.get(0).extra).equals("[3, 4]") && !pr.lines.get(0).luckyDip
                    && java.util.Arrays.toString(pr.lines.get(1).main).equals("[2, 14, 24, 35, 48]")
                    && java.util.Arrays.toString(pr.lines.get(1).extra).equals("[3, 10]") && pr.lines.get(1).luckyDip
                    && pr.drawDates.toString().equals("[2026-10-02, 2026-10-06]") && pr.costPerLinePence == 250;
            check(ok, "import: reads the EuroMillions ticket screenshot text (" + engine + "): game, 2 lines, Lucky Dip, "
                    + pr.drawDates + ", " + pr.costPerLinePence + "p");
        }
        TicketTextParser.Result lens = TicketTextParser.parse("Lotto\nDraw date: Sat 10 Oct 2026\n4 11 23 35 47 58\n", Game.EUROMILLIONS);
        check(lens.game == Game.LOTTO && lens.validLines() == 1 && lens.drawDates.toString().equals("[2026-10-10]"),
                "import: pasted text without Line labels");
        TicketTextParser.Result bad = TicketTextParser.parse("Thunderball\nLine 1: 08 18 21 24 34 01\nLine 2: 08 18 21 2\n"
                + "Draw summary: Tue 06 Oct 2026 to Sat 10 Oct 2026", Game.LOTTO);
        check(bad.game == Game.THUNDERBALL && bad.validLines() == 1 && bad.lines.get(1).problem != null
                && bad.drawDates.toString().equals("[2026-10-06, 2026-10-07, 2026-10-09, 2026-10-10]"),
                "import: unreadable line flagged; draw summary range expanded to draw days " + bad.drawDates);

        // Prize breakdown: exact odds from the rules match the published figures.
        String today = "2026-10-10";
        java.util.function.BiFunction<Game, String, Long> oneIn = new java.util.function.BiFunction<Game, String, Long>() {
            public Long apply(Game g, String tierName) {
                for (Prizes.Tier t : Prizes.tiers(g, today)) if (t.name.equals(tierName)) return (long) Math.ceil(1 / Prizes.probability(g, t) - 1e-6);
                return -1L;
            }
        };
        check(oneIn.apply(Game.EUROMILLIONS, "Match 5 + 2 Lucky Stars – jackpot") == 139_838_160L, "odds: EuroMillions jackpot 1 in 139,838,160");
        check(oneIn.apply(Game.LOTTO, "Match 6 – jackpot") == 45_057_474L && oneIn.apply(Game.LOTTO, "Match 5 + Bonus Ball") == 7_509_579L,
                "odds: Lotto jackpot 1 in 45,057,474, 5 + Bonus 1 in 7,509,579");
        check(oneIn.apply(Game.THUNDERBALL, "Match 5 + Thunderball") == 8_060_598L && oneIn.apply(Game.THUNDERBALL, "Match 4") == 3_648L
                && oneIn.apply(Game.THUNDERBALL, "Match 0 + Thunderball") == 29L, "odds: Thunderball tiers match published odds");
        check(oneIn.apply(Game.SET_FOR_LIFE, "Match 5 + Life Ball") == 15_339_390L && oneIn.apply(Game.SET_FOR_LIFE, "Match 2") == 15L
                && oneIn.apply(Game.SET_FOR_LIFE, "Match 4 + Life Ball") == 73_045L, "odds: Set For Life tiers match published odds");
        check(oneIn.apply(Game.POWERBALL, "Match 5 + Powerball – jackpot") == 292_201_338L, "odds: Powerball jackpot 1 in 292,201,338");
        for (Game g : Game.ALL) {
            System.out.println("  any prize, " + g.name + ": 1 in " + String.format("%.1f", 1 / Prizes.anyPrize(g, today)));
        }
        // Tickets saved by v2.4/2.5 still load unchanged.
        Ticket saved = Ticket.decode("1759401480000|euromillions|2026-10-06|2,14,24,35,48|3,10|Lucky Dip|250|-1");
        check(saved.isValid() && saved.encode().equals("1759401480000|euromillions|2026-10-06|2,14,24,35,48|3,10|Lucky Dip|250|-1"),
                "tickets saved by earlier versions load unchanged");

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
