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
import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * Runs about once an hour. For each game with notifications on, it only goes online when a draw
 * should have been published but isn't stored yet, then posts a notification with the result.
 */
public class UpdateJobService extends JobService {
    private static final int JOB_ID = 1001;
    private static final long PERIOD_MS = 60 * 60 * 1000L;
    private static final String CHANNEL_ID = "results";

    static final String PREFS = "settings";
    static final String EXTRA_GAME = "game";

    private volatile boolean stopped;

    /**
     * Schedules the periodic check, or cancels it when every game's notifications are off. Returns
     * false if the system refused; that must never crash the app, which works fine without it.
     */
    public static boolean schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (!anyNotificationsEnabled(context)) {
                js.cancel(JOB_ID);
                return true;
            }
            if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return true;
            return js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
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

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean retry = false;
                for (Game game : Game.ALL) {
                    if (stopped) break;
                    if (!notificationsEnabled(UpdateJobService.this, game)) continue;
                    try {
                        ResultsStore store = new ResultsStore(UpdateJobService.this, game);
                        List<Draw> before = store.load();
                        String last = before.get(before.size() - 1).date;
                        if (DrawSchedule.updateDue(game, last, System.currentTimeMillis())) {
                            List<Draw> after = store.update(before);
                            String newLast = after.get(after.size() - 1).date;
                            if (newLast.compareTo(last) > 0 && !stopped) {
                                showResult(UpdateJobService.this, game, after.get(after.size() - 1));
                            }
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

    /** Posts the "new results" notification for {@code latest}. */
    static void showResult(Context context, Game game, Draw latest) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "New results",
                    NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(context, CHANNEL_ID);
        } else {
            b = new Notification.Builder(context);
        }

        StringBuilder nums = new StringBuilder();
        for (int n : latest.main) nums.append(n).append("  ");
        if (latest.extra.length > 0) {
            nums.append(game.extraPicked ? "· " + game.extraName + " " : "· Bonus ");
            for (int e : latest.extra) nums.append(e).append("  ");
        }
        String numbers = nums.toString().trim();

        Intent open = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_GAME, game.id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        int index = Arrays.asList(Game.ALL).indexOf(game);

        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(game.name + " results – " + MainActivity.prettyDate(latest.date))
                .setContentText(numbers)
                .setStyle(new Notification.BigTextStyle().bigText(numbers
                        + "\nStatistics updated – tap to generate new predictions."))
                .setContentIntent(PendingIntent.getActivity(context, index, open, flags))
                .setAutoCancel(true);
        nm.notify(index + 1, b.build());
    }
}
