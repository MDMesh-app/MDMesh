package com.mdmesh.core.kiosk

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mdmesh.proto.KioskBackgroundImageDto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Fetches a kiosk's background image as soon as the kiosk is applied, on the network, with WorkManager's backoff,
 * so it is ready before the launcher next shows (a single-app kiosk's launcher stays behind its app). The image
 * shown meanwhile stays until the new one is verified and decoded.
 */
@HiltWorker
class KioskBackgroundPrefetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: KioskBackgroundImageStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
        val sha = inputData.getString(KEY_SHA)
        if (url == null || sha == null) return Result.success()
        return outcome(store.obtain(KioskBackgroundImageDto(url, sha)))
    }

    companion object {
        private const val NAME = "kiosk-background"
        private const val KEY_URL = "url"
        private const val KEY_SHA = "sha256"
        private const val BACKOFF_SECONDS = 30L

        /** Cached, or never going to work (bad URL or hash): done. A download that failed: WorkManager retries. */
        internal fun outcome(got: KioskBackgroundImageStore.Obtained): Result = when (got) {
            is KioskBackgroundImageStore.Obtained.Ready, KioskBackgroundImageStore.Obtained.Invalid -> Result.success()
            is KioskBackgroundImageStore.Obtained.Retry -> Result.retry()
        }

        /** Replaces any prefetch of an earlier image. */
        fun enqueue(context: Context, image: KioskBackgroundImageDto) {
            val request = OneTimeWorkRequestBuilder<KioskBackgroundPrefetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_URL to image.url, KEY_SHA to image.sha256))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
