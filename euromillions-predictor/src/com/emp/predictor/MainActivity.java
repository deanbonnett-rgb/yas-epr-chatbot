package com.emp.predictor;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Single-screen app with three tabs: Predict, Statistics and History. UI is built in code. */
public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#0B1437");
    private static final int CARD = Color.parseColor("#17245A");
    private static final int ACCENT = Color.parseColor("#3D7BFF");
    private static final int GOLD = Color.parseColor("#F5C518");
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.parseColor("#AAB4D4");

    private static final int MIN_LINES = 2;
    private static final int MAX_LINES = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ResultsStore store;
    private List<Draw> draws = new ArrayList<>();
    private Stats stats;

    private TextView status;
    private Button updateButton;
    private FrameLayout content;
    private final Button[] tabs = new Button[3];
    private int currentTab = 0;

    // Predict tab state, kept so switching tabs does not lose generated lines.
    private int strategy = Predictor.MIXED;
    private int lineCount = MIN_LINES;
    private boolean typicalOnly = true;
    private List<Predictor.Line> lines = new ArrayList<>();
    private boolean statsByFrequency = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = new ResultsStore(this);
        setContentView(buildShell());
        if (UpdateJobService.notificationsEnabled(this)) {
            askNotificationPermission(false);
            UpdateJobService.schedule(this);
        }
        status.setText("Loading results…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Draw> loaded = store.load();
                    main.post(new Runnable() {
                        @Override public void run() {
                            setDraws(loaded);
                            updateResults(false);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() { status.setText("Could not load results: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void setDraws(List<Draw> newDraws) {
        draws = newDraws;
        stats = new Stats(draws);
        status.setText(String.format(Locale.UK, "%,d draws · %s – %s", stats.totalDraws,
                prettyDate(stats.firstDate), prettyDate(stats.lastDate)));
        showTab(currentTab);
    }

    private void updateResults(boolean userAsked) {
        if (draws.isEmpty()) return;
        updateButton.setEnabled(false);
        updateButton.setText("Checking…");
        final List<Draw> before = draws;
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                List<Draw> result = null;
                try {
                    result = store.update(before);
                    int added = result.size() - before.size();
                    msg = added > 0 ? added + " new draw" + (added == 1 ? "" : "s") + " added" : "Results are up to date";
                } catch (Exception e) {
                    msg = "Update failed – check your connection";
                }
                final List<Draw> updated = result;
                final String message = msg;
                main.post(new Runnable() {
                    @Override public void run() {
                        updateButton.setEnabled(true);
                        updateButton.setText("Update results");
                        boolean grew = updated != null && updated.size() > before.size();
                        if (grew) {
                            lines.clear();
                            setDraws(updated);
                        }
                        if (userAsked || grew) Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- layout shell

    private View buildShell() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), 0);

        TextView title = text("EuroMillions Predictor", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        status = text("", 13, MUTED);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateButton = smallButton("Update results", CARD);
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateResults(true); }
        });
        statusRow.addView(updateButton);
        root.addView(statusRow, matchWrap(dp(4)));

        LinearLayout tabRow = horizontal();
        String[] names = {"Predict", "Statistics", "History"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tabs[i] = smallButton(names[i], CARD);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) lp.leftMargin = dp(6);
            tabRow.addView(tabs[i], lp);
        }
        root.addView(tabRow, matchWrap(dp(12)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void showTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < tabs.length; i++) tabs[i].setBackground(rounded(i == idx ? ACCENT : CARD, 10));
        content.removeAllViews();
        if (stats == null) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(0, dp(12), 0, dp(24));
        if (idx == 0) buildPredict(body);
        else if (idx == 1) buildStats(body);
        else buildHistory(body);
        scroll.addView(body);
        content.addView(scroll);
    }

    // ---------------------------------------------------------------- Predict tab

    private void buildPredict(LinearLayout body) {
        LinearLayout settings = card();
        settings.addView(text("Method", 13, MUTED));
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Predictor.STRATEGY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(strategy);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { strategy = pos; }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        settings.addView(spinner, matchWrap(0));

        LinearLayout countRow = horizontal();
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        countRow.addView(text("Number of lines", 15, TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button minus = smallButton("−", CARD);
        TextView count = text(String.valueOf(lineCount), 18, TEXT);
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(dp(40));
        Button plus = smallButton("+", CARD);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lineCount = Math.max(MIN_LINES, lineCount - 1); count.setText(String.valueOf(lineCount)); }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lineCount = Math.min(MAX_LINES, lineCount + 1); count.setText(String.valueOf(lineCount)); }
        });
        minus.setBackground(rounded(BG, 8));
        plus.setBackground(rounded(BG, 8));
        countRow.addView(minus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        countRow.addView(count);
        countRow.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        settings.addView(countRow, matchWrap(dp(8)));

        CheckBox typical = new CheckBox(this);
        typical.setText(String.format(Locale.UK, "Typical patterns only (ball total %d–%d, mixed odd/even)",
                stats.sumPercentile(0.05), stats.sumPercentile(0.95)));
        typical.setTextColor(TEXT);
        typical.setChecked(typicalOnly);
        typical.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) { typicalOnly = checked; }
        });
        settings.addView(typical, matchWrap(dp(4)));

        Button generate = bigButton("Generate predictions", ACCENT);
        generate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                lines = new Predictor(stats, new SecureRandom()).generate(lineCount, strategy, typicalOnly);
                showTab(0);
            }
        });
        settings.addView(generate, matchWrap(dp(12)));
        body.addView(settings, matchWrap(0));

        if (!lines.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) body.addView(lineCard(i + 1, lines.get(i)), matchWrap(dp(10)));
            Button copyAll = bigButton("Copy all lines", CARD);
            copyAll.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < lines.size(); i++) {
                        sb.append("Line ").append(i + 1).append(": ").append(lines.get(i).toClipboardText()).append('\n');
                    }
                    copy(sb.toString().trim(), "All " + lines.size() + " lines copied");
                }
            });
            body.addView(copyAll, matchWrap(dp(12)));
        }

        TextView note = text("How it works: every number is weighted by the full draw history (" + stats.totalDraws
                + " draws since " + prettyDate(stats.firstDate) + "), then lines are drawn at random using those weights. "
                + "“Rate” is how often the chosen numbers have appeared historically – a fair draw gives 10.0% for main "
                + "numbers and 16.7% for Lucky Stars.\n\nPlease note: every EuroMillions draw is random and independent. "
                + "Past results cannot change the odds – each line has the same 1 in 139,838,160 chance of the jackpot. "
                + "Play for fun and only spend what you can afford. BeGambleAware.org · 0808 8020 133", 12, MUTED);
        body.addView(note, matchWrap(dp(16)));
    }

    private View lineCard(int number, Predictor.Line line) {
        LinearLayout c = card();
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("Line " + number, 15, TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button copyBtn = smallButton("Copy", ACCENT);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(line.toClipboardText(), "Line " + number + " copied"); }
        });
        head.addView(copyBtn);
        c.addView(head);

        LinearLayout balls = horizontal();
        balls.setGravity(Gravity.CENTER_VERTICAL);
        for (int n : line.main) balls.addView(ball(n, false, 40), ballParams(40));
        View gap = new View(this);
        balls.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        for (int s : line.stars) balls.addView(ball(s, true, 40), ballParams(40));
        c.addView(balls, matchWrap(dp(10)));

        c.addView(text("Rate: main " + Predictor.percent(line.mainRate) + " · stars " + Predictor.percent(line.starRate),
                12, MUTED), matchWrap(dp(8)));
        return c;
    }

    private void copy(String value, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("EuroMillions numbers", value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- Statistics tab

    private void buildStats(LinearLayout body) {
        Button sort = bigButton(statsByFrequency ? "Sorted by frequency – tap to sort by number"
                : "Sorted by number – tap to sort by frequency", CARD);
        sort.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { statsByFrequency = !statsByFrequency; showTab(1); }
        });
        body.addView(sort, matchWrap(0));

        LinearLayout mainCard = card();
        mainCard.addView(sectionTitle("Main numbers (1–50)"));
        mainCard.addView(text("Chance of appearing in a draw, from all " + stats.totalDraws
                + " draws. A fair draw gives 10.0%.", 12, MUTED));
        List<Integer> order = new ArrayList<>();
        for (int n = 1; n <= Stats.MAIN_MAX; n++) order.add(n);
        if (statsByFrequency) Collections.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return stats.mainCount[b] - stats.mainCount[a]; }
        });
        double maxP = 0;
        for (int n = 1; n <= Stats.MAIN_MAX; n++) maxP = Math.max(maxP, stats.mainProbability(n));
        for (int n : order) {
            mainCard.addView(statRow(n, false, stats.mainProbability(n), maxP,
                    stats.mainCount[n] + " times · " + stats.mainRecent[n] + " in last " + Stats.RECENT_WINDOW
                            + " · last seen " + ago(stats.mainGap[n])), matchWrap(dp(8)));
        }
        body.addView(mainCard, matchWrap(dp(12)));

        LinearLayout starCard = card();
        starCard.addView(sectionTitle("Lucky Stars (1–12)"));
        starCard.addView(text("Adjusted for the star pool growing from 9 to 11 (2011) to 12 (2016), so 10, 11 and 12 "
                + "are only compared with draws they could appear in. A fair draw gives 16.7%.", 12, MUTED));
        List<Integer> sOrder = new ArrayList<>();
        for (int s = 1; s <= Stats.STAR_MAX; s++) sOrder.add(s);
        if (statsByFrequency) Collections.sort(sOrder, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Double.compare(stats.starProbability(b), stats.starProbability(a)); }
        });
        double maxS = 0;
        for (int s = 1; s <= Stats.STAR_MAX; s++) maxS = Math.max(maxS, stats.starProbability(s));
        for (int s : sOrder) {
            starCard.addView(statRow(s, true, stats.starProbability(s), maxS,
                    stats.starCount[s] + " times in " + stats.starEligible[s] + " draws · " + stats.starRecent[s]
                            + " in last " + Stats.RECENT_WINDOW + " · last seen " + ago(stats.starGap[s])), matchWrap(dp(8)));
        }
        body.addView(starCard, matchWrap(dp(12)));
    }

    private View statRow(int n, boolean star, double p, double maxP, String detail) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ball(n, star, 34), ballParams(34));

        LinearLayout right = vertical();
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(rounded(BG, 4));
        View bar = new View(this);
        bar.setBackground(rounded(star ? GOLD : ACCENT, 4));
        track.addView(bar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        track.post(new Runnable() {
            @Override public void run() {
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                lp.width = (int) (track.getWidth() * (maxP == 0 ? 0 : p / maxP));
                bar.setLayoutParams(lp);
            }
        });
        top.addView(track, new LinearLayout.LayoutParams(0, dp(10), 1));
        TextView pct = text(Predictor.percent(p), 14, TEXT);
        pct.setGravity(Gravity.END);
        pct.setMinWidth(dp(56));
        top.addView(pct);
        right.addView(top, matchWrap(0));
        right.addView(text(detail, 11, MUTED));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(10);
        row.addView(right, lp);
        return row;
    }

    // ---------------------------------------------------------------- History tab

    private void buildHistory(LinearLayout body) {
        LinearLayout notifyCard = card();
        CheckBox notify = new CheckBox(this);
        notify.setText("Notify me when new results are published");
        notify.setTextColor(TEXT);
        notify.setChecked(UpdateJobService.notificationsEnabled(this));
        notify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                UpdateJobService.setNotificationsEnabled(MainActivity.this, checked);
                if (checked) askNotificationPermission(true);
            }
        });
        notifyCard.addView(notify);
        notifyCard.addView(text("Checks after every Tuesday and Friday draw (results are usually out by 21:30 UK time) "
                + "and only goes online when a new draw is due.", 12, MUTED));
        Button test = smallButton("Send a test notification", BG);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askNotificationPermission(true);
                UpdateJobService.showResult(MainActivity.this, draws.get(draws.size() - 1), 1);
            }
        });
        notifyCard.addView(test, matchWrap(dp(8)));
        body.addView(notifyCard, matchWrap(0));

        body.addView(text("Most recent 100 draws (all " + stats.totalDraws + " are used for predictions)", 12, MUTED));
        for (int i = draws.size() - 1; i >= Math.max(0, draws.size() - 100); i--) {
            Draw d = draws.get(i);
            LinearLayout c = card();
            c.addView(text(prettyDate(d.date), 13, MUTED));
            LinearLayout balls = horizontal();
            for (int n : d.main) balls.addView(ball(n, false, 32), ballParams(32));
            balls.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            for (int s : d.stars) balls.addView(ball(s, true, 32), ballParams(32));
            c.addView(balls, matchWrap(dp(6)));
            body.addView(c, matchWrap(dp(8)));
        }
    }

    /** Android 13+ needs the user's permission before any notification can be shown. */
    private void askNotificationPermission(boolean force) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences(UpdateJobService.PREFS, MODE_PRIVATE);
        if (!force && prefs.getBoolean("asked_notifications", false)) return;
        prefs.edit().putBoolean("asked_notifications", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    // ---------------------------------------------------------------- view helpers

    private TextView ball(int n, boolean star, int sizeDp) {
        TextView t = text(String.valueOf(n), sizeDp > 36 ? 16 : 14, BG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(star ? GOLD : Color.WHITE);
        t.setBackground(g);
        return t;
    }

    private LinearLayout.LayoutParams ballParams(int sizeDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.rightMargin = dp(5);
        return lp;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = vertical();
        c.setBackground(rounded(CARD, 14));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        return c;
    }

    private Button smallButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setStateListAnimator(null);
        b.setBackground(rounded(color, 10));
        return b;
    }

    private Button bigButton(String label, int color) {
        Button b = smallButton(label, color);
        b.setTextSize(15);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String ago(int draws) {
        if (draws == 0) return "last draw";
        return draws + " draw" + (draws == 1 ? "" : "s") + " ago";
    }

    static String prettyDate(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
            return new SimpleDateFormat("EEE d MMM yyyy", Locale.UK).format(in.parse(iso));
        } catch (ParseException e) {
            return iso;
        }
    }
}
