package com.example.bluetoothcodecpriority

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Keeps one non-dismissible status notification while the user has enabled the app. */
class CodecStatusService : Service() {
    override fun onCreate() {
        super.onCreate()
        CodecStatusNotification.createChannel(this)
        ServiceCompat.startForeground(
            this,
            CodecStatusNotification.notificationId,
            CodecStatusNotification.build(this, "Bluetooth Codec Priority", "Waiting for A2DP device"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
