package com.webdroid.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.webdroid.app.data.AppSettings
import java.util.concurrent.TimeUnit

/** Daily background check for a new WebDroid release. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.get(applicationContext).state.value.autoUpdate) return Result.success()
        val state = AppUpdater.check(applicationContext, userInitiated = false, fromBackground = true)
        return if (state is UpdateState.Failed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "app-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS, 3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
