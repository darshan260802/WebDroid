package com.webdroid.app.web

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.webdroid.app.R
import com.webdroid.app.data.IconStore
import com.webdroid.app.data.WebApp
import com.webdroid.app.util.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request

/** A page's `new Notification(...)` / `registration.showNotification(...)`. */
data class WebNotification(
    val pageId: String,
    val title: String,
    val body: String,
    val tag: String,
    val iconUrl: String?,
    val imageUrl: String?,
    val silent: Boolean,
    val origin: String,
)

/**
 * Android notifications for web apps. Each app gets its own channel (named
 * after the app, so it can be tuned in system settings), grouped under
 * "Web apps"; tapping a notification brings that app's window back.
 */
object WebNotifier {
    const val EXTRA_NOTIFICATION = "webdroid_notification"
    private const val GROUP_ID = "web_apps"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun channelId(app: WebApp) = "app_${app.id}"

    fun notificationId(app: WebApp, pageId: String) = (app.id + "|" + pageId).hashCode()

    private fun ensureChannel(context: Context, app: WebApp) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannelGroup(GROUP_ID) == null) {
            manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, "Web apps"))
        }
        val existing = manager.getNotificationChannel(channelId(app))
        if (existing == null) {
            val channel = NotificationChannel(channelId(app), app.name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications from ${app.host}"
                group = GROUP_ID
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        } else if (existing.name != app.name) {
            existing.name = app.name
            manager.createNotificationChannel(existing)
        }
    }

    fun deleteChannel(context: Context, app: WebApp) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching { context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(channelId(app)) }
    }

    /** Whether Android lets WebDroid post notifications for this app at all. */
    fun systemAllows(context: Context, app: WebApp): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId(app))
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun channelSettingsIntent(context: Context, app: WebApp): Intent {
        ensureChannel(context, app)
        return if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId(app))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        }
    }

    fun show(context: Context, app: WebApp, notification: WebNotification) {
        ensureChannel(context, app)
        if (!systemAllows(context, app)) return
        val tile = IconStore.get(context).renderTile(app, 192)
        post(context, app, notification, tile, picture = null, update = false)
        if (notification.iconUrl != null || notification.imageUrl != null) {
            scope.launch {
                val icon = notification.iconUrl?.let { download(it) }
                val picture = notification.imageUrl?.let { download(it) }
                if (icon != null || picture != null) post(context, app, notification, icon ?: tile, picture, update = true)
            }
        }
    }

    private fun post(context: Context, app: WebApp, n: WebNotification, largeIcon: Bitmap, picture: Bitmap?, update: Boolean) {
        val id = notificationId(app, n.pageId)
        val open = WebAppActivity.intent(context, app.id).putExtra(EXTRA_NOTIFICATION, n.pageId)
        val contentIntent = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channelId(app))
            .setSmallIcon(R.drawable.ic_stat_webdroid)
            .setLargeIcon(largeIcon)
            .setContentTitle(n.title.ifBlank { app.name })
            .setContentText(n.body)
            .setSubText(app.name)
            .setColor(CardColorInt.of(app.cardColor))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setGroup("group_${app.id}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(update)
            .setSilent(n.silent || update)
        if (picture != null) {
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(n.body))
        } else if (n.body.length > 40) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
        }
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(id, builder.build())
            // Collapse several notifications of one app into a group.
            val summary = NotificationCompat.Builder(context, channelId(app))
                .setSmallIcon(R.drawable.ic_stat_webdroid)
                .setSubText(app.name)
                .setColor(CardColorInt.of(app.cardColor))
                .setGroup("group_${app.id}")
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(PendingIntent.getActivity(context, app.id.hashCode(), WebAppActivity.intent(context, app.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
            manager.notify(app.id.hashCode(), summary)
        } catch (e: SecurityException) {
            // Notification permission revoked meanwhile.
        }
    }

    fun cancel(context: Context, app: WebApp, pageId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(app, pageId))
    }

    private fun download(url: String): Bitmap? = runCatching {
        if (url.startsWith("data:")) {
            val (_, bytes) = DownloadHelper.decodeDataUrl(url) ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (!url.startsWith("http")) return null
        val request = Request.Builder().url(url).header("User-Agent", Net.MOBILE_USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()
}

/** Card colours as ARGB ints for platform APIs. */
object CardColorInt {
    fun of(index: Int): Int = when (Math.floorMod(index, 6)) {
        0 -> 0xFFFF4D8B.toInt()
        1 -> 0xFF1A3A3A.toInt()
        2 -> 0xFFB8A4ED.toInt()
        3 -> 0xFFFFB084.toInt()
        4 -> 0xFFE8B94A.toInt()
        else -> 0xFFF5F0E0.toInt()
    }
}
