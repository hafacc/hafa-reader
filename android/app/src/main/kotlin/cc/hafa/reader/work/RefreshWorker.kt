package cc.hafa.reader.work

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
import cc.hafa.reader.core.MIN_INTERVAL_MS
import cc.hafa.reader.readerApplication
import java.util.concurrent.TimeUnit

/**
 * Checks the feeds whose schedule says they are due. Android wakes it at most
 * every 15 minutes, the shortest wait the schedule ever asks for.
 */
class RefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        applicationContext.readerApplication.model.refreshDue()
        applicationContext.readerApplication.model.awaitWrites()
        return Result.success()
    }

    companion object {
        private const val NAME = "refresh-due"
        private const val FINISH_NAME = "finish-refresh"

        /**
         * Ask Android to keep the process working while checks a refresh left
         * to the background finish; they run in the app's scope either way.
         */
        fun finishInBackground(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                FINISH_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FinishChecksWorker>().build(),
            )
        }

        /** Start the periodic check, keeping one already scheduled. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(MIN_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Waits for the checks a refresh left to the background, so the process is not stopped while they run. */
class FinishChecksWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        applicationContext.readerApplication.model.awaitChecks()
        applicationContext.readerApplication.model.awaitWrites()
        return Result.success()
    }
}
