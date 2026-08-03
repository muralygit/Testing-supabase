package com.example.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodically touches the Supabase project so the free-tier
 * "paused after 7 days of no API activity" rule never actually kicks in,
 * even if the app itself sits unopened.
 *
 * The call is deliberately tiny (a 1-row select) — this only needs to
 * register as *a* request, not move any real data.
 */
class SupabaseKeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            // Cheapest possible authenticated request: ask for a single
            // client_id column, limited to 1 row.
            SupabaseClientProvider.client.postgrest[SupabaseClientProvider.DOCUMENTS_TABLE]
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("client_id")) {
                    limit(1)
                }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Transient network/server issue — let WorkManager retry with
            // backoff rather than waiting a full 3 days for the next run.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "supabase_keep_alive"
        private val KEEP_ALIVE_INTERVAL_DAYS = 3L

        /**
         * Schedules the recurring ping. Safe to call every app launch:
         * [ExistingPeriodicWorkPolicy.KEEP] means if it's already scheduled,
         * this is a no-op rather than resetting the countdown.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SupabaseKeepAliveWorker>(
                KEEP_ALIVE_INTERVAL_DAYS, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Call to stop the recurring ping, if ever needed. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
