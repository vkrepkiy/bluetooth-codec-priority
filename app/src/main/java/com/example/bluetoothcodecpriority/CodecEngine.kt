package com.example.bluetoothcodecpriority

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

data class CodecSnapshot(
    val deviceAddress: String,
    val deviceName: String,
    val activeCodec: CodecCapability?,
    val selectable: List<CodecCapability>,
    val sampleRate: String?,
    val bitsPerSample: String?,
    val detail: String,
    val isConnected: Boolean = false,
    val requestedCodec: CodecCapability? = null,
    val lastResult: String? = null,
    val lastResultDetail: String? = null
)

sealed interface ApplyResult {
    data class Success(val requested: CodecCapability, val active: CodecCapability?, val fallback: Boolean = false) : ApplyResult
    data class NoMatch(val selectable: List<CodecCapability>) : ApplyResult
    data class Restricted(val message: String) : ApplyResult
    data class Failed(val message: String, val requested: CodecCapability? = null) : ApplyResult
}

/**
 * Coalesces UI, broadcast, boot and companion triggers for one device.
 * The lock is deliberately process-local: after process death the next real
 * connection/boot event is allowed to apply again.
 */
private object ApplyCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ApplyResult>>()

    suspend fun run(address: String, priority: List<String>, block: suspend () -> ApplyResult): ApplyResult {
        val key = "$address|${priority.joinToString(",")}"
        val deferred = CompletableDeferred<ApplyResult>()
        val existing = inFlight.putIfAbsent(key, deferred)
        if (existing != null) {
            Log.i("CodecPriority", "apply: coalesced concurrent duplicate trigger for $address")
            return existing.await()
        }
        val lock = locks.getOrPut(address) { Mutex() }
        return try {
            val result = lock.withLock { block() }
            deferred.complete(result)
            result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, deferred)
        }
    }
}

/** Reflection boundary for hidden Bluetooth codec APIs. Every failure is surfaced to UI. */
class CodecEngine(private val context: Context) {
    private val diagnosticLog = DiagnosticLog(context)

    private companion object {
        /** One process-wide lock prevents UI and broadcast-triggered A2DP proxies racing. */
        val profileMutex = Mutex()
    }

