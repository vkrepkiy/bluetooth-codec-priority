package com.example.bluetoothcodecpriority

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Applies the persisted device policy after a background Bluetooth event. */
object BackgroundCodecApplier {
    suspend fun apply(context: Context, deviceAddress: String): CodecSnapshot? = applyCurrent(context, deviceAddress, true)

    private suspend fun applyCurrent(context: Context, deviceAddress: String, allowPolicyRestart: Boolean): CodecSnapshot? {
        val log = DiagnosticLog(context)
        val store = SettingsStore(context)
        val settings = store.settings.first()
        if (!isCompanionAuthorized(context, deviceAddress)) {
            log.write("background: $deviceAddress is not Companion-authorized; codec apply skipped")
            val snapshots = CodecEngine(context).readConnected()
            CodecStatusNotification.showConnected(context, snapshots)
            return snapshots.firstOrNull { it.deviceAddress == deviceAddress }
        }
        val mode = settings.deviceModes[deviceAddress] ?: DeviceMode.GLOBAL
        val priority = resolvePriority(settings.globalEnabled, settings.globalPriority, mode, settings.devicePriorities[deviceAddress].orEmpty())
        if (priority == null) {
            log.write("background: $deviceAddress unmanaged; leaving codec unchanged")
            val snapshots = CodecEngine(context).readConnected()
            CodecStatusNotification.showConnected(context, snapshots)
            return snapshots.firstOrNull { it.deviceAddress == deviceAddress }
        }

        val engine = CodecEngine(context)
        val initialSettingsVersion = settings.settingsVersion
        var result: ApplyResult? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(500L * attempt)
            log.write("background: apply attempt ${attempt + 1}/3 for $deviceAddress")
            result = engine.apply(deviceAddress, priority)
            if (result !is ApplyResult.Failed) break
            log.write("background: attempt ${attempt + 1}/3 failed; waiting for A2DP readiness")
        }
        val latestSettings = store.settings.first()
        if (allowPolicyRestart && latestSettings.settingsVersion != initialSettingsVersion) {
            log.write("background: settings changed during apply; restarting with current policy")
            return applyCurrent(context, deviceAddress, false)
        }
        val snapshots = engine.readConnected()
        val snapshot = snapshots.firstOrNull { it.deviceAddress == deviceAddress }
        if (snapshot != null) {
            CodecStatusNotification.showConnected(context, snapshots)
            log.write("background: live status active=${snapshot.activeCodec?.name ?: "unknown"} result=${snapshot.lastResult ?: result?.javaClass?.simpleName ?: "none"}")
        } else {
            log.write("background: device $deviceAddress is no longer in connected A2DP devices")
        }
        return snapshot
    }
}
