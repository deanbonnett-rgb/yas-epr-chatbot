import com.emp.predictor.Draw;
import com.emp.predictor.DrawParser;
import com.emp.predictor.Predictor;
import com.emp.predictor.Stats;

import java.io.FileReader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Plain-JVM checks for the parsing, statistics and prediction logic. Run via test/run.sh. */
public class LogicTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        List<Draw> draws = DrawParser.parse(new FileReader(args[0]));
        check(draws.size() >= 1983, "bundled history loads (" + draws.size() + " draws)");
        check(draws.get(0).date.equals("2004-02-13"), "history starts with the first draw");
        Set<String> dates = new HashSet<>();
        for (Draw d : draws) dates.add(d.date);
        check(dates.size() == draws.size(), "no duplicate draw dates");

        Stats st = new Stats(draws);
        int mainTotal = 0;
        for (int n = 1; n <= 50; n++) mainTotal += st.mainCount[n];
        check(mainTotal == draws.size() * 5, "every main ball counted");
        double pSum = 0;
        for (int s = 1; s <= 12; s++) pSum += st.starProbability(s);
        check(Math.abs(pSum - 2.0) < 1e-9, "star probabilities sum to 2 per draw");
        check(st.starEligible[12] < st.starEligible[10] && st.starEligible[10] < st.starEligible[1],
                "stars 10-12 only judged on draws where they existed");

        // National Lottery download format.
        String nl = "DrawDate,Ball 1,Ball 2,Ball 3,Ball 4,Ball 5,Lucky Star 1,Lucky Star 2,UK Millionaire Maker,DrawNumber\n"
                + "25-Sep-2026,1,9,20,33,47,4,12,\"ABC12345\",1984\n"
                + "22-Sep-2026,13,14,16,44,50,10,12,\"XYZ\",1983\n";
        List<Draw> fresh = DrawParser.parse(new StringReader(nl));
        check(fresh.size() == 2 && fresh.get(0).date.equals("2026-09-25"), "parses National Lottery CSV");
        List<Draw> merged = DrawParser.appendNewer(draws, fresh);
        check(merged.size() == draws.size() + 1, "merge only appends newer draws");
        check(merged.get(merged.size() - 1).date.equals("2026-09-25"), "merged history ends with newest draw");

        Predictor p = new Predictor(st, new Random(42));
        for (int strat = 0; strat < Predictor.STRATEGY_NAMES.length; strat++) {
            List<Predictor.Line> lines = p.generate(10, strat, true);
            boolean ok = lines.size() == 10;
            Set<String> keys = new HashSet<>();
            for (Predictor.Line l : lines) {
                ok &= new Draw("2026-09-25", l.main, l.stars).isValid();
                keys.add(l.toClipboardText());
            }
            check(ok && keys.size() == 10, "strategy " + strat + " gives 10 valid distinct lines, e.g. "
                    + lines.get(0).toClipboardText());
        }

        // Weighted sampling should favour heavier numbers.
        double[] w = p.mainWeights(Predictor.HOT);
        int best = 1, worst = 1;
        for (int n = 1; n <= 50; n++) {
            if (w[n] > w[best]) best = n;
            if (w[n] < w[worst]) worst = n;
        }
        int[] hits = new int[51];
        for (Predictor.Line l : p.generate(4000, Predictor.HOT, false)) for (int n : l.main) hits[n]++;
        check(hits[best] > hits[worst], "hot strategy picks most frequent number (" + best + ": " + hits[best]
                + ") more than least frequent (" + worst + ": " + hits[worst] + ")");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
