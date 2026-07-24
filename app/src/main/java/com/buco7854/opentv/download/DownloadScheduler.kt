package com.buco7854.opentv.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Scheduling port used by [DownloadRepository].
 *
 * WorkManager configuration belongs in this Android adapter, leaving download
 * application policy independent of WorkManager's static entry point.
 */
interface DownloadScheduler {
    fun enqueue(downloadId: Long)
    fun cancel(downloadId: Long)
}

class WorkManagerDownloadScheduler(
    private val context: Context,
) : DownloadScheduler {
    // Delay WorkManager startup until AppGraph has finished constructing. Its
    // initializer can immediately restore workers, which need the completed graph.
    private val workManager by lazy { WorkManager.getInstance(context) }

    override fun enqueue(downloadId: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to downloadId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            workName(downloadId),
            // Resume can follow cancellation immediately; replacing the old
            // unique work prevents KEEP from retaining its cancelling instance.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel(downloadId: Long) {
        workManager.cancelUniqueWork(workName(downloadId))
    }

    private fun workName(downloadId: Long) = "download-$downloadId"
}
