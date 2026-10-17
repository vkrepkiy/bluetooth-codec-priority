# Bluetooth Codec Priority

Bluetooth Codec Priority is a small Android app for choosing a preferred Bluetooth A2DP codec per headset. I started it because Android kept reconnecting my headphones with aptX Adaptive, which, to my ears, was less enjoyable for metal and other heavy music than aptX HD. The app lets you put aptX HD, AAC, SBC, or any codec actually exposed by the Bluetooth stack into a priority order, with optional per-device settings, and tries to apply that order when an authorized headset connects.

## How it works

The app reads the Bluetooth stack's live selectable codec capabilities, filters the user's ordered priority list against those capabilities, requests the first compatible codec, and verifies the result by reading the active codec back. It never treats a saved preference or a successful API call as proof that negotiation succeeded. Global, custom-device, and System default modes are persisted locally with Android DataStore. Connection broadcasts, Companion Device presence callbacks, boot handling, and a bounded retry path support reconnection without continuous polling.

## Disclaimer

Codec control uses Android Bluetooth APIs that are hidden or OEM-dependent on some releases. Companion authorization, Android permissions, the device firmware, and the phone's Bluetooth stack can all affect whether switching is allowed. The app reports restricted or failed operations honestly and does not promise codec switching on every Android phone or headset. It is not affiliated with Qualcomm, Beyerdynamic, Sennheiser, Google, or any codec vendor.

## Project origin

This project started as an AI-assisted prototype built in a couple of hours to solve one concrete headphone problem. It is still an experimental MVP, not a polished product. I am publishing it because it may save someone a few hours of investigation, Android API digging, and AI tokens—and provide a working starting point instead of making the next person build the same experiment from scratch.

## Contributions welcome

Contributions are welcome. Android and Bluetooth experience, testing on different phones and headphones, clearer diagnostics, UX improvements, and documentation would all be useful. Please open an issue with logs and device details, or send a focused pull request. If the idea proves useful to others, perhaps we can turn this small experiment into a genuinely good free tool together.

## Build and install

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release variant uses `versionName 0.2.2` and `versionCode 4` by default. Both can be overridden without editing source:

```sh
./gradlew lintRelease assembleRelease -PversionName=0.2.2 -PversionCode=4
```

GitHub Actions builds and tests the release variant on every pull request and push to `main`. Pushing a version tag such as `v0.2.0` also publishes a GitHub Release with the APK files attached; tag builds use the tag as `versionName` and the workflow run number as `versionCode`. The `app-release-unsigned.apk` asset is unsigned; the accompanying debug APK is installable for testing. Production distribution requires configuring a protected signing key in GitHub Actions.

## Support matrix

| Android | App installation | Codec automation status |
| --- | --- | --- |
| API 26+ (Android 8.0+) | Supported by `minSdk` | Installation is supported; codec automation is not promised below API 31 |
| API 31+ (Android 12+) | Supported | Recommended range for Nearby devices permissions and Companion association |
| API 35 (Android 15) | Supported | Hardware-verified on OPPO Find X5 / ColorOS with Beyerdynamic AVENTHO 300; aptX HD was confirmed by read-back after association |
| API 36 (Android 16) | Supported by the build target | Build/API compatibility only; hardware behavior is not yet verified |

This matrix describes tested scope, not a guarantee that every Android or OEM Bluetooth stack can switch codecs. The app should report `restricted` or `failed` when the platform does not permit the operation.

On Android 12+, grant the in-app Nearby devices permission. The app does not request or declare `BLUETOOTH_PRIVILEGED`: it is a signature permission and cannot be granted to a normal Play-installed application. A companion association can satisfy a Bluetooth service-side permission condition on some platform versions, but it does not make hidden reflection a stable public API. The app reports `restricted` when Android or the OEM blocks the path.

## What is implemented

The pure selector returns only codecs that appear in the device's actual selectable capability list. The Android adapter reads `BluetoothCodecStatus` and calls `setCodecConfigPreference` only through a guarded reflection boundary, then reads back the active codec with bounded retries and tries the next compatible candidate after a non-restricted mismatch. The manifest A2DP receiver handles connection broadcasts after process death; the optional `CompanionDeviceService` is used only after an explicit user association and presence observation.

The app never treats a saved preference as active, never invents vendor codecs, preserves vendor codec identity as a type/extended-type pair, and never changes a device in System default mode. Codec status, selectable capabilities, sample rate and bit depth are shown only when the Bluetooth API exposes them. Settings use escaped token serialization with backward-compatible reads of the original MVP format.

## Background operation

