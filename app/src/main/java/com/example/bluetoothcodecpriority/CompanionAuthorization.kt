package com.example.bluetoothcodecpriority

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build

/** Reads the user's explicit Companion authorization for a Bluetooth address. */
@SuppressLint("MissingPermission", "NewApi")
fun isCompanionAuthorized(context: Context, deviceAddress: String): Boolean {
    val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
    return runCatching {
        listAuthorizedAddresses(manager).any { it.equals(deviceAddress, ignoreCase = true) }
    }.getOrDefault(false)
}

/** The persisted MAC survives process restarts; associatedDevice does not. */
@SuppressLint("NewApi")
fun readAssociationAddress(association: android.companion.AssociationInfo): String? =
    association.deviceMacAddress?.toString()

@Suppress("DEPRECATION")
fun listAuthorizedAddresses(manager: CompanionDeviceManager): List<String> =
    if (Build.VERSION.SDK_INT >= 33) manager.myAssociations.mapNotNull(::readAssociationAddress)
    else manager.associations
