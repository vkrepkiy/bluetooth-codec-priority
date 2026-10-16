package com.example.bluetoothcodecpriority

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.annotation.SuppressLint
import android.util.Log
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Optional process-death/reconnection wake point after the user creates an association. */
@SuppressLint("NewApi")
class CodecCompanionService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(association: AssociationInfo) {
        val address = if (Build.VERSION.SDK_INT >= 34) {
            association.associatedDevice?.bluetoothDevice?.address
        } else {
            association.deviceMacAddress?.toString()
        } ?: return
        scope.launch {
            runCatching {
                withTimeout(8_000L) {
                    applyIfA2dpConnected(address)
                }
            }.onFailure { Log.w("CodecPriority", "Companion codec application failed", it) }
        }
    }
    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(association: AssociationInfo) = Unit

    @SuppressLint("NewApi")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36 || event.event != DevicePresenceEvent.EVENT_BT_CONNECTED) return
        scope.launch {
            runCatching {
                withTimeout(8_000L) {
                    val manager = getSystemService(android.companion.CompanionDeviceManager::class.java)
                    val association = manager.myAssociations.firstOrNull { it.id == event.associationId } ?: return@withTimeout
                    val address = association.associatedDevice?.bluetoothDevice?.address ?: return@withTimeout
                    Log.i("CodecPriority", "Companion presence: A2DP device connected $address")
                    applyIfA2dpConnected(address)
                }
            }.onFailure { Log.w("CodecPriority", "Companion presence codec application failed", it) }
        }
    }

    private suspend fun applyIfA2dpConnected(address: String) {
        val engine = CodecEngine(applicationContext)
        if (!engine.isA2dpConnected(address)) {
            Log.i("CodecPriority", "Companion presence: device $address is present but A2DP is not connected; not applying")
            return
        }
        BackgroundCodecApplier.apply(applicationContext, address)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
