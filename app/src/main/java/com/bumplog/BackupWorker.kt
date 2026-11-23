package com.bumplog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Weekly automatic backup into the folder the owner picked. A save also runs after each trip email. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Belt and braces with BumpLogApp: settings are ready even when WorkManager starts a fresh process.
        com.bumplog.Prefs.init(applicationContext)
        // No folder picked yet is not a failure: succeed quietly instead of retrying forever.
        if (com.bumplog.Prefs.backupFolder.isBlank()) return Result.success()
        return when (val r = Backup.autoSaveResult(applicationContext)) {
            is Backup.SaveResult.Saved, is Backup.SaveResult.NoFolder -> {
                Live.bump() // noteOutcome already cleared the Settings failure line
                Result.success()
            }
            // Too big to ever restore: retrying the same save won't help, so say so once
            // and stop; the weekly schedule still brings the next regular run.
            is Backup.SaveResult.TooLarge -> {
                Notifier.backupFailed(applicationContext, r.reason)
                Live.bump()
                Result.success()
            }
            // An ordinary failure (folder gone, permission revoked) is told once too, not
            // retried silently; the weekly schedule brings the next regular run, and the
            // Settings screen keeps showing the older last-backup day.
            is Backup.SaveResult.Failed -> {
                Notifier.backupFailed(applicationContext, r.reason)
                Live.bump()
                Result.success()
            }
        }
    }

    companion object {
        private const val JOB = "auto_backup"
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                JOB, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build())
        }
    }
}
