# Fire TV Glass

**Your Fire TV, minus the ads and the tracking.**

Glass is a small, fast home screen for the Fire TV Stick, plus scripts that switch off
Amazon's telemetry, ad and preloaded packages over Wi-Fi. No root, nothing to sideload by
hand, and no new apps beyond the launcher.

[![Stock Fire TV home on the left, Glass on the right](docs/before-after.jpg)](docs/before-after.jpg)

**Everything is reversible.** Packages are disabled with `pm disable-user`, never uninstalled.
`./restore.sh` turns everything back on and gives Home back to Amazon's launcher.

## Quick start

On the TV:

1. **Settings > My Fire TV > About**: click the device name 7 times to unlock Developer Options.
2. **Settings > My Fire TV > Developer Options**: turn on **ADB debugging**.
3. **Settings > My Fire TV > About > Network**: note the IP address.

On your Mac (needs Python 3, JDK 17+ and the Android SDK):

```bash
git clone https://github.com/ivantrj/firetv-glass-launcher.git && cd firetv-glass-launcher
```

```bash
python3 -m venv .venv && .venv/bin/pip install adb-shell
```

```bash
echo 192.168.0.42 > .tv-ip   # your TV's IP
```

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > launcher/local.properties
```

```bash
./debloat.sh    # disable Amazon's telemetry, ads and preloads
```

```bash
./install.sh    # build Glass, install it, make it the home screen
```

The first command that talks to the TV makes it ask **Allow USB debugging?**. Tick
"Always allow" and accept.

## What's in the repo

| Path | What it is |
|---|---|
| `debloat.sh` | Disables everything in `debloat/disable-list.txt` (safe to re-run) |
| `restore.sh` | Re-enables one package, or everything, and gives Home back to Amazon |
| `install.sh` | Builds Glass, installs it, makes it the home screen |
| `configure.sh` | Changes dock apps, hidden apps or the wallpaper without rebuilding |
| `debloat/disable-list.txt` | Every package that gets disabled, by category, with notes |
| `launcher/` | Glass source (Java, Android Studio / Gradle project) |
| `tools/tv` | The ADB client all scripts use (see [Why not `adb`](#why-not-adb)) |

## What gets disabled

Full list with comments in [`debloat/disable-list.txt`](debloat/disable-list.txt). Edit it
before running `./debloat.sh` if you want to keep something.

- **Telemetry:** Minerva metrics, device logger, telemetry emitter, wireless metrics,
  perf collection, ACR (content recognition).
- **Content and ads:** recommendations, the sponsored screensaver (`kso.blackbird`),
  ad-ID service, shopping, miniTV, Ambient Experience, sneak-peek previews.
- **Background:** sharing proxy, Kindle connect, phone sign-in companion,
  print and pro-tips stubs.
- **Preloaded:** tutorials, Amazon Kids, game hub, website launcher.
- **Personal picks** (remove the lines if you use them): HBO Max, Live TV, Amazon Music and
  Photos, phone casting (Fling/DIAL), Miracast, the forced updater.

Fire OS refuses to disable 8 "protected" packages (crash/log managers, `device.metrics`,
the usage-stats proxy, ad deeplinking, csapp and the core OTA service). They're marked in the
list. Updates can still arrive, and a Fire OS update may switch things back on. Run
`./debloat.sh` again if that happens.

## The launcher

[![Glass app grid](docs/grid.png)](docs/grid.png)

- **Top bar:** clock, greeting, Inputs, Wi-Fi and Settings.
- **Dock:** your four favourite apps. The big title and the background glow follow the focused app.
- **Grid:** every other app, alphabetical, 4 columns. Press Down from the dock.
- **D-pad:** Up and Down always move exactly one row, and the ends of a row never jump elsewhere.
- **Back** returns to the dock. **Home** while already on Glass does the same.
- **Menu** (☰) on a tile opens that app's system page (force stop, clear cache, uninstall).
- **Hold OK** on a dock tile to move it: Left/Right reorder, OK drops it, Down takes it out of the dock.
  Hold OK on an app in the grid to add it to the dock (if the dock is full, its last app moves back to the grid).
- Remembers the last focused tile when you come back from an app.
- Native Java + RecyclerView: about a 4 MB APK and 57 MB of RAM.

### Customise

All of these take effect immediately, no rebuild:

```bash
./configure.sh dock com.netflix.ninja com.stremio.one org.smarttube.beta com.spotify.tv.android
```

```bash
./configure.sh hide org.videolan.vlc com.nordvpn.android
```

```bash
./configure.sh wallpaper ~/Pictures/mountains.jpg   # scaled and dimmed; "none" for black
```

```bash
./configure.sh apps    # list installed package names
```

`dock reset` and `hide reset` go back to the defaults in
`launcher/app/src/main/res/values/config.xml`. Edit that file and run `./install.sh` to change
the built-in defaults.

## Undo

One package:

```bash
./restore.sh com.amazon.tv.livetv
```

Everything, plus Home back to Amazon's launcher and Glass's Home-button service switched off:

```bash
./restore.sh
```

## How it works, and gotchas

### Home on Fire OS 8

Fire OS protects its launcher: `pm disable-user` is refused, and `cmd package set-home-activity`
is accepted but ignored. So Glass includes a small accessibility service ("Glass Home button",
switched on by `install.sh`). Whenever the Amazon launcher comes to the front (Home button,
boot, an app exiting), it brings Glass straight back. It only listens to window events from
the Amazon launcher's package; there's no polling.

Side effects:

- The Amazon home screen flashes for about 0.4 s before Glass appears.
- Fire OS doesn't pass remote key presses to accessibility services, so Home can't be caught earlier.
- You can't reach the Amazon home screen while it's on. Turn it off with `./restore.sh`, or in
  **Settings > Accessibility**.

### Why not `adb`

On macOS the stock `adb` binary (and Xcode's Python) can get "No route to host" because of
Local Network privacy, even with port 5555 open. `tools/tv` uses the `adb-shell` Python package
instead, and pushes files as base64 chunks through the shell with an md5 check. If plain `adb`
works for you, the scripts' commands work the same way with `adb shell`.

```bash
tools/tv shell 'pm list packages -d'
```

```bash
tools/tv key DPAD_DOWN DPAD_CENTER
```

```bash
tools/tv screencap shot.png
```

### One `pm` command per call

Chaining `pm` commands in one shell call (`pm disable-user a; pm disable-user b`) can silently
stop after the first, so every script runs them one at a time.

## Tested on

| Device | Fire OS | Chipset |
|---|---|---|
| Fire TV Stick 4K (AFTKRT) | Fire OS 8 (Android 11, API 30) | MediaTek |

Other Fire OS 8 sticks should work. If you try one, open an issue or PR with what happened.

## Related

Built in the spirit of [CleanYourTV](https://github.com/Gathin23/cleanyourtv), which does the
same for Android TV and Google TV sets.

## License

MIT, see [LICENSE](LICENSE). The **Outfit** font is © The Outfit Project Authors, SIL Open
Font License 1.1 ([`OFL.txt`](launcher/app/src/main/assets/fonts/OFL.txt)).
