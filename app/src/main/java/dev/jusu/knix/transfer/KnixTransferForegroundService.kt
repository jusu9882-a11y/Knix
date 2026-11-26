package dev.jusu.knix.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a user-started local transfer session at foreground process priority while
 * the UI is backgrounded. The transfer socket/protocol remains owned by the
 * authenticated session; this service provides the Android foreground lifetime
 * and an honest ongoing notification, not a fake WorkManager job.
 */
class KnixTransferForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "knix_transfers"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_UPDATE = "dev.jusu.knix.transfer.UPDATE"
        private const val ACTION_STOP = "dev.jusu.knix.transfer.STOP"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, message: String) {
            val intent = Intent(context, KnixTransferForegroundService::class.java)
                .setAction(ACTION_UPDATE).putExtra(EXTRA_TEXT, message)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, message: String) {
            val intent = Intent(context, KnixTransferForegroundService::class.java)
                .setAction(ACTION_UPDATE).putExtra(EXTRA_TEXT, message)
            try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KnixTransferForegroundService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Knix transfers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows active offline file transfer sessions"
                    setShowBadge(false)
                }
            )
        }
        startForeground(NOTIFICATION_ID, notification("Preparing offline transfer…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.take(120)
                    ?: "Offline transfer session active"
                manager.notify(NOTIFICATION_ID, notification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Knix • Offline transfer")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
