package com.example.bluetoothcodecpriority

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Observes A2DP events for the lifetime of the status notification; never polls. */
class CodecStatusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionReceiver = BluetoothConnectionReceiver()

    override fun onCreate() {
        super.onCreate()
        CodecStatusNotification.createChannel(this)
        ServiceCompat.startForeground(
            this,
            CodecStatusNotification.notificationId,
            CodecStatusNotification.build(this, "Bluetooth Codec Priority", "Waiting for A2DP device"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        // Bluetooth events originate in a privileged app with its own UID.
        // The connection action is a protected system broadcast.
        ContextCompat.registerReceiver(
            this, connectionReceiver,
            IntentFilter(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        scope.launch {
            try {
                withTimeout(20_000L) {
                    val snapshots = CodecEngine(applicationContext).readConnected()
                    CodecStatusNotification.showConnected(applicationContext, snapshots)
                    snapshots.forEach { BackgroundCodecApplier.apply(applicationContext, it.deviceAddress) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog(applicationContext).write("status service: startup reconciliation failed", error)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(connectionReceiver)
        scope.cancel()
        super.onDestroy()
    }
}
