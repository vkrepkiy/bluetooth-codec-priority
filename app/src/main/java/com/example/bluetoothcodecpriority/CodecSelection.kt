package com.example.bluetoothcodecpriority

/** A codec reported by the phone or Bluetooth stack. */
data class CodecCapability(val id: String, val name: String)

/** Returns every distinct candidate in the user's order, preserving stack-reported identity. */
fun orderedCandidates(priority: List<String>, available: List<CodecCapability>): List<CodecCapability> {
    val byId = available.associateBy { it.id }
    return priority.mapNotNull(byId::get).distinctBy(CodecCapability::id)
}

/** Selects the first item in [priority] that is present in [available]. */
fun selectHighestPriority(priority: List<String>, available: List<CodecCapability>): CodecCapability? {
    return orderedCandidates(priority, available).firstOrNull()
}

/** Moves [codecId] to [targetIndex], preserving every other item's order. */
fun moveCodec(priority: List<String>, codecId: String, targetIndex: Int): List<String> {
    val sourceIndex = priority.indexOf(codecId)
    if (sourceIndex < 0 || targetIndex !in priority.indices || sourceIndex == targetIndex) return priority
    return priority.toMutableList().also {
        val codec = it.removeAt(sourceIndex)
        it.add(targetIndex.coerceIn(0, it.size), codec)
    }
}

/** Marks a previously successful application as stale when the live codec disagrees. */
fun reconcileApplyState(state: DeviceApplyState?, activeCodecId: String?): DeviceApplyState? {
    if (state == null || (state.status != "SUCCESS" && state.status != "FALLBACK") || activeCodecId == null || state.requestedCodecId == activeCodecId) return state
    return state.copy(status = "MISMATCH", detail = "Saved success is stale; active codec is $activeCodecId")
}

/** Resolves whether a device is managed and which ordered list applies. */
fun resolvePriority(globalEnabled: Boolean, global: List<String>, mode: DeviceMode, custom: List<String>): List<String>? = when (mode) {
    DeviceMode.SYSTEM_DEFAULT -> null
    DeviceMode.CUSTOM -> custom
    DeviceMode.GLOBAL -> if (globalEnabled) global else null
}

enum class DeviceMode { GLOBAL, CUSTOM, SYSTEM_DEFAULT }