After the user authorizes a device with `Authorize codec control`, switching is event-driven and does not use a polling loop. The `connectedDevice` foreground service keeps one status notification alive and registers an A2DP connection receiver for its lifetime, including while the Activity is stopped. It performs one startup reconciliation through the shared background applier. The exported manifest receiver also provides an event entry point when the process is absent. Both receivers listen to protected system actions; Bluetooth uses its own privileged UID, so a non-exported receiver is not sufficient. The Home screen shows only currently connected A2DP devices; Devices also keeps the saved device list and last known state. Companion presence observation and `BOOT_COMPLETED` provide additional triggers. Each apply attempt is bounded and serialized. On ColorOS, if broadcasts are suppressed, allow the app's background activity/auto-launch and set its battery mode to unrestricted; this is an OEM setting, not a requirement for continuous polling.

Companion authorization is matched against the association's persisted MAC address, not `associatedDevice`, which Android does not persist across sessions. Presence can arrive before A2DP readiness, so it enters the bounded apply retry path. Disconnect callbacks refresh the notification; an empty live connection list replaces the previous headset with `No connected A2DP device`. A connected headset remains visible with unavailable codec status if Android cannot yet expose its codec.

## Connection state machine

A2DP broadcasts and Companion callbacks are triggers, not state. The Bluetooth stack's `connectedDevices` and live `BluetoothCodecStatus` read-back are authoritative.

- When `MainActivity` is started, connection events trigger a fresh read. When it is stopped, the dynamic UI receiver is unregistered and pending UI refresh work is cancelled.
- Home renders only currently connected A2DP devices. Devices renders those devices plus persisted known devices and their last known state.
- A newly observed device is persisted by MAC address with Global mode. Background codec control requires Bluetooth permissions and explicit Companion authorization.
- `System default` never applies. Global applies only when enabled. Custom applies independently of the global toggle.
- A connection event waits for A2DP readiness, then performs bounded retries. Duplicate triggers are serialized; if settings change during an apply, the current policy is retried once.
- Every apply filters the device's selectable codecs and requires live active-codec read-back. A setter return value alone is never success.
- The single status notification summarizes all currently connected A2DP devices. It is updated by both foreground UI reads and background event handlers.

The notification shows only device names and their live active codecs. With multiple devices, the title shows the count, the collapsed summary includes each device, and expanding it shows one row per device. Android may truncate the collapsed summary to fit the available width. Requested codecs and apply results remain in the app's device cards and diagnostics.

The complete event and state table is documented in [docs/connection-state-machine.md](docs/connection-state-machine.md).

## Hardware verification

The support matrix records the previously reported manual OPPO/AVENTHO aptX HD read-back. It does not establish background handover reliability. The 0.2.1 reconnect fix has automated regression coverage on simulated Android 14/15, but has not been installed or checked on the physical phone. After pairing and connecting the headphones, open the app, confirm `aptX HD` appears in the selectable list, put it first, enable global priority, and use Reapply now. Capture:

```sh
adb logcat -s CodecPriority
adb shell dumpsys bluetooth_manager | grep -i -E 'codec|a2dp'
```

If the result is Restricted, that is an Android/OEM privilege or hidden-API limitation, not a successful switch. ColorOS may also require allowing background activity and auto-launch for the app. Physical Shokz-to-AVENTHO handover, aptX HD negotiation with 0.2.1, and ten-cycle reconnect behavior remain unverified. See [the reconnect diagnosis](docs/reconnect-diagnosis.md) for the confirmed code defects and validation boundaries.

## Platform and Play limitations

`BluetoothA2dp.getCodecStatus()` and `setCodecConfigPreference()` are hidden/system Bluetooth APIs. The implementation uses reflection as an isolated best-effort path and verifies the active codec after every request. Android's non-SDK restrictions and OEM Bluetooth implementations may block it even after companion association. This project therefore does not promise codec switching on every Android 15/16 device and should not claim universal Play compatibility without testing the target release policy and OEM behavior.

The app requests Nearby devices, notification, and companion presence permissions. It runs a `connectedDevice` foreground service for the status notification, event registration, and one startup reconciliation; it does not poll Bluetooth. Tapping the notification opens the app. Force-stop and OEM background restrictions can still prevent event delivery. The app does not use root, Shizuku, accessibility automation, accounts, analytics, or network access.

Before publishing, complete [the release checklist](docs/release-checklist.md). In particular, do not describe hidden-API codec control as a guaranteed Play capability, and provide a privacy policy/Data Safety declaration appropriate to the final manifest and any future telemetry. No Play approval is claimed by this repository.
