package io.ather.pro.data.update

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        GithubAppUpdateRepository.getInstance(applicationContext).check()
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("github-app-updates", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
