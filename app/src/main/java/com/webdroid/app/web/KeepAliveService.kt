package com.webdroid.app.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.webdroid.app.MainActivity
import com.webdroid.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps WebDroid's process (and the renderers of "Stay connected" apps)
 * alive while those apps are open in Recents, so their pages can keep
 * sockets open and raise notifications. Shows one quiet ongoing notification.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (connected.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "background"
        private const val NOTIFICATION_ID = 4242
        private val connected = ConcurrentHashMap<String, String>()

        private fun buildNotification(context: Context): android.app.Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Stay connected", NotificationManager.IMPORTANCE_MIN).apply {
                            description = "Shown while web apps stay connected in the background"
                            setShowBadge(false)
                        }
                    )
                }
            }
            val names = connected.values.sorted()
            val text = when (names.size) {
                1 -> "${names[0]} stays connected for notifications"
                else -> "${names.joinToString(", ")} stay connected"
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_webdroid)
                .setContentTitle("WebDroid")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .build()
        }

        /** Call while the app is visible (foreground-service start rules). */
        fun connect(context: Context, appId: String, name: String) {
            connected[appId] = name
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }

        fun disconnect(context: Context, appId: String) {
            if (connected.remove(appId) == null) return
            if (connected.isEmpty()) {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } else {
                runCatching { context.startService(Intent(context, KeepAliveService::class.java)) }
            }
        }
    }
}
