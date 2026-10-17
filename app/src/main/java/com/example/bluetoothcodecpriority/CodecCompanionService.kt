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
        val address = readAssociationAddress(association) ?: return
        handleConnection(address)
    }

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        handleConnection(address)
    }

    private fun handleConnection(address: String) {
        scope.launch {
            runCatching {
                withTimeout(8_000L) {
                    // Presence can precede A2DP readiness; use the bounded retry path.
                    BackgroundCodecApplier.apply(applicationContext, address)
                }
            }.onFailure { Log.w("CodecPriority", "Companion codec application failed", it) }
        }
    }
    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(association: AssociationInfo) { refreshStatus() }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) { refreshStatus() }

    @SuppressLint("NewApi")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        if (event.event == DevicePresenceEvent.EVENT_BT_DISCONNECTED) {
            refreshStatus()
            return
        }
        if (event.event != DevicePresenceEvent.EVENT_BT_CONNECTED) return
        scope.launch {
            runCatching {
                withTimeout(8_000L) {
                    val manager = getSystemService(android.companion.CompanionDeviceManager::class.java)
                    val association = manager.myAssociations.firstOrNull { it.id == event.associationId } ?: return@withTimeout
                    val address = readAssociationAddress(association) ?: return@withTimeout
                    Log.i("CodecPriority", "Companion presence: A2DP device connected $address")
                    BackgroundCodecApplier.apply(applicationContext, address)
                }
            }.onFailure { Log.w("CodecPriority", "Companion presence codec application failed", it) }
        }
    }

    private fun refreshStatus() {
        scope.launch {
            runCatching {
                withTimeout(4_000L) {
                    CodecStatusNotification.showConnected(applicationContext, CodecEngine(applicationContext).readConnected())
                }
            }.onFailure { Log.w("CodecPriority", "Companion status refresh failed", it) }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
