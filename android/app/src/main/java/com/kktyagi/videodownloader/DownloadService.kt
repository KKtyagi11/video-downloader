package com.kktyagi.videodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs downloads outside the activity so they survive the app being backgrounded
 * — the usual case on a phone, where you share a link and immediately switch away.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Phones are far more bandwidth- and heat-constrained than a desktop, so
    // two at a time rather than three.
    private val slots = Semaphore(2)
    private val running = ConcurrentHashMap<Long, CoJob>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(FOREGROUND_ID, buildSummary("Preparing…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val id = intent.getLongExtra(EXTRA_JOB_ID, -1L)
                if (id > 0) start(id)
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_JOB_ID, -1L)
                if (id > 0) cancelJob(id)
            }
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        val job = Downloads.get(id) ?: return
        running[id] = scope.launch {
            slots.withPermit { runJob(job) }
            if (Downloads.activeCount() == 0) stopSelf()
        }
    }

    private fun cancelJob(id: Long) {
        Ytdlp.cancel(processId(id))
        running.remove(id)?.cancel()
        Downloads.update(id) { it.copy(status = Status.CANCELLED, detail = "Stopped") }
        if (Downloads.activeCount() == 0) stopSelf()
    }

    private fun processId(id: Long) = "job-$id"

    private suspend fun runJob(job: Job) {
        val id = job.id
        try {
            // First run downloads a current yt-dlp; later runs return immediately.
            Downloads.update(id) { it.copy(status = Status.RESOLVING, detail = "Preparing engine…") }
            notifyProgress(id)
            Ytdlp.ensureReady(applicationContext)

            Downloads.update(id) { it.copy(status = Status.RESOLVING, detail = "Reading link…") }
            notifyProgress(id)

            val (title, thumb) = Ytdlp.fetchTitle(job.url)
            if (title != null) {
                Downloads.update(id) { it.copy(title = title, thumbnail = thumb) }
            }

            var lastNotified = 0L
            val file = Ytdlp.download(applicationContext, Downloads.get(id) ?: job, processId(id)) { pct, line ->
                val status = if (pct >= 100f) Status.PROCESSING else Status.DOWNLOADING
                Downloads.update(id) {
                    it.copy(
                        status = status,
                        percent = pct,
                        detail = if (status == Status.PROCESSING) "Finishing up…" else line.takeLast(60),
                    )
                }
                // Notification updates are rate-limited; the system drops them
                // if you post faster than a few per second anyway.
                val now = System.currentTimeMillis()
                if (now - lastNotified > 700) {
                    lastNotified = now
                    notifyProgress(id)
                }
            }

            Downloads.update(id) { it.copy(status = Status.PROCESSING, detail = "Saving to gallery…") }
            notifyProgress(id)

            val saved = Ytdlp.exportToGallery(applicationContext, file, job.quality.audioOnly)

            Downloads.update(id) {
                it.copy(status = Status.DONE, percent = 100f, detail = saved, outputPath = saved)
            }
            notifyDone(id, Downloads.get(id)?.title ?: job.url)
        } catch (e: Exception) {
            val cancelled = Downloads.get(id)?.status == Status.CANCELLED
            if (!cancelled) {
                Downloads.update(id) {
                    it.copy(status = Status.FAILED, detail = Ytdlp.explain(e.message ?: e.toString()))
                }
                notifyFailed(id, Downloads.get(id)?.detail ?: "Download failed")
            }
        } finally {
            running.remove(id)
        }
    }

    /* ----------------------------------------------------------- notifications */

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for videos being downloaded"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildSummary(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Video Downloader")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyProgress(id: Long) {
        val job = Downloads.get(id) ?: return
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.title.take(60))
            .setContentText(
                when (job.status) {
                    Status.RESOLVING -> "Reading link…"
                    Status.PROCESSING -> "Finishing up…"
                    else -> "${job.percent.toInt()}%"
                }
            )
            .setProgress(100, job.percent.toInt(), job.status != Status.DOWNLOADING)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        manager().notify(id.toInt() + BASE_ID, n)
        manager().notify(FOREGROUND_ID, buildSummary("${Downloads.activeCount()} downloading"))
    }

    private fun notifyDone(id: Long, title: String) {
        manager().notify(
            id.toInt() + BASE_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved")
                .setContentText(title.take(60))
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notifyFailed(id: Long, message: String) {
        manager().notify(
            id.toInt() + BASE_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(message.take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val FOREGROUND_ID = 1
        private const val BASE_ID = 1000

        const val ACTION_ENQUEUE = "enqueue"
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_JOB_ID = "job_id"

        fun enqueue(context: Context, jobId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, jobId: Long) {
            context.startService(
                Intent(context, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_JOB_ID, jobId)
                }
            )
        }
    }
}
