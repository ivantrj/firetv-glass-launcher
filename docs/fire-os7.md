# Fire OS 7 / Android 9 source build

Tested on **Fire TV Stick Lite, AFTSS (sheldon), Fire OS 7.7.1.7, firmware
PS7717.5741N, Android 9 / SDK 28**. Other Fire OS 7 devices have not been tested.
The default build remains SDK 30 with the original application ID.

## What changed

- `-PfireOs7=true` selects minimum SDK 28, package `com.ivantrj.glasslauncher.os7`
  and label **Glass OS7**, alongside the original package. Java keeps its namespace.
- A launcher-grid entry allows testing Glass as an ordinary app before enabling
  its Home redirect. Amazon's launcher stays enabled throughout the OS 7 setup.
- The accessibility service listens only for Amazon Home window-state events.
  It requests no key filtering or window-content access. A policy accepts
  `HomeActivity` and `HomeActivity_*`, excluding Settings and unrelated apps;
  eight JVM checks cover this boundary.
- The service launches its activity with an immutable `PendingIntent` and catches
  failures. It does no polling or network access.
- The OS 7 Settings pill opens native My Fire TV, Preferences, Display & Sounds,
  Applications, Controllers & Bluetooth and Accessibility pages. The generic
  Settings intent on this firmware instead returns to Amazon Home's Settings tab,
  which would trigger the redirect.
- Inputs falls back to `livetv://showEpg` when Live TV has no leanback activity.
  On this stick that opens Live TV Sources when channels are not configured.
- The Wi-Fi pill shows connection status without requesting location or exposing
  the network name. Unused Wi-Fi/location permissions are removed from both build
  configurations; neither declares `INTERNET`.
- Windows helpers build, back up, install, enable and undo the redirect while
  preserving other accessibility services. No additional debloating is performed.

Upstream dock defaults are retained. Personal order belongs in the app's private
`files/dock.txt`, one installed package per line, rather than the published defaults.

## Build from source

Needs **JDK 17**, Android SDK **platform 35**, **build-tools 35.0.0**, and Python
3.10 or newer for the optional setup helper. No prebuilt APK is supplied.

From the repository root on Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File build-os7.ps1 -JdkHome '<JDK folder>' -SdkRoot '<Android SDK folder>'
```

Or set `JAVA_HOME` and `ANDROID_HOME`, then run `build-os7.ps1`. The script runs
eight policy checks, `lintDebug` with `NewApi` enabled, and `assembleDebug`.
Gradle downloads its declared dependencies normally. Only the expired target-SDK
policy check is suppressed because target SDK 30 is deliberate; runtime API checks
remain enabled and abort on errors.

Equivalent Gradle command on another platform, with the SDK configured:

```sh
cd launcher
./gradlew --no-daemon -PfireOs7=true lintDebug assembleDebug
```

Output: `launcher/app/build/outputs/apk/debug/app-debug.apk`. Keep the debug signing
key private and retain it for updates.

## Install and test before enabling Home

Enable ADB debugging and keep the TV on the same trusted network. Accept its
authorization prompt. In PowerShell from the repository root:

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r tools/adb/requirements.txt
$env:TV_HOST = '<stick ip>'
.venv\Scripts\python.exe control-os7.py install
```

The helper accepts only SDK 28, backs up packages/Home/settings before changes,
transfers the source-built APK with a checksum check, then installs and opens it.
It stops on an installation refusal. Installation leaves Amazon Home and the
default Home component untouched and does not enable the redirect.

Check dock/grid navigation from every edge, app launch/return, Settings, Wi-Fi
and Live TV. Then enable the redirect:

```powershell
.venv\Scripts\python.exe control-os7.py redirect-on
```

The helper verifies the service is bound before opening Glass. Check **Home on the
physical remote**, including from another app; reboot and check again. An injected
key alone is insufficient evidence. Once finished, switch ADB debugging off, revoke
authorizations if available, restart and verify the ADB port is closed. Glass does
not need ADB left enabled.

The existing `install.sh`, `restore.sh` and `configure.sh` address the original
Fire OS 8 package. Use these OS 7 helpers for the separate OS 7 package.

## Undo

Keep `local-state/before-glass.json` private and in place. The initial backup is
not overwritten by a later install or enable operation.

```powershell
$env:TV_HOST = '<stick ip>'
.venv\Scripts\python.exe control-os7.py redirect-off
# Or:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File restore-os7.ps1 -TvHost '<stick ip>'
```

Undo removes only Glass OS7's service, preserves other active services, restores
the initial global accessibility setting when none remain, and opens the enabled
Amazon Home. Glass can remain installed as a manual app. The backup is tied to the
original `TV_HOST`; if its address changes, verify the device and deliberately
update the local record before restoring.

Fire OS may omit third-party services from Accessibility. For an emergency escape,
open Glass **Settings → Applications → Manage Installed Applications → Glass OS7 →
Force stop**, or re-enable ADB and run undo. Force-stop can switch the helper off.
These helpers never uninstall packages.

## Tested behavior and limits

On AFTSS/PS7717.5741N, the source-built local port installed; physical Home returned
Glass; reboot returned Glass automatically with its service bound; native Settings
and Live TV Sources opened; YouTube launched from the dock and Home returned Glass.
The user confirmed remote and casting operation during the session. These are
functional checks on one device, not a compatibility or performance claim for all
Fire OS 7 hardware.

Home is **not instantaneous**. Device logs measured approximately **4.96 seconds**
with `startActivity` and **4.83 seconds** with `PendingIntent`; activity rendering
itself was about 0.3 seconds. This is not a meaningful speed improvement. Fire OS 7
holds the app switch after Home; Amazon Home may remain visible during the wait.
There is no six-second runtime timer: six seconds was the test allowance. The
600 ms debounce only suppresses duplicate events.

No overlay, root access, system-package disabling or privileged permission grant
is added to shorten the wait. Fire OS 8 hardware and the macOS workflow were not
rerun during this OS 7 session. Publication packaging uses the opt-in Gradle flag
and retains upstream dock defaults; the tested local port used a separate OS 7 ID.

## Publication checks

Both the opt-in SDK 28 build and default SDK 30 build passed `lintDebug` and
`assembleDebug` on Windows with JDK 17 (zero lint errors, 16 warnings). APK metadata
was checked for the selected package ID, label, minimum SDK and permissions.
The eight JVM Home-policy checks passed. Seven fake-device setup/restore tests
cover SDK rejection, missing launcher, preservation of other accessibility
services, undo, host mismatch and keeping the initial backup unchanged:

```powershell
python -m unittest discover -s tests -p test_control_os7.py -v
```

These tests make no TV connection. No APK, key, backup or device log is published.

## Private files

Exclude `local-state/`, `tools/adb/adbkey/`, backups, raw device logs, signing keys
and APKs from commits. `tools/adb/` contains unchanged CleanYourTV 2.7.0 ADB helpers,
with their origin and MIT license beside them.
