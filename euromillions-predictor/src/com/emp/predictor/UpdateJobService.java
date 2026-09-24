package com.emp.predictor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * Runs about once an hour (except overnight). Sends "draw tomorrow" reminders for the games
 * chosen, and for each game with result notifications on, goes online only when a draw should
 * have been published but isn't stored yet, then posts the result.
 */
public class UpdateJobService extends JobService {
    private static final int JOB_ID = 1002;
    /** Earlier versions' job, which only ran with a network connection. */
    private static final int OLD_JOB_ID = 1001;
    private static final long PERIOD_MS = 60 * 60 * 1000L;
    private static final String CHANNEL_ID = "results";

    static final String PREFS = "settings";
    static final String EXTRA_GAME = "game";

    private volatile boolean stopped;

    /**
     * Schedules the periodic check, or cancels it when every notification and reminder is off.
     * Returns false if the system refused; that must never crash the app, which works fine without it.
     * No network constraint, so reminders still arrive offline.
     */
    public static boolean schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            js.cancel(OLD_JOB_ID);
            if (!anyNotificationsEnabled(context) && !anyRemindersEnabled(context)) {
                js.cancel(JOB_ID);
                return true;
            }
            if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return true;
            return js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJobService.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException e) {
            Log.e("UpdateJobService", "Could not schedule results check", e);
            return false;
        }
    }

    static boolean notificationsEnabled(Context context, Game game) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("notify_" + game.id, true);
    }

    static boolean anyNotificationsEnabled(Context context) {
        for (Game g : Game.ALL) if (notificationsEnabled(context, g)) return true;
        return false;
    }

    static void setNotificationsEnabled(Context context, Game game, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("notify_" + game.id, enabled).apply();
        schedule(context);
    }

    static boolean reminderEnabled(Context context, Game game) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("remind_" + game.id, false);
    }

    static boolean anyRemindersEnabled(Context context) {
        for (Game g : Game.ALL) if (reminderEnabled(context, g)) return true;
        return false;
    }

    static void setReminderEnabled(Context context, Game game, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("remind_" + game.id, enabled).apply();
        schedule(context);
    }

    /** Hour of the day (7-22) reminders are sent on the day before a draw. */
    static int reminderHour(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt("remind_hour", 18);
    }

    static void setReminderHour(Context context, int hour) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("remind_hour", hour).apply();
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean retry = false;
                if (DrawSchedule.isQuietHours(System.currentTimeMillis(), TimeZone.getDefault())) {
                    // Checked again after 7am, so overnight results arrive in the morning.
                    jobFinished(params, false);
                    return;
                }
                Context ctx = UpdateJobService.this;
                for (Game game : Game.ALL) {
                    if (stopped) break;
                    try {
                        if (reminderEnabled(ctx, game)) remindIfDue(ctx, game);
                    } catch (Exception e) {
                        Log.e("UpdateJobService", "Reminder failed for " + game.name, e);
                    }
                    if (!notificationsEnabled(ctx, game)) continue;
                    try {
                        ResultsStore store = new ResultsStore(ctx, game);
                        List<Draw> before = store.load();
                        String last = before.get(before.size() - 1).date;
                        if (DrawSchedule.updateDue(game, last, System.currentTimeMillis())) {
                            List<Draw> after = store.update(before);
                            if (after.size() > before.size() && !stopped) showResult(ctx, game, latestNight(after));
                        }
                    } catch (Exception e) {
                        retry = true;
                    }
                }
                jobFinished(params, retry);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }

    /** The draws made on the most recent draw night (two for Lotto since June 2026). */
    static List<Draw> latestNight(List<Draw> draws) {
        String last = draws.get(draws.size() - 1).date;
        List<Draw> out = new ArrayList<>();
        for (Draw d : draws) if (d.date.equals(last)) out.add(d);
        return out;
    }

    /** Posts "draw tomorrow" with a suggested line, once per draw, from the chosen hour the day before. */
    private static void remindIfDue(Context context, Game game) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String drawDate = DrawSchedule.reminderFor(game, System.currentTimeMillis(), TimeZone.getDefault(),
                reminderHour(context), prefs.getString("reminded_" + game.id, null));
        if (drawDate == null) return;
        Stats stats = new Stats(game, new ResultsStore(context, game).load());
        Predictor.Line line = new Predictor(stats, new SecureRandom()).generate(1, Predictor.MIXED, true).get(0);
        String text = "Suggested line: " + line.toClipboardText();
        post(context, game, 100, game.name + " draw tomorrow – " + MainActivity.prettyDate(drawDate),
                "Don't forget your ticket. " + text,
                "Don't forget to buy your ticket.\n" + text + "\nTap for more lines.");
        prefs.edit().putString("reminded_" + game.id, drawDate).apply();
    }

    /** Posts the "new results" notification for the draws made on one night. */
    static void showResult(Context context, Game game, List<Draw> night) {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < night.size(); i++) {
            Draw d = night.get(i);
            if (night.size() > 1) all.append("Round ").append(i + 1).append(": ");
            for (int n : d.main) all.append(n).append("  ");
            if (d.extra.length > 0) {
                all.append(game.extraPicked ? "· " + game.extraName + " " : "· Bonus ");
                for (int e : d.extra) all.append(e).append("  ");
            }
            all.append('\n');
        }
        String numbers = all.toString().trim();
        post(context, game, 1, game.name + " results – " + MainActivity.prettyDate(night.get(0).date),
                numbers.replace('\n', ' '), numbers + "\nStatistics updated – tap to generate new predictions.");
    }

    /** Posts a notification that opens {@code game}; results and reminders use separate ids per game. */
    private static void post(Context context, Game game, int idBase, String title, String text, String bigText) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Results and reminders",
                    NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(context, CHANNEL_ID);
        } else {
            b = new Notification.Builder(context);
        }
        int index = Arrays.asList(Game.ALL).indexOf(game);
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_GAME, game.id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(bigText))
                .setContentIntent(PendingIntent.getActivity(context, idBase + index, open, flags))
                .setAutoCancel(true);
        nm.notify(idBase + index, b.build());
    }

    /** Sends a sample reminder now, for the test button. */
    static void showTestReminder(Context context, Game game) throws IOException {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("reminded_" + game.id).apply();
        Stats stats = new Stats(game, new ResultsStore(context, game).load());
        Predictor.Line line = new Predictor(stats, new SecureRandom()).generate(1, Predictor.MIXED, true).get(0);
        post(context, game, 100, game.name + " reminder (test)", "Suggested line: " + line.toClipboardText(),
                "This is how the day-before reminder looks.\nSuggested line: " + line.toClipboardText());
    }
}
