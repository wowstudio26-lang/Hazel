package com.hazel.android.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hazel.android.MainActivity
import com.hazel.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Performs one remote file transfer independently of the Remote Bridge screen's lifecycle. */
class RemoteBridgeTransferService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeClient: RemoteBridgeClient? = null

    @Volatile
    private var cancelRequested = false

    private var transferJob: Job? = null
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> handleCancel(startId)
            ACTION_START -> handleStart(intent, startId)
            else -> {
                // No task is restored by the system. If a stale restart intent arrives,
                // stop cleanly rather than displaying an unrelated active notification.
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, startId: Int) {
        if (transferJob?.isActive == true) {
            RemoteBridgeTransferStore.load(this)?.let(::showNotification)
            return
        }

        val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        val relativePath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank {
            "remote-download.bin"
        }
        val state = RemoteBridgeTransferStore.State(
            jobId = jobId,
            relativePath = relativePath,
            displayName = displayName,
            status = "queued",
            message = getString(R.string.remote_bridge_starting_transfer)
        )
        enterForeground(state)

        if (!jobId.matches(Regex("^[0-9a-f]{32}$")) ||
            relativePath.isBlank() ||
            relativePath.split('/').any { it == "." || it == ".." }
        ) {
            val failed = state.copy(
                status = "failed",
                message = getString(R.string.remote_bridge_transfer_failed)
            )
            RemoteBridgeTransferStore.save(this, failed)
            showNotification(failed)
            stopForegroundAndSelf()
            return
        }

        cancelRequested = false
        RemoteBridgeTransferStore.save(this, state)
        transferJob = serviceScope.launch { runTransfer(state, startId) }
    }

    private fun handleCancel(startId: Int) {
        val current = RemoteBridgeTransferStore.load(this)
        val active = transferJob?.isActive == true && current?.isActive == true
        if (!active) {
            current?.takeIf { it.isActive }?.let {
                RemoteBridgeTransferStore.save(
                    this,
                    it.copy(status = "cancelled", message = getString(R.string.remote_bridge_cancelled))
                )
            }
            enterForeground(current ?: fallbackState())
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }

        cancelRequested = true
        current?.let {
            val cancelling = it.copy(
                status = "cancelling",
                message = getString(R.string.remote_bridge_file_transfer_cancelling)
            )
            RemoteBridgeTransferStore.save(this, cancelling)
            showNotification(cancelling)
        }
        activeClient?.cancelActiveFileDownload()
    }

    private suspend fun runTransfer(initial: RemoteBridgeTransferStore.State, startId: Int) {
        var state = initial.copy(
            status = "downloading",
            message = getString(R.string.remote_bridge_starting_transfer)
        )
        var lastNotificationAt = 0L
        var lastPersistedBytes = 0L
        try {
            val config = RemoteBridgeSecrets.load(this)
                ?: throw IOException("Configure and save the Remote Bridge endpoint and token first")
            if (cancelRequested) throw CancellationException("Transfer cancelled before start")
            val client = RemoteBridgeClient(config.endpoint, config.token)
            activeClient = client
            if (cancelRequested) throw CancellationException("Transfer cancelled before network request")
            RemoteBridgeTransferStore.save(this, state)
            showNotification(state)

            val uri = RemoteBridgeDownloads.downloadAndPublish(
                context = this,
                client = client,
                jobId = state.jobId,
                relativePath = state.relativePath,
                displayName = state.displayName
            ) { downloaded, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotificationAt >= 1000L ||
                    downloaded - lastPersistedBytes >= 2L * 1024L * 1024L ||
                    (total > 0L && downloaded >= total)
                ) {
                    lastNotificationAt = now
                    lastPersistedBytes = downloaded
                    state = state.copy(
                        status = if (cancelRequested) "cancelling" else "downloading",
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        message = progressMessage(downloaded, total),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    RemoteBridgeTransferStore.save(this, state)
                    showNotification(state)
                }
            }

            state = state.copy(
                status = "complete",
                fileUri = uri.toString(),
                message = getString(R.string.remote_bridge_transfer_complete),
                updatedAtMillis = System.currentTimeMillis()
            )
            RemoteBridgeTransferStore.save(this, state)
            showNotification(state)
        } catch (e: CancellationException) {
            val terminal = state.copy(
                status = if (cancelRequested) "cancelled" else "interrupted",
                message = if (cancelRequested) getString(R.string.remote_bridge_cancelled)
                    else getString(R.string.remote_bridge_transfer_interrupted),
                updatedAtMillis = System.currentTimeMillis()
            )
            RemoteBridgeTransferStore.save(this, terminal)
            showNotification(terminal)
        } catch (e: Exception) {
            val wasCancelled = cancelRequested
            val terminal = state.copy(
                status = if (wasCancelled) "cancelled" else "failed",
                message = if (wasCancelled) getString(R.string.remote_bridge_cancelled)
                    else (e.message ?: getString(R.string.remote_bridge_transfer_failed)),
                updatedAtMillis = System.currentTimeMillis()
            )
            RemoteBridgeTransferStore.save(this, terminal)
            showNotification(terminal)
        } finally {
            activeClient = null
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stopForegroundAndSelf()
            }
        }
    }

    private fun progressMessage(downloaded: Long, total: Long): String {
        val downloadedMiB = downloaded / (1024L * 1024L)
        return if (total > 0L) {
            getString(
                R.string.remote_bridge_progress_known,
                downloadedMiB,
                total / (1024L * 1024L)
            )
        } else {
            getString(R.string.remote_bridge_progress_unknown, downloadedMiB)
        }
    }

    private fun enterForeground(state: RemoteBridgeTransferStore.State) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showNotification(state: RemoteBridgeTransferStore.State) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RemoteBridgeTransferStore.State): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state.displayName.ifBlank { getString(R.string.remote_bridge_title) })
            .setContentText(state.message.ifBlank { getString(R.string.remote_bridge_starting_transfer) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.message))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!state.isActive)
            .setOngoing(state.isActive)

        if (state.isActive) {
            val cancelIntent = PendingIntent.getService(
                this,
                CANCEL_REQUEST,
                Intent(this, RemoteBridgeTransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.remote_bridge_cancel_file_transfer),
                cancelIntent
            )
            if (state.totalBytes > 0L) {
                val percent = ((state.downloadedBytes.toDouble() / state.totalBytes.toDouble()) * 100.0)
                    .toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_bridge_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.remote_bridge_notification_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun fallbackState() = RemoteBridgeTransferStore.State(
        jobId = "",
        relativePath = "",
        displayName = getString(R.string.remote_bridge_title),
        status = "cancelled",
        message = getString(R.string.remote_bridge_cancelled)
    )

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf(latestStartId)
    }

    override fun onDestroy() {
        running = false
        activeClient?.cancelActiveFileDownload()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "hazel_remote_bridge_transfer"
        private const val NOTIFICATION_ID = 1104
        private const val OPEN_APP_REQUEST = 3101
        private const val CANCEL_REQUEST = 3102
        private const val ACTION_START = "com.hazel.android.remote_bridge.START"
        private const val ACTION_CANCEL = "com.hazel.android.remote_bridge.CANCEL"
        private const val EXTRA_JOB_ID = "remote_bridge.job_id"
        private const val EXTRA_PATH = "remote_bridge.relative_path"
        private const val EXTRA_DISPLAY_NAME = "remote_bridge.display_name"

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun start(context: Context, jobId: String, relativePath: String, displayName: String) {
            require(jobId.matches(Regex("^[0-9a-f]{32}$")))
            require(relativePath.isNotBlank() && relativePath.split('/').none { it == "." || it == ".." })
            val intent = Intent(context, RemoteBridgeTransferService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_PATH, relativePath)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, RemoteBridgeTransferService::class.java).setAction(ACTION_CANCEL)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
