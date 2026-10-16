# Bluetooth connection state machine

The app treats Android Bluetooth events as triggers, not as state. The authoritative state is read from the Bluetooth stack:

- `BluetoothA2dp.connectedDevices` determines whether an A2DP device is connected.
- `BluetoothCodecStatus.getCodecConfig()` determines the active codec.
- `getCodecsSelectableCapabilities()` determines which codecs may be requested.
- `SettingsStore` determines the persisted mode and priority.
- `CompanionDeviceManager.myAssociations` determines whether background codec control is authorized.

| Situation | UI | Background work | Result |
| --- | --- | --- | --- |
| A2DP connects while Activity is started | Dynamic receiver triggers a fresh read | Manifest receiver and Companion may apply the effective policy | Home shows the connected device only |
| A2DP disconnects while Activity is started | Dynamic receiver triggers a fresh read | Notification is rebuilt from all remaining connected devices | Home removes the disconnected device |
| Event while Activity is stopped | No Compose refresh | Receiver/Companion may apply policy and update the single notification | No UI state is mutated |
| Activity starts/resumes | Fresh `readConnected()` | None beyond normal event handling | Stale in-memory device list is replaced |
| Unknown device connects | Device is remembered by MAC | It inherits Global mode; codec control waits for Companion authorization | Devices shows its saved state after disconnect |
| Connect event arrives before A2DP readiness | No special UI assumption | Background applier retries at most three times | Stops on a non-failed result or after the bound |
| Global mode | Live card and settings | Applies only when Global priority is enabled | Disabled means unchanged |
| Custom mode | Live card and custom editor | Applies independently of Global enabled state | Uses only the device list |
| System default | Live status only | Never calls codec setter | Device codec remains unchanged |
| Duplicate broadcast/Companion/boot event | At most one fresh UI read per event while visible | `ApplyCoordinator` serializes applies; settings version can cause one restart with current policy | No unbounded loop |

Every successful apply requires a live active-codec read-back matching the requested candidate. A setter returning without an exception is not success.

The foreground service exists only to keep one status notification visible. It does not poll Bluetooth or perform codec switching. A notification is updated from fresh stack reads and summarizes all connected A2DP devices, not the last callback that happened to finish.
