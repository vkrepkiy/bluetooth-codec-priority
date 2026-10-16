package com.example.bluetoothcodecpriority

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.util.Base64

private val Context.settingsDataStore by preferencesDataStore("settings")

data class AppSettings(
    val globalEnabled: Boolean = true,
    val globalPriority: List<String> = DEFAULT_PRIORITY,
    val deviceModes: Map<String, DeviceMode> = emptyMap(),
    val devicePriorities: Map<String, List<String>> = emptyMap(),
    val knownDevices: Map<String, String> = emptyMap(),
    val deviceApplyStates: Map<String, DeviceApplyState> = emptyMap(),
    val settingsVersion: Long = 0L
)

data class DeviceApplyState(
    val requestedCodecId: String? = null,
    val requestedCodecName: String? = null,
    val status: String,
    val detail: String,
    val updatedAtEpochMs: Long
) {
    fun requestedCodec(): CodecCapability? = requestedCodecId?.let { CodecCapability(it, requestedCodecName ?: it) }
}

val DEFAULT_PRIORITY = listOf("aptx_hd", "aptx_adaptive", "ldac", "aptx", "aac", "sbc")

class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            globalEnabled = preferences[booleanPreferencesKey("global_enabled")] ?: true,
            globalPriority = decode(preferences[stringPreferencesKey("global_priority")] ?: DEFAULT_PRIORITY.joinToString(",")),
            deviceModes = decodeMap(preferences[stringPreferencesKey("device_modes")] ?: ""),
            devicePriorities = decodeListMap(preferences[stringPreferencesKey("device_priorities")] ?: ""),
            knownDevices = decodeStringMap(preferences[stringPreferencesKey("known_devices")] ?: ""),
            deviceApplyStates = decodeApplyStateMap(preferences[stringPreferencesKey("device_apply_states")] ?: ""),
            settingsVersion = preferences[longPreferencesKey("settings_version")] ?: 0L
        )
    }

    suspend fun setGlobalEnabled(enabled: Boolean) = context.settingsDataStore.edit { it[booleanPreferencesKey("global_enabled")] = enabled; incrementSettingsVersion(it) }

    suspend fun setGlobalPriority(priority: List<String>) = context.settingsDataStore.edit { it[stringPreferencesKey("global_priority")] = encode(priority); incrementSettingsVersion(it) }

    suspend fun setDeviceMode(address: String, mode: DeviceMode) = context.settingsDataStore.edit {
        val current = decodeMap(it[stringPreferencesKey("device_modes")] ?: "").toMutableMap()
        current[address] = mode
        it[stringPreferencesKey("device_modes")] = current.entries.joinToString(";") { entry -> "${encodeToken(entry.key)}:${entry.value.name}" }
        incrementSettingsVersion(it)
    }

    suspend fun setDevicePriority(address: String, priority: List<String>) = context.settingsDataStore.edit {
        val current = decodeListMap(it[stringPreferencesKey("device_priorities")] ?: "").toMutableMap()
        current[address] = priority
        it[stringPreferencesKey("device_priorities")] = current.entries.joinToString(";") { entry -> "${encodeToken(entry.key)}:${encode(entry.value)}" }
        incrementSettingsVersion(it)
    }

    suspend fun rememberDevice(address: String, name: String) = context.settingsDataStore.edit {
        val current = decodeStringMap(it[stringPreferencesKey("known_devices")] ?: "").toMutableMap()
        current[address] = name.ifBlank { "Unknown device" }
        it[stringPreferencesKey("known_devices")] = encodeStringMap(current)
    }

    suspend fun setDeviceApplyState(address: String, state: DeviceApplyState) = context.settingsDataStore.edit {
        val current = decodeApplyStateMap(it[stringPreferencesKey("device_apply_states")] ?: "").toMutableMap()
        current[address] = state
        it[stringPreferencesKey("device_apply_states")] = encodeApplyStateMap(current)
    }
}

private fun incrementSettingsVersion(preferences: androidx.datastore.preferences.core.MutablePreferences) {
    val key = longPreferencesKey("settings_version")
    preferences[key] = (preferences[key] ?: 0L) + 1L
}

private fun encode(values: List<String>): String = values.joinToString(",", transform = ::encodeToken)
private fun decode(value: String): List<String> {
    val encoded = value.split(',').mapNotNull(::decodeToken)
    return if (encoded.size == value.split(',').count { it.isNotBlank() }) encoded else value.split(',').map(String::trim).filter(String::isNotEmpty)
}
private fun decodeMap(value: String): Map<String, DeviceMode> = value.split(';').mapNotNull { item ->
    val pair = item.split(':', limit = 2)
    if (pair.size != 2) null else runCatching { (decodeToken(pair[0]) ?: pair[0]) to DeviceMode.valueOf(pair[1]) }.getOrNull()
}.toMap()
private fun decodeListMap(value: String): Map<String, List<String>> = value.split(';').mapNotNull { item ->
    val pair = item.split(':', limit = 2)
    if (pair.size != 2) null else (decodeToken(pair[0]) ?: pair[0]) to decode(pair[1])
}.toMap()

private fun decodeStringMap(value: String): Map<String, String> = value.split(';').mapNotNull { item ->
    val pair = item.split(':', limit = 2)
    if (pair.size != 2) null else runCatching { decodeToken(pair[0])!! to decodeToken(pair[1])!! }.getOrNull()
}.toMap()

private fun decodeApplyStateMap(value: String): Map<String, DeviceApplyState> = value.split(';').mapNotNull { item ->
    val fields = item.split(':')
    if (fields.size != 6) return@mapNotNull null
    runCatching {
        val address = decodeToken(fields[0]) ?: return@runCatching null
        val requestedId = decodeToken(fields[1]).orEmpty().ifBlank { null }
        val requestedName = decodeToken(fields[2]).orEmpty().ifBlank { null }
        val status = decodeToken(fields[3]) ?: return@runCatching null
        val detail = decodeToken(fields[4]) ?: return@runCatching null
        val updatedAt = decodeToken(fields[5])?.toLongOrNull() ?: return@runCatching null
        address to DeviceApplyState(requestedId, requestedName, status, detail, updatedAt)
    }.getOrNull()
}.toMap()

private fun encodeToken(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
private fun decodeToken(value: String): String? = runCatching { String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8) }.getOrNull()
private fun encodeStringMap(values: Map<String, String>): String = values.entries.joinToString(";") { "${encodeToken(it.key)}:${encodeToken(it.value)}" }

private fun encodeApplyStateMap(values: Map<String, DeviceApplyState>): String = values.entries.joinToString(";") { (address, state) ->
    listOf(address, state.requestedCodecId.orEmpty(), state.requestedCodecName.orEmpty(), state.status, state.detail, state.updatedAtEpochMs.toString()).joinToString(":", transform = ::encodeToken)
}
