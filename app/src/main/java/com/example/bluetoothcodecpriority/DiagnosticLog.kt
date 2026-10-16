package com.example.bluetoothcodecpriority

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

private val Context.diagnosticDataStore by preferencesDataStore("diagnostics")
private val diagnosticEntriesKey = stringPreferencesKey("entries")

/** Local bounded event journal plus Logcat output for Bluetooth troubleshooting. */
class DiagnosticLog(context: Context) {
    private val applicationContext = context.applicationContext
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val entries: Flow<List<String>> = applicationContext.diagnosticDataStore.data.map { preferences ->
        preferences[diagnosticEntriesKey].orEmpty().lineSequence().filter(String::isNotBlank).toList()
    }

    fun write(message: String, error: Throwable? = null) {
        val safeMessage = redactSensitiveData(message)
        val safeError = error?.let { " | ${it.javaClass.simpleName}: ${redactSensitiveData(it.message.orEmpty())}" }.orEmpty()
        val line = "${Instant.now()} $safeMessage$safeError"
        if (error == null) Log.d("CodecPriority", safeMessage) else Log.e("CodecPriority", safeMessage, error)
        backgroundScope.launch {
            applicationContext.diagnosticDataStore.edit { preferences ->
                val current = preferences[diagnosticEntriesKey].orEmpty().lineSequence().filter(String::isNotBlank).toMutableList()
                current += line
                preferences[diagnosticEntriesKey] = current.takeLast(200).joinToString("\n")
            }
        }
    }

    suspend fun clear() {
        applicationContext.diagnosticDataStore.edit { it.remove(diagnosticEntriesKey) }
    }
}

private val bluetoothAddressPattern = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

fun redactSensitiveData(value: String): String = bluetoothAddressPattern.replace(value, "XX:XX:XX:XX:XX:XX")
