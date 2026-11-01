package com.dualframe.cam

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Una volta a settimana (con connessione) controlla su GitHub se c'è una versione nuova
 * e, se c'è, manda una notifica: toccandola si scarica l'APK.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val NAME = "camera-dual-update-check"
        private const val CHANNEL = "updates"
        private const val NOTIF_ID = 77

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val r = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_NAME) }
        if (r !is UpdateChecker.Result.Newer) return Result.success()

        // Una sola notifica per versione.
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getString("notifiedVersion", null) == r.version) return Result.success()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success() // senza permesso resta il controllo manuale toccando nome e versione

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl ?: r.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Camera Dual ${r.version} disponibile")
            .setContentText("Tocca per scaricare l'aggiornamento (hai la ${BuildConfig.VERSION_NAME}).")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
        prefs.edit().putString("notifiedVersion", r.version).apply()
        return Result.success()
    }
}
