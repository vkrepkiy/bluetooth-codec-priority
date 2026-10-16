# Release checklist

This checklist is for a real release review. A debug APK that works on one phone is not a Play-ready release.

## Product claims

- [ ] State `Android 8.0+ (API 26)` as the installable minimum.
- [ ] State `Android 12+ (API 31)` as the recommended range for codec automation.
- [ ] State that hardware verification currently covers OPPO Find X5 / ColorOS, Android 15/API 35, and Beyerdynamic AVENTHO 300 with aptX HD.
- [ ] State that Android 16/API 36 is build-only until a physical API 36 device is tested.
- [ ] Do not claim universal codec switching, universal OEM support, or Play approval.
- [ ] Explain that a saved preference is never treated as active; success requires Bluetooth read-back.

## Permissions and association

- [ ] Request `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` at runtime on Android 12+.
- [ ] Explain why Nearby devices access is needed to read connected A2DP devices and their reported capabilities.
- [ ] Explain Companion association before the user starts codec control for a device.
- [ ] Explain that `BLUETOOTH_PRIVILEGED` is not available to an ordinary Play application.
- [ ] Explain that `getCodecStatus()` and `setCodecConfigPreference()` are hidden/non-SDK APIs used only as a guarded best-effort path.
- [ ] Verify that restricted hidden API access produces a truthful restricted/failed result and never success.
- [ ] Confirm that the final manifest and runtime behavior match the Play Console Data Safety answers.

## Background behavior

- [ ] Validate A2DP connection broadcasts after process death.
- [ ] Validate Companion presence callbacks after association and reboot.
- [ ] Validate boot handling when headphones are already connected.
- [ ] Validate that duplicate broadcasts do not create concurrent codec applies.
- [ ] Confirm bounded retries and no continuous polling; verify the connected-device foreground service performs notification duty only.
- [ ] Verify the single ongoing status notification shows live read-back and cannot be dismissed while the service is running.
- [ ] Verify Android 13+ notification permission denial leaves codec switching functional and produces no crash.
- [ ] Document OEM setup when needed: ColorOS auto-launch, background activity, and unrestricted battery usage.
- [ ] Confirm that HFP call connections are not modified by the A2DP automation.

## Manual device matrix

- [ ] Fresh install, grant Nearby devices, associate AVENTHO 300, and verify the association status.
- [ ] Global order `aptX HD > AAC > SBC` selects aptX HD on the verified OPPO/AVENTHO pair.
- [ ] Disconnect and reconnect the verified pair ten times; record verified read-back results and crashes/timeouts.
- [ ] Force-stop the app, reconnect, and verify the documented background path.
- [ ] Reboot with the headphones paired/connected and verify one bounded reapply.
- [ ] Test a device without aptX HD but with AAC; verify AAC is selected.
- [ ] Test a device exposing only SBC; verify SBC is selected.
- [ ] Test Custom priority, System default, and disabled global priority.
- [ ] Remove/revoke association or cause an API restriction; verify actionable restricted/failed diagnostics.
- [ ] Repeat the critical cases on at least one additional OEM before making broader claims.

## Release artifact

- [ ] Configure a protected release keystore outside the repository.
- [ ] Build a signed release AAB with the final `targetSdk` and version metadata.
- [ ] Test R8/minification if enabled; otherwise record why it remains disabled for the release.
- [ ] Test clean install, update, uninstall/reinstall, and DataStore migration.
- [ ] Run unit tests and lint; attach the exact commands and results to the release record.
- [ ] Upload to Play internal testing first and inspect the pre-launch report.
- [ ] Review crash/ANR reports and Android vitals before wider rollout.
- [ ] Prepare a privacy policy URL and complete Play Data Safety accurately. This app currently declares no network permission and no analytics, but the final declaration must follow the shipped artifact.
- [ ] Review the final store listing for truthful limitations, supported Android versions, and OEM-specific behavior.

## Evidence to retain

- [ ] Release AAB SHA-256 and version code.
- [ ] Device model, OEM build, Android/API level, headset model, and association state for each hardware test.
- [ ] Sanitized diagnostic logs with Bluetooth addresses masked by default.
- [ ] Play Console test track, pre-launch report, policy declarations, and known limitations.
