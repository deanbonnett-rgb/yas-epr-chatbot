package com.emp.predictor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.provider.Settings;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
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
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;

/**
 * Opens on a game picker (EuroMillions, Lotto, Powerball). Each game has Predict, Statistics and
 * History tabs. The UI is built in code.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#0B1437");
    private static final int CARD = Color.parseColor("#17245A");
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.parseColor("#AAB4D4");
    private static final int GREEN = Color.parseColor("#2ECC71");

    private static final int MIN_LINES = 2;
    private static final int MAX_LINES = 10;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Selected game; null while the picker is showing. */
    private Game game;
    private ResultsStore store;
    private List<Draw> draws = new ArrayList<>();
    private Stats stats;

    private TextView status;
    private Button updateButton;
    private FrameLayout content;
    private final Button[] tabs = new Button[4];
    private int currentTab = 0;

    // Predict tab state, kept so switching tabs does not lose generated lines.
    private int strategy = Predictor.MIXED;
    private int lineCount = MIN_LINES;
    private boolean typicalOnly = true;
    private List<Predictor.Line> lines = new ArrayList<>();
    private boolean statsByFrequency = false;
    /** Line to float once the "display over other apps" permission comes back granted, or -1. */
    private int pendingFloat = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (UpdateJobService.anyNotificationsEnabled(this) || UpdateJobService.anyRemindersEnabled(this)) {
            askNotificationPermission(false);
        }
        UpdateJobService.schedule(this);
        Game requested = Game.byId(String.valueOf(getIntent().getStringExtra(UpdateJobService.EXTRA_GAME)));
        if (requested != null) openGame(requested); else showPicker();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Game requested = Game.byId(String.valueOf(intent.getStringExtra(UpdateJobService.EXTRA_GAME)));
        if (requested != null) openGame(requested);
    }

    @Override
    public void onBackPressed() {
        if (game != null) showPicker(); else super.onBackPressed();
    }

    // ---------------------------------------------------------------- game picker

    private void showPicker() {
        game = null;
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        TextView title = text("Lottery Predictor", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Choose a game. Predictions use every previous draw.", 14, MUTED), matchWrap(dp(4)));

        for (final Game g : Game.ALL) {
            LinearLayout c = card();
            c.setPadding(dp(18), dp(16), dp(18), dp(16));
            LinearLayout head = horizontal();
            head.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(g.accent);
            dot.setBackground(dd);
            head.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
            TextView name = text(g.name, 22, TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nlp.leftMargin = dp(10);
            head.addView(name, nlp);
            head.addView(text("›", 26, MUTED));
            c.addView(head);
            c.addView(text(g.country + " · " + g.formatDescription(), 14, TEXT), matchWrap(dp(6)));
            final TextView info = text("Draws " + g.drawDaysText(), 13, MUTED);
            c.addView(info, matchWrap(dp(2)));
            if (g.drawNote != null) c.addView(text(g.drawNote, 13, MUTED), matchWrap(dp(2)));
            if (g.historyNote != null) c.addView(text(g.historyNote, 12, MUTED), matchWrap(dp(2)));
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openGame(g); }
            });
            root.addView(c, matchWrap(dp(16)));
            loadSummary(g, info);
        }
        final TextView ticketSummary = text("My tickets: loading…", 14, TEXT);
        LinearLayout tc = card();
        tc.addView(sectionTitle("My tickets"));
        tc.addView(ticketSummary, matchWrap(dp(4)));
        tc.addView(text("Open a game's Tickets tab to add or check tickets.", 12, MUTED), matchWrap(dp(2)));
        root.addView(tc, matchWrap(dp(20)));
        loadTicketSummary(ticketSummary);
        root.addView(remindersCard(), matchWrap(dp(16)));
        root.addView(text("Draws are random – no method can change the odds. Play for fun and only spend what you can afford.",
                12, MUTED), matchWrap(dp(20)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** Picker card for "draw tomorrow" reminders: which games, and at what time. */
    private View remindersCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("Reminders"));
        c.addView(text("A notification the day before each draw of the games you tick, with a suggested line.", 12, MUTED));

        LinearLayout timeRow = horizontal();
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.addView(text("Reminder time", 15, TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button time = smallButton(hourText(UpdateJobService.reminderHour(this)), BG);
        time.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker p, int hour, int minute) {
                        // Background checks sleep 11pm-7am, so keep reminders inside the day.
                        int h = Math.max(DrawSchedule.QUIET_UNTIL_HOUR, Math.min(DrawSchedule.QUIET_FROM_HOUR - 1, hour));
                        if (h != hour) Toast.makeText(MainActivity.this, "Reminders are sent between 7am and 10pm", Toast.LENGTH_SHORT).show();
                        UpdateJobService.setReminderHour(MainActivity.this, h);
                        time.setText(hourText(h));
                    }
                }, UpdateJobService.reminderHour(MainActivity.this), 0, true).show();
            }
        });
        timeRow.addView(time);
        c.addView(timeRow, matchWrap(dp(8)));

        for (final Game g : Game.ALL) {
            CheckBox box = new CheckBox(this);
            box.setText(g.name + " – day before " + g.drawDaysText() + " draws");
            box.setTextColor(TEXT);
            box.setChecked(UpdateJobService.reminderEnabled(this, g));
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                    UpdateJobService.setReminderEnabled(MainActivity.this, g, checked);
                    if (checked) askNotificationPermission(true);
                }
            });
            c.addView(box, matchWrap(dp(2)));
        }
        return c;
    }

    private static String hourText(int hour) {
        return String.format(Locale.UK, "%02d:00", hour);
    }

    /** Fills in each picker card's draw count and latest draw date. */
    private void loadSummary(final Game g, final TextView info) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Draw> d = new ResultsStore(MainActivity.this, g).load();
                    main.post(new Runnable() {
                        @Override public void run() {
                            info.setText(String.format(Locale.UK, "Draws %s · %,d results since %s · latest %s", g.drawDaysText(),
                                    d.size(), d.get(0).date.substring(0, 4), prettyDate(d.get(d.size() - 1).date)));
                        }
                    });
                } catch (Exception ignored) {
                    // The card still works; the game screen reports load errors.
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- game screen

    private void openGame(final Game g) {
        game = g;
        store = new ResultsStore(this, g);
        draws = new ArrayList<>();
        stats = null;
        lines = new ArrayList<>();
        currentTab = 0;
        setContentView(buildShell());
        status.setText("Loading results…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Draw> loaded = store.load();
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (game != g) return;
                            setDraws(loaded);
                            if (store.updateWanted(loaded, System.currentTimeMillis())) updateResults(false);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() { if (game == g) status.setText("Could not load results: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void setDraws(List<Draw> newDraws) {
        draws = newDraws;
        stats = new Stats(game, draws);
        status.setText(String.format(Locale.UK, "%,d draws · %s – %s", stats.totalDraws,
                prettyDate(stats.firstDate), prettyDate(stats.lastDate)));
        showTab(currentTab);
    }

    private void updateResults(final boolean userAsked) {
        if (draws.isEmpty()) return;
        final Game g = game;
        final ResultsStore s = store;
        updateButton.setEnabled(false);
        updateButton.setText("Checking…");
        final List<Draw> before = draws;
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                List<Draw> result = null;
                try {
                    result = s.update(before);
                    int added = result.size() - before.size();
                    msg = added > 0 ? added + " new draw" + (added == 1 ? "" : "s") + " added" : "Results are up to date";
                } catch (Exception e) {
                    msg = "Update failed – check your connection";
                }
                final List<Draw> updated = result;
                final String message = msg;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (game != g) return;
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

    private View buildShell() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), 0);

        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        Button back = smallButton("‹ Games", CARD);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPicker(); }
        });
        titleRow.addView(back);
        TextView title = text(game.name, 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(12);
        titleRow.addView(title, tlp);
        root.addView(titleRow);

        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        status = text("", 13, MUTED);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateButton = smallButton("Update results", CARD);
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateResults(true); }
        });
        statusRow.addView(updateButton);
        root.addView(statusRow, matchWrap(dp(8)));

        LinearLayout tabRow = horizontal();
        String[] names = {"Predict", "Stats", "History", "Tickets"};
        for (int i = 0; i < names.length; i++) {
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
        for (int i = 0; i < tabs.length; i++) tabs[i].setBackground(rounded(i == idx ? game.accent : CARD, 10));
        content.removeAllViews();
        if (stats == null) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(0, dp(12), 0, dp(24));
        if (idx == 0) buildPredict(body);
        else if (idx == 1) buildStats(body);
        else if (idx == 2) buildHistory(body);
        else buildTickets(body);
        scroll.addView(body);
        content.addView(scroll);
    }

    // ---------------------------------------------------------------- Predict tab

    private void buildPredict(LinearLayout body) {
        LinearLayout settings = card();
        settings.addView(text(game.formatDescription(), 15, TEXT));
        settings.addView(text("Method", 13, MUTED), matchWrap(dp(10)));
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
        Button minus = smallButton("−", BG);
        final TextView count = text(String.valueOf(lineCount), 18, TEXT);
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(dp(40));
        Button plus = smallButton("+", BG);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lineCount = Math.max(MIN_LINES, lineCount - 1); count.setText(String.valueOf(lineCount)); }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lineCount = Math.min(MAX_LINES, lineCount + 1); count.setText(String.valueOf(lineCount)); }
        });
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

        Button generate = bigButton("Generate " + game.name + " numbers", game.accent);
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
                    StringBuilder sb = new StringBuilder(game.name).append('\n');
                    for (int i = 0; i < lines.size(); i++) {
                        sb.append("Line ").append(i + 1).append(": ").append(lines.get(i).toClipboardText()).append('\n');
                    }
                    copy(sb.toString().trim(), "All " + lines.size() + " lines copied");
                }
            });
            body.addView(copyAll, matchWrap(dp(12)));
            Button floatAll = bigButton("Show numbers over the Lottery app", game.accent);
            floatAll.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showFloating(0); }
            });
            body.addView(floatAll, matchWrap(dp(8)));
            body.addView(text("The National Lottery app takes one number per box. Tap any ball above to copy just that "
                    + "number, or float the numbers over the Lottery app and tap them there as you fill each box.", 12, MUTED),
                    matchWrap(dp(6)));
        }

        String fair = "a fair draw gives " + Predictor.percent(stats.main.fairProbability()) + " for main numbers";
        if (game.extraPicked) fair += " and " + Predictor.percent(stats.extra.fairProbability()) + " for the " + game.extraName;
        String help = "BeGambleAware.org · 0808 8020 133";
        TextView note = text("How it works: every number is weighted by the full draw history (" + stats.totalDraws
                + " draws since " + prettyDate(stats.firstDate) + "), then lines are drawn at random using those weights. "
                + "“Rate” is how often the chosen numbers have appeared historically – " + fair + ".\n\n"
                + "Please note: every " + game.name + " draw is random and independent. Past results cannot change the odds – "
                + "each line has the same " + game.jackpotOdds + " chance of the jackpot. "
                + "Play for fun and only spend what you can afford. " + help, 12, MUTED);
        body.addView(note, matchWrap(dp(16)));
    }

    private View lineCard(final int number, final Predictor.Line line) {
        LinearLayout c = card();
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("Line " + number, 15, TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button floatBtn = smallButton("Float", CARD);
        floatBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFloating(number - 1); }
        });
        head.addView(floatBtn);
        Button copyBtn = smallButton("Copy", game.accent);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(line.toClipboardText(), "Line " + number + " copied"); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        head.addView(copyBtn, clp);
        c.addView(head);

        int size = game.mainCount + line.extra.length > 7 ? 34 : 38;
        LinearLayout balls = horizontal();
        balls.setGravity(Gravity.CENTER_VERTICAL);
        for (int n : line.main) balls.addView(copyableBall(n, false, size), ballParams(size));
        if (line.extra.length > 0) {
            balls.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
            for (int e : line.extra) balls.addView(copyableBall(e, true, size), ballParams(size));
        }
        c.addView(balls, matchWrap(dp(10)));

        String rate = "Rate: main " + Predictor.percent(line.mainRate);
        if (line.extra.length > 0) rate += " · " + game.extraName + " " + Predictor.percent(line.extraRate);
        c.addView(text(rate, 12, MUTED), matchWrap(dp(8)));

        final String drawDate = DrawSchedule.nextDrawDate(game, System.currentTimeMillis());
        Ticket logged = TicketStore.find(this, game, drawDate, line.main, line.extra);
        if (logged != null) {
            TextView played = text("✓ Played for " + prettyDate(drawDate) + " – it's in your Tickets", 13, GREEN);
            c.addView(played, matchWrap(dp(8)));
        } else {
            Button play = smallButton("I played this line (" + prettyDate(drawDate) + ")", BG);
            play.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        TicketStore.save(MainActivity.this, new Ticket(System.currentTimeMillis(), game.id, drawDate,
                                line.main, line.extra, Ticket.GENERATED, defaultCost(game), -1));
                        Toast.makeText(MainActivity.this, "Saved to Tickets – it'll be checked when results are out", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Could not save ticket", Toast.LENGTH_SHORT).show();
                    }
                    showTab(0);
                }
            });
            c.addView(play, matchWrap(dp(8)));
        }
        return c;
    }

    /** A ball that copies just its own number: ticket apps take one number per box. */
    private TextView copyableBall(final int n, boolean extra, int size) {
        TextView b = ball(n, extra, size);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(String.valueOf(n), n + " copied"); }
        });
        return b;
    }

    /** Floats the generated lines over other apps, asking for the permission first if needed. */
    private void showFloating(int index) {
        if (lines.isEmpty()) return;
        if (!FloatingNumbers.canShow(this)) {
            pendingFloat = index;
            new AlertDialog.Builder(this)
                    .setTitle("Show numbers over other apps")
                    .setMessage("The National Lottery app takes one number per box, so a whole line can't be pasted in one go. "
                            + "This shows your numbers in a small panel on top of it: tap a number to copy it, then paste "
                            + "it into the next box (or just type what you see).\n\n"
                            + "On the next screen, turn on \"Allow display over other apps\" for Lottery Predictor, then come back.")
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (RuntimeException e) {
                                Toast.makeText(MainActivity.this, "Open Settings › Apps › Lottery Predictor › Display over other apps",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        FloatingNumbers.show(this, game, lines, index);
        Toast.makeText(this, "Numbers are floating – now open The National Lottery app", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingFloat >= 0 && game != null && !lines.isEmpty() && FloatingNumbers.canShow(this)) {
            int index = Math.min(pendingFloat, lines.size() - 1);
            pendingFloat = -1;
            showFloating(index);
        }
    }

    private void copy(String value, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(game.name + " numbers", value));
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

        boolean poolsChanged = !stats.firstDate.isEmpty() && !game.isCurrentEra(stats.firstDate);
        String eraNote = poolsChanged
                ? " The rules have changed over the years, so each number is only compared with draws it could appear in." : "";
        if (game.historyNote != null) eraNote += " " + game.historyNote + ".";
        body.addView(ballSection("Main numbers (1–" + stats.main.pool + ")", stats.main, false,
                "Chance of appearing in a draw, from all " + stats.totalDraws + " draws. A fair draw gives "
                        + Predictor.percent(stats.main.fairProbability()) + "." + eraNote), matchWrap(dp(12)));
        if (game.extraPicked) {
            body.addView(ballSection(game.extraName + " (1–" + stats.extra.pool + ")", stats.extra, true,
                    "A fair draw gives " + Predictor.percent(stats.extra.fairProbability()) + "." + eraNote), matchWrap(dp(12)));
        }
    }

    private View ballSection(String title, final Stats.Balls b, boolean extra, String intro) {
        LinearLayout c = card();
        c.addView(sectionTitle(title));
        c.addView(text(intro, 12, MUTED));
        List<Integer> order = new ArrayList<>();
        for (int n = 1; n <= b.pool; n++) order.add(n);
        if (statsByFrequency) {
            Collections.sort(order, new Comparator<Integer>() {
                @Override public int compare(Integer x, Integer y) { return Double.compare(b.probability(y), b.probability(x)); }
            });
        }
        double maxP = 0;
        for (int n = 1; n <= b.pool; n++) maxP = Math.max(maxP, b.probability(n));
        for (int n : order) {
            String detail = b.count[n] + " times";
            if (b.eligible[n] < stats.totalDraws) detail += " in " + b.eligible[n] + " draws";
            detail += " · " + b.recent[n] + " in last " + Stats.RECENT_WINDOW + " · last seen " + ago(b.gap[n]);
            c.addView(statRow(n, extra, b.probability(n), maxP, detail), matchWrap(dp(8)));
        }
        return c;
    }

    private View statRow(int n, boolean extra, final double p, final double maxP, String detail) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ball(n, extra, 34), ballParams(34));

        LinearLayout right = vertical();
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        final FrameLayout track = new FrameLayout(this);
        track.setBackground(rounded(BG, 4));
        final View bar = new View(this);
        bar.setBackground(rounded(extra ? game.extraColor : game.accent, 4));
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

    // ---------------------------------------------------------------- Tickets tab

    private int defaultCost(Game g) {
        return getSharedPreferences(UpdateJobService.PREFS, MODE_PRIVATE).getInt("cost_" + g.id, g.pricePence);
    }

    private void buildTickets(LinearLayout body) {
        List<Ticket> tickets = TicketStore.forGame(this, game);
        List<List<TicketChecker.Result>> results = new ArrayList<>();
        for (Ticket t : tickets) results.add(TicketChecker.check(t, draws));
        int[] tot = TicketChecker.totals(tickets, results);

        LinearLayout sum = card();
        sum.addView(sectionTitle(game.name + " tickets"));
        sum.addView(text(String.format(Locale.UK, "%d ticket%s · spent %s · won %s", tot[0], tot[0] == 1 ? "" : "s",
                Ticket.money(tot[1]), Ticket.money(tot[2])), 14, TEXT), matchWrap(dp(4)));
        int net = tot[2] - tot[1];
        sum.addView(text((net >= 0 ? "Up " : "Down ") + Ticket.money(Math.abs(net)) + " · " + tot[4] + " winning ticket"
                + (tot[4] == 1 ? "" : "s") + " · " + (tot[0] - tot[3]) + " waiting for results", 13, net >= 0 ? GREEN : MUTED), matchWrap(dp(2)));
        Button add = bigButton("Add a ticket (Lucky Dip or your own numbers)", game.accent);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTicketForm(); }
        });
        sum.addView(add, matchWrap(dp(10)));
        sum.addView(text("Generated lines can be saved with “I played this line” on the Predict tab. Tickets are checked "
                + "automatically when results are in; fixed prizes are filled in, and you can enter any amount you won.", 12, MUTED),
                matchWrap(dp(6)));
        body.addView(sum, matchWrap(0));

        for (int i = 0; i < tickets.size(); i++) body.addView(ticketCard(tickets.get(i), results.get(i)), matchWrap(dp(10)));
    }

    private View ticketCard(final Ticket t, List<TicketChecker.Result> results) {
        LinearLayout c = card();
        c.addView(text(prettyDate(t.drawDate) + " · " + t.source + " · " + (t.costPence >= 0 ? Ticket.money(t.costPence) : "cost not set"),
                13, MUTED));

        Set<Integer> hitMain = new HashSet<>(), hitExtra = new HashSet<>();
        for (TicketChecker.Result r : results) {
            for (int n : t.main) {
                for (int x : r.draw.main) if (x == n) hitMain.add(n);
                if (!game.extraPicked) for (int x : r.draw.extra) if (x == n) hitMain.add(n);
            }
            for (int e : t.extra) for (int x : r.draw.extra) if (x == e) hitExtra.add(e);
        }
        int size = game.mainCount + t.extra.length > 7 ? 32 : 36;
        LinearLayout balls = horizontal();
        for (int n : t.main) balls.addView(markedBall(n, false, size, hitMain.contains(n)), ballParams(size));
        if (t.extra.length > 0) {
            balls.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
            for (int e : t.extra) balls.addView(markedBall(e, true, size, hitExtra.contains(e)), ballParams(size));
        }
        c.addView(balls, matchWrap(dp(6)));

        if (results.isEmpty()) {
            c.addView(text("Waiting for the " + prettyDate(t.drawDate) + " results", 14, TEXT), matchWrap(dp(6)));
        } else {
            for (int i = 0; i < results.size(); i++) {
                TicketChecker.Result r = results.get(i);
                String prefix = results.size() > 1 ? "Round " + (i + 1) + ": " : "";
                String line;
                if (!r.isWin()) {
                    line = prefix + "no win (matched " + r.mainMatches + (game.extraPicked ? " + " + r.extraMatches : "") + ")";
                } else if (r.prizeNote != null) {
                    line = prefix + r.tier + " – " + r.prizeNote;
                } else if (r.prizePence == TicketChecker.VARIABLE) {
                    line = prefix + r.tier + " – prize varies, check the official results";
                } else {
                    line = prefix + r.tier + " – " + Ticket.money(r.prizePence);
                }
                c.addView(text(line, 14, r.isWin() ? GREEN : TEXT), matchWrap(dp(4)));
            }
            int won = TicketChecker.winningsPence(t, results);
            String total = t.enteredPrizePence >= 0 ? "Winnings entered: " + Ticket.money(won)
                    : TicketChecker.needsAmount(t, results) ? "Tap “Winnings” to enter what you won" : "Winnings: " + Ticket.money(won);
            c.addView(text(total, 13, won > 0 ? GREEN : MUTED), matchWrap(dp(4)));
        }

        LinearLayout actions = horizontal();
        Button winnings = smallButton("Winnings", BG);
        winnings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showWinningsDialog(t); }
        });
        actions.addView(winnings);
        Button delete = smallButton("Delete", BG);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Delete this ticket from your log?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                try {
                                    TicketStore.delete(MainActivity.this, t.id);
                                } catch (Exception ignored) {
                                    // Leave it; the list is redrawn either way.
                                }
                                showTab(3);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(8);
        actions.addView(delete, dlp);
        c.addView(actions, matchWrap(dp(8)));
        return c;
    }

    private TextView markedBall(int n, boolean extra, int size, boolean hit) {
        TextView b = ball(n, extra, size);
        if (hit) {
            GradientDrawable g = (GradientDrawable) b.getBackground();
            g.setStroke(dp(4), GREEN);
        }
        return b;
    }

    private void showWinningsDialog(final Ticket t) {
        final EditText amount = new EditText(this);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setHint("e.g. 4.20");
        if (t.enteredPrizePence >= 0) amount.setText(String.format(Locale.UK, "%.2f", t.enteredPrizePence / 100.0));
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(amount);
        new AlertDialog.Builder(this)
                .setTitle("Winnings for this ticket (£)")
                .setMessage("Enter the total this ticket won. Leave it empty to use the prizes worked out from the results.")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            TicketStore.save(MainActivity.this, t.withEnteredPrize(Ticket.parsePence(amount.getText().toString())));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Could not save", Toast.LENGTH_SHORT).show();
                        }
                        showTab(3);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Form for logging a Lucky Dip or your own numbers: draw date, how chosen, numbers and cost. */
    private void showTicketForm() {
        final Game g = game;
        final int extraCount = g.extraPicked ? g.extraCount : 0;
        final Set<Integer> mainSel = new TreeSet<>(), extraSel = new TreeSet<>();
        LinearLayout form = vertical();
        form.setPadding(dp(16), dp(8), dp(16), dp(8));

        form.addView(text("Draw", 13, MUTED));
        final List<String> dates = DrawSchedule.drawDatesAround(g, System.currentTimeMillis(), 3, 8);
        String next = DrawSchedule.nextDrawDate(g, System.currentTimeMillis());
        List<String> dateLabels = new ArrayList<>();
        for (String d : dates) dateLabels.add(prettyDate(d) + (d.equals(next) ? " (next draw)" : ""));
        final Spinner date = new Spinner(this);
        date.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, dateLabels));
        date.setSelection(dates.indexOf(next));
        form.addView(date);

        form.addView(text("How were the numbers chosen?", 13, MUTED), matchWrap(dp(8)));
        final String[] sources = {Ticket.LUCKY_DIP, Ticket.OWN, Ticket.GENERATED};
        final Spinner source = new Spinner(this);
        source.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sources));
        form.addView(source);

        form.addView(text("Tap your " + g.mainCount + " numbers (1–" + g.currentMainPool() + ")", 13, MUTED), matchWrap(dp(8)));
        form.addView(numberGrid(g.currentMainPool(), g.mainCount, mainSel, false));
        if (extraCount > 0) {
            form.addView(text("Tap your " + (extraCount == 1 ? g.extraName : extraCount + " " + g.extraName)
                    + " (1–" + g.currentExtraPool() + ")", 13, MUTED), matchWrap(dp(8)));
            form.addView(numberGrid(g.currentExtraPool(), extraCount, extraSel, true));
        }

        form.addView(text("Cost of this line (£)", 13, MUTED), matchWrap(dp(8)));
        final EditText cost = new EditText(this);
        cost.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        cost.setText(String.format(Locale.UK, "%.2f", defaultCost(g) / 100.0));
        form.addView(cost);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add a " + g.name + " ticket")
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (mainSel.size() != g.mainCount || extraSel.size() != extraCount) {
                            Toast.makeText(MainActivity.this, "Pick " + g.mainCount + " numbers"
                                    + (extraCount > 0 ? " and " + extraCount + " " + g.extraName : ""), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int pence = Ticket.parsePence(cost.getText().toString());
                        Ticket t = new Ticket(System.currentTimeMillis(), g.id, dates.get(date.getSelectedItemPosition()),
                                toArray(mainSel), toArray(extraSel), sources[source.getSelectedItemPosition()], pence, -1);
                        try {
                            TicketStore.save(MainActivity.this, t);
                            if (pence >= 0) getSharedPreferences(UpdateJobService.PREFS, MODE_PRIVATE).edit().putInt("cost_" + g.id, pence).apply();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Could not save ticket", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        dialog.dismiss();
                        showTab(3);
                    }
                });
            }
        });
        dialog.show();
    }

    /** Rows of tappable numbers 1..pool; at most {@code max} can be selected. */
    private View numberGrid(int pool, final int max, final Set<Integer> selected, final boolean extra) {
        LinearLayout grid = vertical();
        LinearLayout row = null;
        for (int n = 1; n <= pool; n++) {
            if ((n - 1) % 8 == 0) {
                row = horizontal();
                grid.addView(row, matchWrap(dp(4)));
            }
            final int num = n;
            final TextView cell = text(String.valueOf(n), 14, TEXT);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(rounded(CARD, 18));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (selected.contains(num)) {
                        selected.remove(num);
                        cell.setBackground(rounded(CARD, 18));
                        cell.setTextColor(TEXT);
                    } else if (selected.size() < max) {
                        selected.add(num);
                        cell.setBackground(rounded(extra ? game.extraColor : Color.WHITE, 18));
                        cell.setTextColor(extra ? Color.WHITE : BG);
                    } else {
                        Toast.makeText(MainActivity.this, "You've picked " + max + " already – tap one to remove it", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(4);
            row.addView(cell, lp);
        }
        return grid;
    }

    private static int[] toArray(Set<Integer> set) {
        int[] a = new int[set.size()];
        int i = 0;
        for (int n : set) a[i++] = n;
        return a;
    }

    /** Fills in the picker's "My tickets" card: totals across every game. */
    private void loadTicketSummary(final TextView view) {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Ticket> all = TicketStore.load(MainActivity.this);
                List<List<TicketChecker.Result>> results = new ArrayList<>();
                java.util.Map<String, List<Draw>> cache = new java.util.HashMap<>();
                for (Ticket t : all) {
                    List<Draw> d = cache.get(t.gameId);
                    if (d == null) {
                        try {
                            d = new ResultsStore(MainActivity.this, t.game()).load();
                        } catch (Exception e) {
                            d = new ArrayList<>();
                        }
                        cache.put(t.gameId, d);
                    }
                    results.add(TicketChecker.check(t, d));
                }
                final int[] tot = TicketChecker.totals(all, results);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (tot[0] == 0) {
                            view.setText("No tickets logged yet");
                            return;
                        }
                        int net = tot[2] - tot[1];
                        view.setText(String.format(Locale.UK, "%d ticket%s · spent %s · won %s · %s %s", tot[0], tot[0] == 1 ? "" : "s",
                                Ticket.money(tot[1]), Ticket.money(tot[2]), net >= 0 ? "up" : "down", Ticket.money(Math.abs(net))));
                    }
                });
            }
        }).start();
    }

    private void buildHistory(LinearLayout body) {
        final Game g = game;
        LinearLayout notifyCard = card();
        CheckBox notify = new CheckBox(this);
        notify.setText("Notify me when new " + g.name + " results are published");
        notify.setTextColor(TEXT);
        notify.setChecked(UpdateJobService.notificationsEnabled(this, g));
        notify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                UpdateJobService.setNotificationsEnabled(MainActivity.this, g, checked);
                if (checked) askNotificationPermission(true);
            }
        });
        notifyCard.addView(notify);
        notifyCard.addView(text("Checks after every " + g.drawDaysText() + " draw and only goes online when a new draw is due. "
                + "No checks between 11pm and 7am, so overnight results arrive in the morning.", 12, MUTED));
        if (g.drawNote != null) notifyCard.addView(text(g.drawNote + ".", 12, MUTED));
        Button test = smallButton("Send a test notification", BG);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askNotificationPermission(true);
                UpdateJobService.showResult(MainActivity.this, g, UpdateJobService.latestNight(draws));
            }
        });
        notifyCard.addView(test, matchWrap(dp(8)));

        CheckBox remind = new CheckBox(this);
        remind.setText("Remind me the day before each " + g.name + " draw");
        remind.setTextColor(TEXT);
        remind.setChecked(UpdateJobService.reminderEnabled(this, g));
        remind.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                UpdateJobService.setReminderEnabled(MainActivity.this, g, checked);
                if (checked) askNotificationPermission(true);
            }
        });
        notifyCard.addView(remind, matchWrap(dp(12)));
        notifyCard.addView(text("Sent at about " + hourText(UpdateJobService.reminderHour(this))
                + " with a suggested line. Change the time on the games screen.", 12, MUTED));
        Button testReminder = smallButton("Send a test reminder", BG);
        testReminder.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askNotificationPermission(true);
                try {
                    UpdateJobService.showTestReminder(MainActivity.this, g);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not create reminder", Toast.LENGTH_SHORT).show();
                }
            }
        });
        notifyCard.addView(testReminder, matchWrap(dp(8)));
        body.addView(notifyCard, matchWrap(0));

        body.addView(text("Most recent 100 draws (all " + stats.totalDraws + " are used for predictions)", 12, MUTED),
                matchWrap(dp(12)));
        int size = game.mainCount + game.extraCount > 7 ? 30 : 32;
        for (int i = draws.size() - 1; i >= Math.max(0, draws.size() - 100); i--) {
            Draw d = draws.get(i);
            LinearLayout c = card();
            c.addView(text(prettyDate(d.date), 13, MUTED));
            LinearLayout balls = horizontal();
            for (int n : d.main) balls.addView(ball(n, false, size), ballParams(size));
            balls.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
            for (int e : d.extra) balls.addView(ball(e, true, size), ballParams(size));
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

    private TextView ball(int n, boolean extra, int sizeDp) {
        int fill = extra ? game.extraColor : Color.WHITE;
        boolean darkFill = extra && Color.red(fill) * 0.3 + Color.green(fill) * 0.59 + Color.blue(fill) * 0.11 < 150;
        TextView t = text(String.valueOf(n), sizeDp > 34 ? 16 : 14, darkFill ? Color.WHITE : BG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        t.setBackground(g);
        return t;
    }

    private LinearLayout.LayoutParams ballParams(int sizeDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.rightMargin = dp(4);
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
