package com.dualframe.cam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Avvia / mette in pausa l'elaborazione in sottofondo. */
object ExportScheduler {
    private const val NAME = "camera-dual-export"

    fun kick(context: Context) {
        if (RecordingGate.isRecording) return
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ExportWorker>().build()
        )
    }

    /** La ripresa ha la priorità: l'elaborazione in corso si ferma e riprende dopo. */
    fun pause(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
    }
}

/**
 * Taglia i master 4:3 nei due formati e li salva nella galleria.
 * Gira anche con l'app chiusa; se viene interrotto il lavoro resta in coda.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 42
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Taglio dei video", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Camera Dual")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Taglio i video nei due formati…")

    /** In primo piano (con notifica) il taglio non viene interrotto dopo 10 minuti né ad app chiusa. */
    private suspend fun goForeground(text: String) {
        try {
            setForeground(foregroundInfo(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Android non permette di partire in primo piano da sottofondo: prosegue come lavoro normale.
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        withContext(Dispatchers.IO) { PendingStore.recoverOrphans(ctx) }
        val exporter = VideoExporter(ctx)
        val tried = HashSet<String>()
        var failures = 0

        while (true) {
            if (RecordingGate.isRecording) return Result.success() // riprenderà dopo la ripresa
            val job = withContext(Dispatchers.IO) { PendingStore.list(ctx) }
                .firstOrNull { it.state == "ready" && it.id !in tried } ?: break
            tried += job.id
            val waiting = withContext(Dispatchers.IO) { PendingStore.list(ctx) }.count { it.state == "ready" }
            goForeground(if (waiting > 1) "Taglio $waiting video nei due formati…" else "Taglio il video nei due formati…")
            // Il file potrebbe essere ancora in chiusura: riprova tra poco senza contarlo come errore.
            if (System.currentTimeMillis() - File(job.masterPath).lastModified() < 5000) {
                failures++
                continue
            }
            try {
                process(ctx, exporter, job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                withContext(Dispatchers.IO) { fail(ctx, job, e.message ?: e.javaClass.simpleName) }
            }
        }
        return if (failures > 0) Result.retry() else Result.success()
    }

    private fun stamp(job: PendingJob): String =
        if (!job.recovered) job.id
        else SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(job.createdAt))

    private fun durationMs(f: File): Long = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    }.getOrDefault(0L)

    private suspend fun process(ctx: Context, exporter: VideoExporter, job0: PendingJob) {
        var job = job0
        val master = File(job.masterPath)
        if (!master.exists() || master.length() == 0L) {
            withContext(Dispatchers.IO) {
                PendingStore.remove(ctx, job)
                TakeStore.update(ctx, job.id) { it.copy(status = "failed", note = "File della ripresa non trovato") }
            }
            return
        }
        val info = runCatching { exporter.probe(master) }.getOrNull()
        if (info == null || info.width <= 0 || info.height <= 0) {
            throw IllegalStateException("Il file della ripresa è danneggiato (registrazione interrotta)")
        }
        val duration = durationMs(master)
        val ts = stamp(job)

        for (track in listOf(job.primaryTrack, job.secondaryTrack)) {
            val tag = FrameMath.fileTag(track.rectAt(0), job.angle, info.displayW, info.displayH)
            if (job.done.containsKey(tag)) continue
            val out = File(ctx.cacheDir, "out_${job.id}_$tag.mp4")
            try {
                withContext(Dispatchers.Main) {
                    exporter.export(master, track, job.angle, job.codec, job.maxLongSide, out)
                }
                val oi = exporter.probe(out)
                val saved = withContext(Dispatchers.IO) {
                    MediaSaver.saveVideo(ctx, out, "${MediaSaver.PREFIX}_${ts}_$tag",
                        Folders.forTag(tag), oi.displayW, oi.displayH)
                } ?: throw IllegalStateException("Salvataggio nella galleria non riuscito")
                val ref = MediaRef.of(saved)!!
                job = job.copy(done = job.done + (tag to ref))
                withContext(Dispatchers.IO) { PendingStore.save(ctx, job) }
            } finally {
                out.delete()
            }
        }

        val done = job.done
        withContext(Dispatchers.IO) {
            TakeStore.update(ctx, job.id) {
                it.copy(status = "done", durationMs = duration, vertical = done["9x16"], horizontal = done["16x9"])
            }
            PendingStore.remove(ctx, job)
        }
    }

    /** Dopo 3 tentativi falliti salva il master così com'è: la ripresa non va mai persa. */
    private fun fail(ctx: Context, job: PendingJob, message: String) {
        val attempts = job.attempts + 1
        if (attempts < 3) {
            PendingStore.save(ctx, job.copy(attempts = attempts))
            TakeStore.update(ctx, job.id) { it.copy(note = "Nuovo tentativo in corso… ($message)") }
            return
        }
        val master = File(job.masterPath)
        val info = runCatching { VideoExporter(ctx).probe(master) }.getOrNull()
        val saved = if (info != null && info.width > 0 && master.length() > 0) {
            MediaSaver.saveVideo(ctx, master, "${MediaSaver.PREFIX}_${stamp(job)}_originale",
                Folders.ORIGINALS, info.displayW, info.displayH)
        } else null
        // Un file danneggiato non si butta: resta in Android/data/com.dualframe.cam/files/Danneggiati
        // (raggiungibile dal computer) per un eventuale recupero con programmi appositi.
        if (saved == null && master.exists() && master.length() > 0) {
            runCatching {
                val dir = ctx.getExternalFilesDir("Danneggiati")
                if (dir != null) {
                    dir.mkdirs()
                    val dest = File(dir, "${MediaSaver.PREFIX}_${stamp(job)}_danneggiato.mp4")
                    if (!master.renameTo(dest)) { master.copyTo(dest, overwrite = true) }
                }
            }
        }
        TakeStore.update(ctx, job.id) {
            it.copy(
                status = "failed",
                vertical = job.done["9x16"] ?: it.vertical,
                horizontal = job.done["16x9"] ?: it.horizontal,
                note = if (saved != null) "Non sono riuscito a tagliarlo: l'originale 4:3 è in \"Camera Dual Originali\""
                else "Video danneggiato: il file grezzo è in Android/data/com.dualframe.cam/files/Danneggiati"
            )
        }
        PendingStore.remove(ctx, job)
    }
}
