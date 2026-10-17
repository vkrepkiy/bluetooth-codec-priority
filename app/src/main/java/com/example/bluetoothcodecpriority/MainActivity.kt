package com.example.bluetoothcodecpriority

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentSender
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class AppScreen { HOME, DEVICES, PRIORITY, DIAGNOSTICS, ABOUT }
private enum class AssociationState { AUTHORIZED, PENDING, NOT_AUTHORIZED }

class MainActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private lateinit var engine: CodecEngine
    private var refreshJob: Job? = null
    private var permissionRefreshToken by mutableIntStateOf(0)
    private var connectionRefreshToken by mutableIntStateOf(0)
    private var connectionReceiverRegistered = false
    private val connectionEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                android.util.Log.i("CodecPriority", "Activity received A2DP state event")
                connectionRefreshToken++
            }
        }
    }
    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRefreshToken++ }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefreshToken++ }
    private val companionAssociation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        android.util.Log.i("CodecPriority", "Companion authorization activity finished resultCode=${result.resultCode}")
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = SettingsStore(this)
        engine = CodecEngine(this)
        if (missingBluetoothPermissions().isNotEmpty()) requestBluetooth.launch(missingBluetoothPermissions().toTypedArray())
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        CodecStatusNotification.createChannel(this)
        androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, CodecStatusService::class.java))
        setContent { CodecApp() }
    }

    override fun onStart() {
        super.onStart()
        if (connectionReceiverRegistered) return
        val filter = IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        ContextCompat.registerReceiver(this, connectionEventsReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        connectionReceiverRegistered = true
        connectionRefreshToken++
    }

    override fun onStop() {
        refreshJob?.cancel()
        if (connectionReceiverRegistered) {
            unregisterReceiver(connectionEventsReceiver)
            connectionReceiverRegistered = false
        }
        super.onStop()
    }

    @Composable
    private fun CodecApp() {
        val settings by store.settings.collectAsState(initial = AppSettings())
        val diagnosticLog = remember { DiagnosticLog(this@MainActivity) }
        val logEntries by diagnosticLog.entries.collectAsState(initial = emptyList())
        var screen by remember { mutableStateOf(AppScreen.HOME) }
        var devices by remember { mutableStateOf<List<CodecSnapshot>>(emptyList()) }
        var phoneCapabilities by remember { mutableStateOf<List<CodecCapability>?>(null) }
        var requestedByDevice by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var resultByDevice by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        val associationStates = remember { mutableStateMapOf<String, AssociationState>() }
        var message by remember { mutableStateOf(getString(R.string.status_ready)) }
        fun refresh(reapplyConnected: Boolean = false) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            refreshJob?.cancel()
            refreshJob = lifecycleScope.launch {
                runCatching {
                    var snapshots = engine.readConnected()
                    if (reapplyConnected) {
                        DiagnosticLog(this@MainActivity).write("ui: reconciling ${snapshots.size} connected device(s)")
                        snapshots.forEach { BackgroundCodecApplier.apply(this@MainActivity, it.deviceAddress) }
                        snapshots = engine.readConnected()
                    }
                    snapshots
                }.onSuccess { snapshots ->
                    if (snapshots.isEmpty()) CodecStatusNotification.showWaiting(this@MainActivity) else CodecStatusNotification.showConnected(this@MainActivity, snapshots)
                    snapshots.forEach { store.rememberDevice(it.deviceAddress, it.deviceName) }
                    requestedByDevice = snapshots.mapNotNull { snapshot -> snapshot.requestedCodec?.id?.let { snapshot.deviceAddress to it } }.toMap()
                    resultByDevice = snapshots.mapNotNull { snapshot -> snapshot.lastResult?.let { snapshot.deviceAddress to it } }.toMap()
                    devices = snapshots
                    refreshAssociationStates(associationStates, snapshots.map { it.deviceAddress } + settings.knownDevices.keys)
                }.onFailure { error -> if (error !is kotlinx.coroutines.CancellationException) { message = error.message ?: getString(R.string.status_bluetooth_unavailable); devices = emptyList() } }
            }
        }
        LaunchedEffect(permissionRefreshToken) {
            refresh(reapplyConnected = true)
            phoneCapabilities = runCatching { engine.knownLocalCodecs() }.onFailure { message = getString(R.string.phone_capabilities_unknown) }.getOrNull()
            refreshAssociationStates(associationStates, settings.knownDevices.keys)
        }
        LaunchedEffect(connectionRefreshToken) { refresh(reapplyConnected = true) }
        val allDevices = (devices + settings.knownDevices.filterKeys { address -> devices.none { it.deviceAddress == address } }.map { (address, name) ->
            val savedState = settings.deviceApplyStates[address]
            CodecSnapshot(address, name, null, emptyList(), null, null, getString(R.string.device_not_connected), false, savedState?.requestedCodec(), savedState?.status, savedState?.detail)
        }).distinctBy { it.deviceAddress }
        val permissionMissing = missingBluetoothPermissions().isNotEmpty()
        Scaffold(bottomBar = { NavigationBar {
            NavigationBarItem(screen == AppScreen.HOME, { screen = AppScreen.HOME }, icon = {}, label = { Text(stringResource(R.string.nav_home)) })
            NavigationBarItem(screen == AppScreen.DEVICES, { screen = AppScreen.DEVICES }, icon = {}, label = { Text(stringResource(R.string.nav_devices)) })
        } }) { padding ->
            when (screen) {
                AppScreen.HOME -> HomeScreen(settings, devices, associationStates, phoneCapabilities, message, permissionMissing, { requestBluetooth.launch(missingBluetoothPermissions().toTypedArray()) }, { lifecycleScope.launch { store.setGlobalEnabled(it); refresh(reapplyConnected = true) } }, { screen = AppScreen.PRIORITY }, { screen = AppScreen.DIAGNOSTICS }, { screen = AppScreen.ABOUT }, ::refresh, { name, address -> associationStates[address] = AssociationState.PENDING; associateDevice(name, address) { associationStates[address] = it } }, { address, priority -> lifecycleScope.launch { val result = engine.apply(address, priority); requestedByDevice = requestedByDevice + (address to result.requestedCodecId()); resultByDevice = resultByDevice + (address to result.toUiStatus()); message = result.toMessage(); refresh() } }, requestedByDevice, resultByDevice, padding)
                AppScreen.DEVICES -> DevicesScreen(settings, allDevices, devices.map { it.deviceAddress }.toSet(), associationStates, { address, mode -> lifecycleScope.launch { store.setDeviceMode(address, mode); refresh(reapplyConnected = true) } }, { address, priority -> lifecycleScope.launch { store.setDevicePriority(address, priority); refresh(reapplyConnected = true) } }, { name, address -> associationStates[address] = AssociationState.PENDING; associateDevice(name, address) { associationStates[address] = it } }, { address, priority -> lifecycleScope.launch { val result = engine.apply(address, priority); message = result.toMessage(); refresh() } }, padding)
                AppScreen.PRIORITY -> PriorityScreen(settings.globalPriority, devices.flatMap { it.selectable }.distinctBy { it.id }, { screen = AppScreen.HOME }, { priority -> lifecycleScope.launch { store.setGlobalPriority(priority); refresh(reapplyConnected = true) } }) { priority -> lifecycleScope.launch { store.setGlobalPriority(priority); screen = AppScreen.HOME; refresh(reapplyConnected = true) } }
                AppScreen.DIAGNOSTICS -> DiagnosticsScreen(logEntries, { screen = AppScreen.HOME }, { copyDiagnosticLog(logEntries) }) { lifecycleScope.launch { diagnosticLog.clear() } }
                AppScreen.ABOUT -> AboutScreen { screen = AppScreen.HOME }
            }
        }
    }

    @Composable
    private fun HomeScreen(settings: AppSettings, devices: List<CodecSnapshot>, associationStates: Map<String, AssociationState>, phoneCapabilities: List<CodecCapability>?, message: String, permissionMissing: Boolean, requestPermissions: () -> Unit, setEnabled: (Boolean) -> Unit, openPriority: () -> Unit, openDiagnostics: () -> Unit, openAbout: () -> Unit, refresh: () -> Unit, authorize: (String, String) -> Unit, reapply: (String, List<String>) -> Unit, requestedByDevice: Map<String, String>, resultByDevice: Map<String, String>, padding: PaddingValues) {
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ScreenHeader(stringResource(R.string.app_name), stringResource(R.string.home_subtitle))
            if (permissionMissing) { StatusCard(stringResource(R.string.bluetooth_permission_required), true); Button(requestPermissions, Modifier.fillMaxWidth()) { Text(stringResource(R.string.grant_bluetooth_access)) }; Spacer(Modifier.height(12.dp)) }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.global_priority), style = MaterialTheme.typography.titleMedium); Text(if (settings.globalEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled), style = MaterialTheme.typography.bodySmall) }; Switch(settings.globalEnabled, setEnabled) }
                Spacer(Modifier.height(8.dp)); InfoLine(stringResource(R.string.phone_capabilities), phoneCapabilities?.joinToString { it.name }?.ifBlank { stringResource(R.string.none_reported) } ?: stringResource(R.string.unknown)); OutlinedButton(openPriority, Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_priority, settings.globalPriority.size)) }
            } }
            Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.connected_devices), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { if (devices.isEmpty()) item { EmptyCard(stringResource(R.string.no_connected_devices)) }; items(devices, key = { it.deviceAddress }) { device -> DeviceStatusCard(device = device, settings = settings, isConnected = true, associationState = associationStates[device.deviceAddress] ?: AssociationState.NOT_AUTHORIZED, requestedCodec = requestedByDevice[device.deviceAddress] ?: device.requestedCodec?.id, result = resultByDevice[device.deviceAddress] ?: device.lastResult, authorize = { deviceName, deviceAddress -> authorize(deviceName, deviceAddress) }, reapply = reapply) } }
            Spacer(Modifier.height(8.dp)); Text(message, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { OutlinedButton(refresh, Modifier.weight(1f)) { Text(stringResource(R.string.refresh)) }; OutlinedButton(openDiagnostics, Modifier.weight(1f)) { Text(stringResource(R.string.diagnostics)) }; OutlinedButton(openAbout, Modifier.weight(1f)) { Text(stringResource(R.string.about)) } }
        }
    }

    @Composable
    private fun DeviceStatusCard(device: CodecSnapshot, settings: AppSettings, isConnected: Boolean, associationState: AssociationState, requestedCodec: String?, result: String?, authorize: (String, String) -> Unit, reapply: (String, List<String>) -> Unit) {
        val mode = settings.deviceModes[device.deviceAddress] ?: DeviceMode.GLOBAL
        val effective = resolvePriority(settings.globalEnabled, settings.globalPriority, mode, settings.devicePriorities[device.deviceAddress].orEmpty())
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(device.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (isConnected) stringResource(R.string.connected) else stringResource(R.string.disconnected), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp)); InfoLine(stringResource(R.string.active_codec), device.activeCodec?.name ?: stringResource(R.string.codec_status_unavailable)); InfoLine(stringResource(R.string.requested_codec), requestedCodec?.let(::codecTitle) ?: stringResource(R.string.not_requested)); InfoLine(stringResource(R.string.selectable_codecs), device.selectable.joinToString { it.name }.ifBlank { stringResource(R.string.unknown) }); InfoLine(stringResource(R.string.audio), listOfNotNull(device.sampleRate, device.bitsPerSample).joinToString(" · ").ifBlank { stringResource(R.string.unknown) }); InfoLine(stringResource(R.string.control_mode), mode.toLabel()); InfoLine(stringResource(R.string.switching_result), result ?: stringResource(R.string.no_result)); Text(stringResource(R.string.association_status, associationState.toLabel()), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (!isConnected) {
                Text(stringResource(R.string.connect_to_reapply), style = MaterialTheme.typography.bodySmall)
            } else if (associationState != AssociationState.AUTHORIZED) {
                OutlinedButton({ authorize(device.deviceName, device.deviceAddress) }, Modifier.fillMaxWidth()) { Text(if (associationState == AssociationState.PENDING) stringResource(R.string.authorization_pending) else stringResource(R.string.authorize_codec_control)) }
            } else if (isConnected && effective != null) {
                Button({ reapply(device.deviceAddress, effective) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.reapply_now)) }
            } else {
                Text(stringResource(R.string.connect_to_reapply), style = MaterialTheme.typography.bodySmall)
            }
        } }
    }

    @Composable
    private fun PriorityScreen(savedPriority: List<String>, confirmed: List<CodecCapability>, close: () -> Unit, persist: (List<String>) -> Unit, save: (List<String>) -> Unit) {
        var priority by remember(savedPriority) { mutableStateOf(savedPriority) }
        fun move(codecId: String, targetIndex: Int) {
            val updated = moveCodec(priority, codecId, targetIndex)
            if (updated != priority) {
                priority = updated
                persist(updated)
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) { ScreenHeader(stringResource(R.string.priority_order), stringResource(R.string.priority_explanation), close); Text(stringResource(R.string.confirmed_codecs, confirmed.joinToString { it.name }.ifBlank { stringResource(R.string.unknown) }), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)); LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(priority, key = { _, id -> id }) { index, id -> CodecRow(id, index, priority.size) { target -> move(id, target) } } }; Button({ save(priority) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_priority)) } }
    }

    @Composable
    private fun DevicesScreen(settings: AppSettings, devices: List<CodecSnapshot>, connectedAddresses: Set<String>, associationStates: Map<String, AssociationState>, setMode: (String, DeviceMode) -> Unit, setPriority: (String, List<String>) -> Unit, associate: (String, String) -> Unit, reapply: (String, List<String>) -> Unit, padding: PaddingValues) {
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { ScreenHeader(stringResource(R.string.known_devices), stringResource(R.string.devices_explanation)); Text(stringResource(R.string.background_setup_hint), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)); LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { if (devices.isEmpty()) item { EmptyCard(stringResource(R.string.no_known_devices)) }; items(devices, key = { it.deviceAddress }) { device ->
            var customPriority by remember(device.deviceAddress, settings.devicePriorities[device.deviceAddress]) { mutableStateOf(settings.devicePriorities[device.deviceAddress] ?: settings.globalPriority) }
            fun moveCustom(codecId: String, targetIndex: Int) {
                val updated = moveCodec(customPriority, codecId, targetIndex)
                if (updated != customPriority) {
                    customPriority = updated
                    setPriority(device.deviceAddress, updated)
                }
            }
            val mode = settings.deviceModes[device.deviceAddress] ?: DeviceMode.GLOBAL
            val connected = connectedAddresses.contains(device.deviceAddress)
            Column {
                DeviceStatusCard(device = device, settings = settings, isConnected = connected, associationState = associationStates[device.deviceAddress] ?: AssociationState.NOT_AUTHORIZED, requestedCodec = device.requestedCodec?.id, result = device.lastResult, authorize = associate, reapply = reapply)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(device.deviceAddress, style = MaterialTheme.typography.labelSmall); Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.device_mode), style = MaterialTheme.typography.labelLarge); DeviceModeOption(stringResource(R.string.mode_global), mode == DeviceMode.GLOBAL) { setMode(device.deviceAddress, DeviceMode.GLOBAL) }; DeviceModeOption(stringResource(R.string.mode_custom), mode == DeviceMode.CUSTOM) { setMode(device.deviceAddress, DeviceMode.CUSTOM) }; DeviceModeOption(stringResource(R.string.mode_system_default), mode == DeviceMode.SYSTEM_DEFAULT) { setMode(device.deviceAddress, DeviceMode.SYSTEM_DEFAULT) }
                    if (mode == DeviceMode.CUSTOM) { customPriority.forEachIndexed { index, codec -> CodecRow(codec, index, customPriority.size) { target -> moveCustom(codec, target) } }; Button({ setPriority(device.deviceAddress, customPriority) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_custom_priority)) } }
                } }
            }
        } } }
    }

    @Composable private fun DiagnosticsScreen(entries: List<String>, close: () -> Unit, copy: () -> Unit, clear: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { ScreenHeader(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_explanation), close); Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { Button(copy, Modifier.weight(1f)) { Text(stringResource(R.string.copy_log)) }; OutlinedButton(clear, Modifier.weight(1f)) { Text(stringResource(R.string.clear_log)) } }; Spacer(Modifier.height(8.dp)); LazyColumn(Modifier.weight(1f)) { items(entries) { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 3.dp)) } } } }
    @Composable private fun AboutScreen(close: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { ScreenHeader(stringResource(R.string.about_support), stringResource(R.string.about_subtitle), close); LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { item { AboutCard(stringResource(R.string.android_support), stringResource(R.string.android_support_value)) }; item { AboutCard(stringResource(R.string.tested_hardware), stringResource(R.string.tested_hardware_value)) }; item { AboutCard(stringResource(R.string.permissions), stringResource(R.string.permissions_value)) }; item { AboutCard(stringResource(R.string.limitations), stringResource(R.string.limitations_value)) }; item { AboutCard(stringResource(R.string.background_behavior), stringResource(R.string.background_behavior_value)) } } } }

    @Composable private fun ScreenHeader(title: String, subtitle: String, close: (() -> Unit)? = null) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; if (close != null) OutlinedButton(close) { Text(stringResource(R.string.close)) } }; Spacer(Modifier.height(12.dp)) }
    @Composable private fun InfoLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text("$label: ", fontWeight = FontWeight.SemiBold); Text(value) } }
    @Composable private fun StatusCard(text: String, error: Boolean) { Surface(shape = RoundedCornerShape(12.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(12.dp)) } }
    @Composable private fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp)) } }
    @Composable private fun AboutCard(title: String, body: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(body) } } }
    @Composable private fun DeviceModeOption(label: String, selected: Boolean, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label) } }

    private fun missingBluetoothPermissions(): List<String> = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED } else emptyList()
    private fun copyDiagnosticLog(entries: List<String>) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bluetooth Codec Priority log", redactSensitiveData(entries.joinToString("\n")))) }

    @Suppress("DEPRECATION", "NewApi")
    private fun associateDevice(deviceName: String, deviceAddress: String, reportState: (AssociationState) -> Unit = {}) {
        val manager = getSystemService(CompanionDeviceManager::class.java); val request = AssociationRequest.Builder().addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(deviceAddress).build()).setSingleDevice(true).build(); android.util.Log.i("CodecPriority", "Companion association requested for $deviceName $deviceAddress")
        runCatching { manager.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) { runCatching { companionAssociation.launch(androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()) }.onFailure { error -> android.util.Log.e("CodecPriority", "Companion authorization UI could not be opened", error) } }
            override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) { reportState(AssociationState.AUTHORIZED); android.util.Log.i("CodecPriority", "Companion association created id=${associationInfo.id} device=$deviceAddress"); runCatching { if (android.os.Build.VERSION.SDK_INT >= 36) manager.startObservingDevicePresence(android.companion.ObservingDevicePresenceRequest.Builder().setAssociationId(associationInfo.id).build()) else if (android.os.Build.VERSION.SDK_INT >= 31) manager.startObservingDevicePresence(deviceAddress); android.util.Log.i("CodecPriority", "Companion presence observation started for $deviceAddress") }.onFailure { error -> android.util.Log.w("CodecPriority", "Companion presence observation unavailable", error) } }
            override fun onFailure(error: CharSequence?) { reportState(AssociationState.NOT_AUTHORIZED); android.util.Log.w("CodecPriority", "Companion association failed for $deviceName $deviceAddress: $error") }
        }, Handler(Looper.getMainLooper())) }.onFailure { failure -> reportState(AssociationState.NOT_AUTHORIZED); android.util.Log.e("CodecPriority", "Companion association request failed for $deviceName $deviceAddress", failure) }
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun refreshAssociationStates(states: MutableMap<String, AssociationState>, addresses: Collection<String>) {
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return
        val authorized = runCatching { listAuthorizedAddresses(manager) }.getOrDefault(emptyList())
        addresses.distinct().forEach { address ->
            states[address] = if (authorized.any { it.equals(address, ignoreCase = true) }) AssociationState.AUTHORIZED
            else if (states[address] == AssociationState.PENDING) AssociationState.PENDING
            else AssociationState.NOT_AUTHORIZED
        }
    }
}

