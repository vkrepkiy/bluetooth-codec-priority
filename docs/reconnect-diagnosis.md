# Headset handover diagnosis (0.2.1)

Reported: after Shokz was put away and AVENTHO 300 connected, the notification still named Shokz and the headset reported aptX Adaptive rather than the requested aptX HD. The phone was not available over ADB, so the exact execution sequence is not reconstructed from device logs.

## Confirmed defects in 0.2.0

1. On API 34+, authorization, UI association state and Companion callbacks used `associatedDevice.bluetoothDevice.address`. Android does not persist `associatedDevice` across sessions. A restored association could therefore be treated as absent and background application skipped. All paths now use the persisted `deviceMacAddress`; the pre-33 authorization path uses the legacy address list.
2. The Activity receiver was `RECEIVER_NOT_EXPORTED` and the manifest receiver was `exported=false`. Bluetooth broadcasts can originate from a privileged Bluetooth process with its own UID. The receivers are now exported for protected system actions.
3. The status service held a notification but registered no receiver. It now registers an A2DP receiver independently of the Activity and reconciles once on creation. There is no periodic polling.
4. Companion presence checked A2DP readiness once and abandoned early arrivals. It now uses the bounded background apply/retry path. An empty selectable capability list is also retryable. Companion disconnects now refresh status.
5. `showConnected(emptyList())` returned without replacing the old notification; a completed apply also skipped notification updates when its target disappeared. Both paths now display the current connection list, including the empty state.
6. A connected device with a null codec status was dropped from snapshots. It now remains visible with an unavailable codec instead of being presented as disconnected.

These defects explain plausible ways to produce the report, but do not prove which paths ran on the phone. The existing codec request/negotiation algorithm is preserved; only live codec read-back can establish that aptX HD actually became active.

## Automated coverage

`BluetoothReconnectTest` uses Robolectric with Android 14/API 34 and Android 15/API 35. It exercises restored associations with a null transient device, revocation, runtime receiver registration without an Activity, receiver cleanup, the merged manifest's exported receiver, empty-state notification replacement, and headset/codec notification replacement. `CodecSelectionTest` covers priority filtering, fallback ordering, modes and stale-success reconciliation. These tests do not emulate radio negotiation, ColorOS process management, or real broadcast timing.

Validation command:

```sh
./gradlew testDebugUnitTest lintRelease assembleDebug assembleRelease
```

Local result (2026-10-17): 23 tests passed (11 selector tests plus 6 Android regression scenarios on each of API 34 and 35); release lint completed with 0 errors and 10 warnings; debug and unsigned release APKs built. Debug artifact: version 0.2.1, versionCode 3, SHA-256 `bdcf7438ddb6d2b4a983d0b00068575a36a880d51d47df0cc29f4b26a23ad749`.

## Physical acceptance still required

Install the debug APK as an update, open it once, retain Nearby devices permissions and Companion association, and leave the Activity. Switch Shokz → disconnected → AVENTHO 300. Verify the notification at each step, then confirm active aptX HD through Bluetooth read-back. Repeat with the process reclaimed by Android and after reboot. Force-stop remains a separate Android restriction and is not equivalent to ordinary process death.

## Platform references

- [AssociationInfo: associatedDevice is not persisted across sessions](https://developer.android.com/reference/android/companion/AssociationInfo#getAssociatedDevice())
- [Broadcast receiver export flags and privileged Bluetooth senders](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
- [A2DP connection broadcasts are exempt from implicit broadcast limits](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
