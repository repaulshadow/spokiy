package ua.spokiy.trevoga;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Запасне опитування сервера раз на ~15 хв — якщо push загубився або джерела розійшлися. */
public class PollJob extends JobService {
    static final int ID = 7;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == ID) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, PollJob.class))
                    .setPeriodic(15 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
        } catch (Throwable e) { MonitorService.L("JOB " + e); }
    }

    static void cancel(Context c) {
        try { ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(ID); } catch (Throwable ignored) { }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            public void run() {
                try { PlayPush.sync(PollJob.this); PlayCore.apply(getApplicationContext(), null, false); }
                catch (Throwable ignored) { }
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
