package com.buco7854.opentv.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class HubDownloadPrepareWorker(
    context: Context,
    params: WorkerParameters,
    private val hubDownloads: HubDownloadWorkerAccess,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val downloadId = inputData.getLong(DownloadWorker.KEY_DOWNLOAD_ID, -1)
        return when (val outcome = hubDownloads.prepare(downloadId)) {
            HubPreparationResult.Preparing -> Result.retry()
            is HubPreparationResult.RetryAfter -> {
                delay(outcome.delayMs)
                Result.retry()
            }
            HubPreparationResult.HandedOff,
            HubPreparationResult.Complete,
            HubPreparationResult.Blocked -> Result.success()
        }
    }
}
