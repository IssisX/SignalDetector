# Signal Hunter

A native Android BLE and Wi-Fi field instrument. Version 0.1.0.

## What is implemented

- Live BLE advertisement scanning with Android timestamps and raw
  advertisement bytes where the platform supplies them.
- Wi-Fi scan requests, Android scan-result broadcasts, original scan
  timestamps, BSSID, SSID, frequency, capabilities, and channel data.
- Discover, Hunt, Sessions, and Library screens with a responsive
  two-pane layout for unfolded devices.
- Raw dBm traces, observed min/max and arithmetic dBm mean, and
  touch selection of actual samples. No distance or direction inference.
- SQLite storage, boot-qualified observation deduplication, custom
  labels and notes, explicit recording sessions, and streaming JSON export.
- No internet permission, analytics, advertising, cloud account,
  background service, or simulated radio results.

## Build

The authoritative build is `.github/workflows/android.yml`. It uses
JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3, and Gradle 8.9.
The workflow runs the core tests, compiles a debug APK, checks its
package and signature, and uploads the APK with a SHA-256 digest.

Use Actions > Android APK > Run workflow, or push to main. Open the
successful run and download `SignalHunter-debug-APK`. Extract the ZIP
and install `app-debug.apk` on your Android device. Android may ask you
to allow installation from the app you used to open the APK.

For a local build with the same toolchain:

```sh
bash tools/build.sh
```

The APK output is `app/build/outputs/apk/debug/app-debug.apk`.
A debug APK is signed by the build host's debug key. It is suitable
for direct installation, not a reproducibly signed production release.
A separately generated debug key can prevent an update from installing
over a previous APK. Preserve a release signing key before distributing
production builds. Do not commit signing keys or passwords.

## Operating limits

BLE and Wi-Fi are first-class but different measurement sources. BLE
provides advertisement callbacks; Wi-Fi results are snapshots managed
and potentially throttled by Android. A successful scan request is not
proof that every returned observation is fresh. Source timestamps are
retained and stale results are rejected per boot-qualified identity.

Android requires location access and enabled Location services for
these scanning features. Android 12+ also requires Bluetooth runtime
permissions; Android 13+ requires Nearby Wi-Fi Devices permission.
The app does not claim neverForLocation because signal investigation
can reveal physical location. No location coordinates are collected.

An address is an observed radio identity, not proof of a unique physical
device. BLE addresses can rotate, and multiple Wi-Fi BSSIDs can belong
to one network. The app never automatically merges those identities.
A user label is not a manufacturer identification. Hidden/unnamed
advertisers remain explicitly unknown.

RSSI is platform-reported received power. It is affected by propagation,
antenna orientation, transmit power, and hardware. It is not calibrated
distance. Wi-Fi scan metadata is not a raw 802.11 packet capture.
The app does not manufacture missing packet bytes, unknown channels,
or unsupported radio measurements.

Scanning is foreground-only. Leaving the app stops the radios and ends
an active recording. Unexpectedly interrupted database sessions are
marked `interrupted` when the app next starts. The database is local,
not backed up to a cloud account, and uninstalling the app removes it.
Export important sessions before uninstalling.

## Evidence and current delivery status

The canonical source has been compiled successfully on a GitHub-hosted
Ubuntu runner with JDK 17, Android SDK 35, Build Tools 35.0.0, Android
Gradle Plugin 8.7.3, and Gradle 8.9. The dependency-free core suite
passed 21 assertions before compilation. The resulting debug APK was
validated with `aapt`, `zipalign`, and `apksigner`; package identity is
`com.cory.signalhunter`.

Physical BLE and Wi-Fi scanning behavior is not claimed verified until
the APK is exercised on real Android hardware. That device-level test is
the next authority for permissions, OEM radio behavior, scan throttling,
and Fold layout behavior.

## Source ownership

- `core/` - immutable observations, identity ordering, math.
- `radio/` - native Android BLE and Wi-Fi data sources.
- `store/` - SQLite records, sessions, labels, export.
- `ui/` - native UI components and measurement traces.
- `MainActivity.java` - navigation, permissions, lifecycle, actions.

No third-party runtime library is needed in the application.
# Analyst workstation update (0.2.0)

The native Analyst screen is now the default entry point. It adds a
compact Wi-Fi/BLE list, identity-keyed multi-selection, a persisted
list/inspector splitter, source timestamps, retained RSSI history,
search, radio/band filters, sort, watch filtering, operator notes and
explicit operator links. Discover, Hunt, Sessions and Library remain.

Wi-Fi information elements exposed by Android API 30+ are retained
with their element IDs and raw payloads. Bounded decoders cover RSN
suite selectors and PMF bits, DTIM, QBSS load and vendor OUIs. The BLE
inspector exposes AD structure payloads, flags, Tx power and company
identifiers without inventing physical-device identity.

The Analyst JSON format is `signalhunter.analyst.1`. Export contains
the retained window (at most 360 samples per identity), not the whole
SQLite session archive. Select rows to export a subset, or clear
selection to export both radios. Imports are isolated replay state;
they never enter the live radio repository. Importing a second capture
produces a latest-field capture diff. Imported claims are not verified
measurements. The existing Sessions export remains available separately.

Channel plots are observations, not spectrum-analyzer measurements.
The heatmap bins observations into 10-second cells over five minutes.
Overlap is a primary-frequency proximity heuristic, not a measurement
of interference or airtime. Stale observations are not proof of departure.
Temporal coincidence is labeled HYPOTHESIS and never establishes identity.

Remaining product scope: complete HE/EHT decoding and BSS color,
beacon interval/subtype evidence, width-aware interference modeling,
vendor/rotation clustering, a dedicated left rail, named multi-view
management, durable change-event archive and complete session-diff UI.
Wi-Fi STA and Bluetooth connection inventory are explicitly unavailable
in the current passive discovery data path. No synthetic radio generator
is shipped; instrumentation fixtures are imported with a synthetic label.

CI runs the real Activity on an API 35 emulator at 1280x720. Its checks
do not establish physical Fold6 scanning, OEM throttling, or usability
in every fold/orientation. Device testing remains required.
