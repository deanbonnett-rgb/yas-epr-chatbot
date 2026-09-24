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

import java.util.List;

/**
 * Runs about once an hour. It only goes online when a Tuesday/Friday draw should have been
 * published but isn't stored yet, then posts a notification with the new result.
 */
public class UpdateJobService extends JobService {
    private static final int JOB_ID = 1001;
    private static final long PERIOD_MS = 60 * 60 * 1000L;
    private static final String CHANNEL_ID = "results";
    private static final int NOTIFICATION_ID = 1;

    static final String PREFS = "settings";
    static final String PREF_NOTIFY = "notify_results";

    private volatile boolean stopped;

    /** Schedules (or cancels, if notifications are switched off) the periodic check. */
    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!notificationsEnabled(context)) {
            js.cancel(JOB_ID);
            return;
        }
        if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    static boolean notificationsEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_NOTIFY, true);
    }

    static void setNotificationsEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putBoolean(PREF_NOTIFY, enabled).apply();
        schedule(context);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean retry = false;
                try {
                    ResultsStore store = new ResultsStore(UpdateJobService.this);
                    List<Draw> before = store.load();
                    String last = before.get(before.size() - 1).date;
                    if (DrawSchedule.updateDue(last, System.currentTimeMillis())) {
                        List<Draw> after = store.update(before);
                        if (after.size() > before.size() && !stopped) notifyNewDraws(after, after.size() - before.size());
                    }
                } catch (Exception e) {
                    retry = true;
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

    private void notifyNewDraws(List<Draw> draws, int added) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;
        showResult(this, draws.get(draws.size() - 1), added);
    }

    /** Posts the "new results" notification for {@code latest}. */
    static void showResult(Context context, Draw latest, int added) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
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
        nums.append("★ ");
        for (int s : latest.stars) nums.append(s).append("  ");
        String numbers = nums.toString().trim();
        String extra = added > 1 ? " (+" + (added - 1) + " earlier draw" + (added > 2 ? "s" : "") + ")" : "";

        Intent open = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("EuroMillions results – " + MainActivity.prettyDate(latest.date) + extra)
                .setContentText(numbers)
                .setStyle(new Notification.BigTextStyle().bigText(numbers
                        + "\nStatistics updated – tap to generate new predictions."))
                .setContentIntent(PendingIntent.getActivity(context, 0, open, flags))
                .setAutoCancel(true);
        nm.notify(NOTIFICATION_ID, b.build());
    }
}
