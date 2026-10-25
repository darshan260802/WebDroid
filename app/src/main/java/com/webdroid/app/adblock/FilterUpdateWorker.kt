package com.webdroid.app.adblock

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.webdroid.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps filter lists fresh in the background, like uBO's auto-update: lists
 * are re-downloaded and recompiled every few days on a non-low battery.
 */
class FilterUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = AppSettings.get(context).state.value
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force) {
            val meta = AdblockStorage.activeMeta(context)
            val age = System.currentTimeMillis() - (meta?.builtAtMillis ?: 0L)
            val bundledFresh = meta == null && AdblockStorage.bundledMatches(context, settings.listOverrides) &&
                System.currentTimeMillis() - bundledMillis(context) < MAX_AGE_MS
            if (bundledFresh || (meta != null && age < MAX_AGE_MS)) return@withContext Result.success()
        }
        AdblockEngine.setUpdating(true, "Starting")
        val result = FilterCompiler.run(context, settings.listOverrides) { message ->
            AdblockEngine.setUpdating(true, message)
            setProgressAsync(workDataOf("progress" to message))
        }
        if (result.ok) {
            AdblockEngine.setUpdating(false, null)
            AdblockEngine.reload()
            context.getSharedPreferences("webdroid", Context.MODE_PRIVATE).edit()
                .putLong("filters_updated_at", System.currentTimeMillis()).apply()
            Result.success()
        } else {
            AdblockEngine.setUpdating(false, null, result.message)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun bundledMillis(context: Context): Long = runCatching {
        java.time.Instant.parse(AdblockStorage.catalog(context).builtAt).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        private const val KEY_FORCE = "force"
        private const val PERIODIC = "filter-update"
        private const val NOW = "filter-update-now"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FilterUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun updateNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<FilterUpdateWorker>()
                .setInputData(workDataOf(KEY_FORCE to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
            AdblockEngine.setUpdating(true, "Waiting for network")
        }
    }
}
