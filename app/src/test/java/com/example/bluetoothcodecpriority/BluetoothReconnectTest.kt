package com.example.bluetoothcodecpriority

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Real Android objects exercise persistence and notification replacement, without a headset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class BluetoothReconnectTest {
    private lateinit var context: Context
    private lateinit var manager: CompanionDeviceManager
    private val address = "AA:BB:CC:DD:EE:01"

    @Before fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context = application
        manager = context.getSystemService(CompanionDeviceManager::class.java)
    }

    @Test fun restoredAssociationWithoutTransientDeviceStillAuthorizesCodecControl() {
        shadowOf(manager).addAssociation(address)
        val restoredAssociation = manager.myAssociations.single()
        assertNull(restoredAssociation.associatedDevice)
        assertEquals(address.lowercase(), readAssociationAddress(restoredAssociation)?.lowercase())
        assertTrue(isCompanionAuthorized(context, address.lowercase()))
        assertFalse(isCompanionAuthorized(context, "AA:BB:CC:DD:EE:02"))
    }

    @Test fun revokingAssociationRemovesAuthorization() {
        shadowOf(manager).addAssociation(address)
        assertTrue(isCompanionAuthorized(context, address))
        manager.disassociate(manager.myAssociations.single().id)
        assertFalse(isCompanionAuthorized(context, address))
    }

    @Test fun backgroundServiceObservesBluetoothWithoutActivityAndUnregistersOnDestroy() {
        val application = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(CodecStatusService::class.java).create()
        val serviceReceiver = ReflectionHelpers.getField<android.content.BroadcastReceiver>(controller.get(), "connectionReceiver")
        try {
            val receiver = shadowOf(application).registeredReceivers.single {
                it.broadcastReceiver === serviceReceiver && it.intentFilter.hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            }
            assertTrue(receiver.flags and Context.RECEIVER_EXPORTED != 0)
            assertEquals(0, receiver.flags and Context.RECEIVER_NOT_EXPORTED)
        } finally {
            controller.destroy()
        }
        assertFalse(shadowOf(application).registeredReceivers.any { it.broadcastReceiver === serviceReceiver })
    }

    @Test fun manifestReceiverCanReceiveEventsFromBluetoothProcess() {
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, BluetoothConnectionReceiver::class.java), 0)
        assertTrue(receiver.exported)
    }

    @Test fun disconnectClearsPreviousHeadsetNotification() {
        CodecStatusNotification.showConnected(context, listOf(createSnapshot("Shokz", "aac", "AAC")))
        assertEquals("Shokz", readNotification().extras.getString(Notification.EXTRA_TITLE))
        CodecStatusNotification.showConnected(context, emptyList())
        assertEquals("Bluetooth Codec Priority", readNotification().extras.getString(Notification.EXTRA_TITLE))
        assertEquals("No connected A2DP device", readNotification().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun switchingHeadsetsReplacesOneNotificationWithLiveCodec() {
        CodecStatusNotification.showConnected(context, listOf(createSnapshot("Shokz", "aac", "AAC")))
        CodecStatusNotification.showConnected(context, listOf(createSnapshot("AVENTHO 300", "aptx_adaptive", "aptX Adaptive")))
        val notification = readNotification()
        assertEquals("AVENTHO 300", notification.extras.getString(Notification.EXTRA_TITLE))
        val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("active aptX Adaptive"))
        assertFalse(text.contains("Shokz"))
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(1, notifications.size())
    }

    private fun createSnapshot(name: String, codecId: String, codecName: String) = CodecSnapshot(
        address, name, CodecCapability(codecId, codecName), emptyList(), null, null, "Live read-back", true
    )

    private fun readNotification(): Notification =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(CodecStatusNotification.notificationId)
}
