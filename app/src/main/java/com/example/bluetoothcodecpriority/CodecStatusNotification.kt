package com.example.bluetoothcodecpriority

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Shows live codec read-back through the app's single foreground status notification. */
object CodecStatusNotification {
    private const val channelId = "codec_status"
    const val notificationId = 10_000
    @Volatile private var legacyNotificationsCleared = false

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Bluetooth codec status", NotificationManager.IMPORTANCE_LOW))
        if (!legacyNotificationsCleared) synchronized(this) {
            if (!legacyNotificationsCleared) {
                manager.cancelAll()
                legacyNotificationsCleared = true
            }
        }
    }

    fun show(context: Context, snapshot: CodecSnapshot) {
        showConnected(context, listOf(snapshot))
    }

    fun showConnected(context: Context, snapshots: List<CodecSnapshot>) {
        createChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (snapshots.isEmpty()) return
        val title = if (snapshots.size == 1) snapshots.first().deviceName else "${snapshots.size} Bluetooth audio devices"
        val text = snapshots.joinToString("\n") { snapshot ->
            "${snapshot.deviceName}: active ${snapshot.activeCodec?.name ?: "unavailable"}, requested ${snapshot.requestedCodec?.name ?: "none"}, result ${snapshot.lastResult ?: "not applied"}"
        }
        notify(context, build(context, title, text))
    }

    fun showWaiting(context: Context) {
        createChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        notify(context, build(context, "Bluetooth Codec Priority", "No connected A2DP device"))
    }

    fun build(context: Context, title: String, text: String): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }
}