private fun codecTitle(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun ApplyResult.requestedCodecId(): String = when (this) {
    is ApplyResult.Success -> requested.id
    is ApplyResult.Failed -> requested?.id.orEmpty()
    is ApplyResult.NoMatch, is ApplyResult.Restricted -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodecRow(codec: String, index: Int, size: Int, onMove: (Int) -> Unit) {
    var accumulatedDistance by remember(codec, index) { mutableFloatStateOf(0f) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().pointerInput(codec, index) { detectDragGesturesAfterLongPress(onDragStart = { accumulatedDistance = 0f }, onDragCancel = { accumulatedDistance = 0f }, onDragEnd = { accumulatedDistance = 0f }) { _, dragAmount -> accumulatedDistance += dragAmount.y; if (accumulatedDistance > 48f && index < size - 1) { onMove(index + 1); accumulatedDistance = 0f } else if (accumulatedDistance < -48f && index > 0) { onMove(index - 1); accumulatedDistance = 0f } } }) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}", Modifier.padding(end = 16.dp), fontWeight = FontWeight.SemiBold); Text(codecTitle(codec)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = index > 0, onClick = { onMove(index - 1) }) { Text("↑") }
                TextButton(enabled = index < size - 1, onClick = { onMove(index + 1) }) { Text("↓") }
                Text("☷", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

private fun DeviceMode.toLabel(): String = when (this) { DeviceMode.GLOBAL -> "Global"; DeviceMode.CUSTOM -> "Custom"; DeviceMode.SYSTEM_DEFAULT -> "System default" }
private fun AssociationState.toLabel(): String = when (this) { AssociationState.AUTHORIZED -> "Authorized"; AssociationState.PENDING -> "Pending"; AssociationState.NOT_AUTHORIZED -> "Not authorized" }
private fun ApplyResult.toMessage(): String = when (this) { is ApplyResult.Success -> "${if (fallback) "Fallback verified" else "Success"}: requested ${requested.name}; active ${active?.name ?: "not readable"}."; is ApplyResult.NoMatch -> "No requested codec is selectable. Reported: ${selectable.joinToString { it.name }}"; is ApplyResult.Restricted -> "Restricted: $message"; is ApplyResult.Failed -> "Failed: $message" }
private fun ApplyResult.toUiStatus(): String = when (this) { is ApplyResult.Success -> if (fallback) "FALLBACK VERIFIED" else "SUCCESS"; is ApplyResult.NoMatch -> "UNSUPPORTED"; is ApplyResult.Restricted -> "RESTRICTED"; is ApplyResult.Failed -> "FAILED" }