    @SuppressLint("MissingPermission")
    suspend fun readConnected(): List<CodecSnapshot> = withContext(Dispatchers.IO) {
        diagnosticLog.write("readConnected: started")
        checkPermission()
        val settings = SettingsStore(context).settings.first()
        withA2dp { a2dp ->
            val devices = a2dp.connectedDevices
            diagnosticLog.write("readConnected: A2DP reports ${devices.size} connected device(s): ${devices.joinToString { it.name ?: it.address }}")
            if (devices.isEmpty()) diagnosticLog.write("readConnected: no A2DP headphones are connected")
            devices.mapNotNull { readStatus(a2dp, it, settings.deviceApplyStates[it.address]) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun apply(deviceAddress: String, priority: List<String>): ApplyResult = withContext(Dispatchers.IO) {
        val result = ApplyCoordinator.run(deviceAddress, priority) { applyInternal(deviceAddress, priority) }
        runCatching { SettingsStore(context).setDeviceApplyState(deviceAddress, result.toDeviceApplyState()) }
            .onFailure { diagnosticLog.write("apply: could not persist result", it) }
        result
    }

    @SuppressLint("MissingPermission")
    suspend fun isA2dpConnected(deviceAddress: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            checkPermission()
            withA2dp { a2dp -> a2dp.connectedDevices.any { it.address == deviceAddress } }
        }.getOrElse {
            diagnosticLog.write("presence: could not verify A2DP connection", it)
            false
        }
    }

    private suspend fun applyInternal(deviceAddress: String, priority: List<String>): ApplyResult = withContext(Dispatchers.IO) {
        try {
            val deadlineMs = SystemClock.elapsedRealtime() + 7_000L
            diagnosticLog.write("apply: started for $deviceAddress with priority ${priority.joinToString()}")
            checkPermission()
            withA2dp { a2dp ->
                diagnosticLog.write("apply: A2DP proxy connected")
                val device = a2dp.connectedDevices.firstOrNull { it.address == deviceAddress }
                    ?: return@withA2dp ApplyResult.Failed("A2DP device is no longer connected").also { diagnosticLog.write("apply: target is not in connected A2DP devices") }
                diagnosticLog.write("apply: target ${device.name ?: "Unknown"} ${device.address} is connected")
                val status = getCodecStatus(a2dp, device)
                    ?: return@withA2dp ApplyResult.Failed("Bluetooth did not expose codec status").also { diagnosticLog.write("apply: getCodecStatus returned null") }
                val selectableObjects = invokeList(status, "getCodecsSelectableCapabilities")
                val selectable = selectableObjects.map { describe(it) }
                diagnosticLog.write("apply: selectable codecs ${selectable.joinToString { it.name + "[" + it.id + "]" }}")
                val candidates = orderedCandidates(priority, selectable)
                if (candidates.isEmpty()) return@withA2dp ApplyResult.NoMatch(selectable).also { diagnosticLog.write("apply: no priority entry matched selectable codecs") }
                diagnosticLog.write("apply: candidates ${candidates.joinToString { it.name }}")
                var lastActive: CodecCapability? = null
                var lastError: String? = null
                var lastRequested: CodecCapability? = null
                for (chosen in candidates) {
                    if (SystemClock.elapsedRealtime() >= deadlineMs) {
                        diagnosticLog.write("apply: bounded time budget reached; stopping retries")
                        break
                    }
                    val chosenObject = selectableObjects.firstOrNull { describe(it).id == chosen.id } ?: continue
                    try {
                        lastRequested = chosen
                        diagnosticLog.write("apply: requesting ${chosen.name} (${chosen.id})")
                        val activeObject = getCodecStatus(a2dp, device)?.let { invokeObject(it, "getCodecConfig") }
                        var active = requestAndVerify(a2dp, device, chosen, chosenObject, activeObject, deadlineMs)
                        // Use the renegotiation bridge only for the user's top choice.
                        // Lower-priority fallbacks must remain short and must not keep reopening the session.
                        if (chosen.id == candidates.first().id && active?.id != chosen.id) {
                            val bridge = selectableObjects
                                .map { describe(it) to it }
                                .firstOrNull { (capability, _) -> capability.id != chosen.id && capability.id != active?.id }
                            if (bridge != null) {
                                diagnosticLog.write("apply: ${chosen.name} not active; two-step bridge via ${bridge.first.name}")
                                requestAndVerify(a2dp, device, bridge.first, bridge.second, activeObject, deadlineMs)
                                active = requestAndVerify(a2dp, device, chosen, chosenObject, activeObject, deadlineMs)
                            }
                        }
                        lastActive = active
                        if (active?.id == chosen.id) return@withA2dp ApplyResult.Success(chosen, active, chosen.id != candidates.first().id).also { diagnosticLog.write("apply: verified ${if (it.fallback) "fallback" else "success"} ${chosen.name}") }
                    } catch (security: SecurityException) {
                        throw security
                    } catch (hidden: ReflectiveOperationException) {
                        throw hidden
                    } catch (error: Throwable) {
                        lastError = error.message ?: error.javaClass.simpleName
                        diagnosticLog.write("apply: candidate ${chosen.name} failed; trying next", error)
                    }
                }
                ApplyResult.Failed("Tried ${candidates.joinToString { it.name }}; active codec is ${lastActive?.name ?: "unknown"}${lastError?.let { "; last error: $it" }.orEmpty()}", lastRequested)
            }
        } catch (security: SecurityException) {
            diagnosticLog.write("apply: restricted by Bluetooth permissions/API", security)
            ApplyResult.Restricted("Bluetooth codec preference is restricted by the system. Companion association or Shizuku/ADB may be required: ${security.message ?: "permission denied"}")
        } catch (hidden: ReflectiveOperationException) {
            diagnosticLog.write("apply: hidden codec API unavailable", hidden)
            ApplyResult.Restricted("Android hid the codec API on this device: ${hidden.message ?: hidden.javaClass.simpleName}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnosticLog.write("apply: failed", error)
            ApplyResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun requestAndVerify(
        a2dp: BluetoothA2dp,
        device: BluetoothDevice,
        chosen: CodecCapability,
        capability: Any,
        activeConfig: Any?,
        deadlineMs: Long
    ): CodecCapability? {
        invokeSetPreference(a2dp, device, buildRequest(capability, activeConfig))
        diagnosticLog.write("apply: setCodecConfigPreference returned for ${chosen.name}; read-back is the only success proof")
        var active: CodecCapability? = null
        for (attempt in 0 until 3) {
            val remaining = deadlineMs - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            Thread.sleep(minOf(350L * (attempt + 1), remaining))
            active = getCodecStatus(a2dp, device)?.let { statusObject ->
                invokeObject(statusObject, "getCodecConfig")?.let(::describe)
            }
            diagnosticLog.write("apply: verification attempt ${attempt + 1}/3 active=${active?.name ?: "unknown"}")
            if (active?.id == chosen.id) break
        }
        return active
    }

    @SuppressLint("MissingPermission")
    suspend fun knownLocalCodecs(): List<CodecCapability> = withContext(Dispatchers.IO) {
        checkPermission()
        withA2dp { a2dp ->
            runCatching {
                val method = BluetoothA2dp::class.java.getMethod("getSupportedCodecTypes")
                @Suppress("UNCHECKED_CAST") val types = method.invoke(a2dp) as? Collection<Any> ?: emptyList()
                types.mapNotNull { type ->
                    val name = runCatching { type.javaClass.getMethod("getCodecName").invoke(type)?.toString() }.getOrNull()
                        ?: type.toString().takeUnless { it.contains("BluetoothCodecType") }
                    name?.let { CodecCapability(normalizeCodecId(it), it) }
                }
            }.getOrDefault(emptyList())
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> withA2dp(block: (BluetoothA2dp) -> T): T = profileMutex.withLock {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth is unavailable")
        diagnosticLog.write("withA2dp: Bluetooth adapter=${adapter.isEnabled}")
        var result: Result<T>? = null
        var profileProxy: BluetoothA2dp? = null
        val latch = CountDownLatch(1)
        val connected = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                diagnosticLog.write("withA2dp: onServiceConnected profile=$profile")
                profileProxy = proxy as BluetoothA2dp
                latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.A2DP)
        if (!connected) error("Could not attach to the A2DP service")
        if (!latch.await(2_500L, TimeUnit.MILLISECONDS)) {
            profileProxy?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            error("A2DP service timed out")
        }
        val proxy = profileProxy ?: error("A2DP service returned no proxy")
        result = try {
            runCatching { block(proxy) }
        } finally {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }
        return result!!.getOrThrow()
    }

    private fun getCodecStatus(a2dp: BluetoothA2dp, device: BluetoothDevice): Any? =
        invokeObject(a2dp, "getCodecStatus", device)

    private fun invokeSetPreference(a2dp: BluetoothA2dp, device: BluetoothDevice, config: Any) {
        val method = BluetoothA2dp::class.java.methods.firstOrNull {
            it.name == "setCodecConfigPreference" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0].isAssignableFrom(device.javaClass) && it.parameterTypes[1].isAssignableFrom(config.javaClass)
        } ?: BluetoothA2dp::class.java.getDeclaredMethod("setCodecConfigPreference", BluetoothDevice::class.java, config.javaClass)
        method.isAccessible = true
        try { method.invoke(a2dp, device, config) } catch (error: InvocationTargetException) { throw (error.targetException as? Throwable ?: error) }
    }

    /** Builds a concrete codec config; capability bitmasks are not valid negotiated values on all OEM stacks. */
    private fun buildRequest(capability: Any, activeConfig: Any?): Any {
        return runCatching {
            val configClass = capability.javaClass
            val builderClass = Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            val setters = listOf("CodecType", "CodecPriority", "SampleRate", "BitsPerSample", "ChannelMode", "CodecSpecific1", "CodecSpecific2", "CodecSpecific3", "CodecSpecific4")
            setters.forEach { suffix ->
                val getter = configClass.methods.firstOrNull { it.name == "get$suffix" && it.parameterCount == 0 }
                val setter = builderClass.methods.firstOrNull { it.name == "set$suffix" && it.parameterCount == 1 }
                if (getter != null && setter != null) {
                val capabilityValue = getter.invoke(capability)
                val value = when (suffix) {
                    "CodecPriority" -> 1_000_000
                    "SampleRate", "BitsPerSample", "ChannelMode" -> selectConcreteValue(
                        capabilityValue as? Number,
                        activeConfig?.let { runCatching { getter.invoke(it) as Number }.getOrNull() }
                    )
                    else -> capabilityValue
                }
                setter.invoke(builder, value)
                }
            }
            builderClass.getMethod("build").invoke(builder).also { diagnosticLog.write("buildRequest: ${it.toString()}") }
        }.getOrDefault(capability)
    }

    /** Chooses one supported bit from an OEM capability mask, preferring the live value. */
    private fun selectConcreteValue(capabilityValue: Number?, activeValue: Number?): Int {
        val capabilityMask = capabilityValue?.toInt() ?: return activeValue?.toInt() ?: 0
        val current = activeValue?.toInt()
        if (current != null && current != 0 && capabilityMask and current != 0 && (current and (current - 1)) == 0) return current
        var bit = 1
        while (bit != 0) {
            if (capabilityMask and bit != 0) return bit
            bit = bit shl 1
        }
        return capabilityMask
    }

    @SuppressLint("MissingPermission")
    private fun readStatus(a2dp: BluetoothA2dp, device: BluetoothDevice, savedState: DeviceApplyState?): CodecSnapshot? = runCatching {
        val status = getCodecStatus(a2dp, device) ?: return null
        val selectable = invokeList(status, "getCodecsSelectableCapabilities").map(::describe)
        val active = invokeObject(status, "getCodecConfig")?.let(::describe)
        val activeConfig = invokeObject(status, "getCodecConfig")
        val reconciledState = reconcileApplyState(savedState, active?.id)
        CodecSnapshot(device.address, device.name ?: "Unknown device", active, selectable, activeConfig?.let { readMetric(it, "getSampleRate") }, activeConfig?.let { readMetric(it, "getBitsPerSample") }, "Read from BluetoothCodecStatus", true, reconciledState?.requestedCodec(), reconciledState?.status, reconciledState?.detail)
    }.getOrElse { CodecSnapshot(device.address, device.name ?: "Unknown device", null, emptyList(), null, null, "Codec status unavailable: ${it.message}", true, savedState?.requestedCodec(), savedState?.status, savedState?.detail) }

    private fun invokeList(target: Any, methodName: String): List<Any> = (invokeObject(target, methodName) as? List<*>)?.filterNotNull() ?: emptyList()
    private fun invokeObject(target: Any, methodName: String, vararg arguments: Any): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == arguments.size }
            ?: target.javaClass.getDeclaredMethod(methodName, *arguments.map { it.javaClass }.toTypedArray()).also { it.isAccessible = true }
        return method.invoke(target, *arguments)
    }

    private fun describe(config: Any): CodecCapability {
        val type = runCatching { config.javaClass.getMethod("getCodecType").invoke(config).toString() }.getOrDefault("unknown")
        val extendedObject = runCatching { config.javaClass.getMethod("getExtendedCodecType").invoke(config) }.getOrNull()
        val extended = extendedObject?.let { objectValue ->
            runCatching { objectValue.javaClass.getMethod("getCodecName").invoke(objectValue)?.toString() }.getOrNull()
                ?: objectValue.toString().takeUnless { it == "0" || it.contains("null") }
        }
        val standardName = when (type) {
            "0" -> "SBC"; "1" -> "AAC"; "2" -> "aptX"; "3" -> "aptX HD"; "4" -> "LDAC"; "5" -> "aptX Adaptive"; "6" -> "LC3"; else -> null
        }
        val extendedName = extended?.takeUnless { it.isBlank() || it == "0" || it.all(Char::isDigit) }
        val name = extendedName ?: standardName ?: "Vendor codec (type $type)"
        val id = normalizeCodecId(name)
        return CodecCapability(id, name)
    }

    private fun readMetric(config: Any, getterName: String): String? = runCatching {
        val value = (config.javaClass.getMethod(getterName).invoke(config) as Number).toInt()
        when (getterName) {
            "getSampleRate" -> listOf(1 to "44.1 kHz", 2 to "48 kHz", 4 to "88.2 kHz", 8 to "96 kHz", 16 to "176.4 kHz", 32 to "192 kHz")
            else -> listOf(1 to "16-bit", 2 to "24-bit", 4 to "32-bit")
        }.filter { value and it.first != 0 }.joinToString(", ") { it.second }.ifBlank { "unknown" }
    }.getOrNull()

    private fun normalizeCodecId(name: String): String = name.trim().lowercase()
        .replace("lhac", "lhdc")
        .replace("apt-x", "aptx")
        .replace(" ", "_")
        .replace("-", "_")

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = listOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
                .filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) throw SecurityException("Nearby devices permission is missing: ${missing.joinToString()}")
        }
    }
}

private fun ApplyResult.toDeviceApplyState(): DeviceApplyState = when (this) {
    is ApplyResult.Success -> DeviceApplyState(requested.id, requested.name, if (fallback) "FALLBACK" else "SUCCESS", "Verified active codec: ${active?.name ?: "unknown"}", System.currentTimeMillis())
    is ApplyResult.NoMatch -> DeviceApplyState(null, null, "NO_MATCH", "No requested codec is selectable", System.currentTimeMillis())
    is ApplyResult.Restricted -> DeviceApplyState(null, null, "RESTRICTED", message, System.currentTimeMillis())
    is ApplyResult.Failed -> DeviceApplyState(requested?.id, requested?.name, "FAILED", message, System.currentTimeMillis())
}
