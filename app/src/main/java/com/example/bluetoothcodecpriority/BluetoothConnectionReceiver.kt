package com.example.bluetoothcodecpriority

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

class BluetoothConnectionReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val diagnosticLog = DiagnosticLog(context)
        if (intent.action == "android.intent.action.BOOT_COMPLETED") {
            handleBoot(context, diagnosticLog)
            return
        }
        val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
        diagnosticLog.write("receiver: action=${intent.action} state=$state")
        if (intent.action != "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") return
        val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>("android.bluetooth.device.extra.DEVICE")
        if (state != 2) {
            diagnosticLog.write("receiver: ignoring non-connected A2DP state=$state")
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(4_000L) {
                        val snapshots = CodecEngine(context).readConnected()
                        if (snapshots.isEmpty()) CodecStatusNotification.showWaiting(context)
                        else CodecStatusNotification.showConnected(context, snapshots)
                    }
                } catch (error: Throwable) {
                    diagnosticLog.write("receiver: could not refresh status after disconnect", error)
                } finally { pending.finish() }
            }
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                    val connectedDevice = device ?: return@withTimeout
                    diagnosticLog.write("receiver: A2DP connected ${connectedDevice.name ?: "Unknown"} ${connectedDevice.address}")
                    SettingsStore(context).rememberDevice(connectedDevice.address, connectedDevice.name ?: "Unknown device")
                    BackgroundCodecApplier.apply(context, connectedDevice.address)
                }
            } catch (error: Throwable) {
                diagnosticLog.write("receiver: automatic application failed", error)
                android.util.Log.w("CodecPriority", "Automatic codec application did not complete", error)
            } finally { pending.finish() }
        }
    }

    private fun handleBoot(context: Context, diagnosticLog: DiagnosticLog) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(12_000L) {
                    diagnosticLog.write("receiver: boot completed; checking connected A2DP devices")
                    val engine = CodecEngine(context)
                    engine.readConnected().forEach { device ->
                        BackgroundCodecApplier.apply(context, device.deviceAddress)
                    }
                }
            } catch (error: Throwable) {
                diagnosticLog.write("receiver: boot reapply failed", error)
            } finally { pending.finish() }
        }
    }
}
