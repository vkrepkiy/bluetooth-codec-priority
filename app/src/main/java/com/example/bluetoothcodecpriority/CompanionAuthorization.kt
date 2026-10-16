package com.example.bluetoothcodecpriority

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.content.Context

/** Reads the user's explicit Companion authorization for a Bluetooth address. */
@SuppressLint("MissingPermission", "NewApi")
fun isCompanionAuthorized(context: Context, deviceAddress: String): Boolean {
    val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
    return runCatching {
        manager.myAssociations.any { association ->
            val address = if (android.os.Build.VERSION.SDK_INT >= 34) {
                association.associatedDevice?.bluetoothDevice?.address
            } else {
                association.deviceMacAddress?.toString()
            }
            address.equals(deviceAddress, ignoreCase = true)
        }
    }.getOrDefault(false)
}
