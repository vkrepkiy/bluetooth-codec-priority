# Bluetooth connection state machine

The app treats Android Bluetooth events as triggers, not as state. The authoritative state is read from the Bluetooth stack:

- `BluetoothA2dp.connectedDevices` determines whether an A2DP device is connected.
- `BluetoothCodecStatus.getCodecConfig()` determines the active codec.
- `getCodecsSelectableCapabilities()` determines which codecs may be requested.
- `SettingsStore` determines the persisted mode and priority.
- The persisted MAC in `CompanionDeviceManager.myAssociations` determines whether background codec control is authorized (legacy address list below API 33). `associatedDevice` is transient and must not be used to recover authorization.

| Situation | UI | Background work | Result |
| --- | --- | --- | --- |
| A2DP connects while Activity is started | Dynamic receiver triggers a fresh read | Manifest receiver and Companion may apply the effective policy | Home shows the connected device only |
| A2DP disconnects while Activity is started | Dynamic receiver triggers a fresh read | Notification is rebuilt from all remaining connected devices | Home removes the disconnected device |
| Event while Activity is stopped | No Compose refresh | Foreground service's exported runtime receiver and exported manifest receiver receive protected A2DP events; Companion is an additional trigger | Shared applier updates the single notification |
| Status service starts or restarts | No UI required | Registers its receiver and performs one bounded reconciliation of connected devices | Notification is rebuilt; managed authorized devices enter the shared applier |
| Activity starts/resumes | Fresh `readConnected()` | None beyond normal event handling | Stale in-memory device list is replaced |
| Unknown device connects | Device is remembered by MAC | It inherits Global mode; codec control waits for Companion authorization | Devices shows its saved state after disconnect |
| Connect event arrives before A2DP readiness | No special UI assumption | Background applier retries at most three times | Stops on a non-failed result or after the bound |
| Companion presence precedes A2DP | No special UI assumption | Enters the same retry path; no one-shot readiness veto | A missing device/status or empty capability list can be retried within the bound |
| No devices remain after an event/apply | Home is empty | Rebuilds the notification even when the target disappeared or is unmanaged | Previous headset is replaced by `No connected A2DP device` |
| Connected device has no codec status yet | Device remains connected in Home | Does not invent an active codec | Notification reports active codec unavailable |
| Global mode | Live card and settings | Applies only when Global priority is enabled | Disabled means unchanged |
| Custom mode | Live card and custom editor | Applies independently of Global enabled state | Uses only the device list |
| System default | Live status only | Never calls codec setter | Device codec remains unchanged |
| Duplicate broadcast/Companion/boot event | At most one fresh UI read per event while visible | `ApplyCoordinator` serializes applies; settings version can cause one restart with current policy | No unbounded loop |

Every successful apply requires a live active-codec read-back matching the requested candidate. A setter returning without an exception is not success.

The foreground service keeps the status notification visible, registers a runtime receiver independently of Activity visibility, and performs one startup reconciliation. Event-driven codec work uses the shared background applier. There is no periodic polling. A notification is updated from fresh stack reads and summarizes all connected A2DP devices, including an empty list.
