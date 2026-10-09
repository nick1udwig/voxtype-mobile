# Voxtype Mobile

Voxtype Mobile lets you dictate into Android apps using a speech-recognition model running on your own Linux computer or server.
An Android app records your voice, sends it to your server over Tailscale, and inserts the returned text into the focused text field.
Your existing Android keyboard stays available.

Use it when you want phone dictation backed by your own hardware, without a hosted transcription service or a model running on the phone.
If you also use [Voxtype](https://github.com/peteonrails/voxtype) for desktop dictation, both clients share the same loaded model and daemon.
Other applications can use the server's [authenticated dictation API](daemon/docs/remote-api.md).

This is an independently maintained Android companion and Voxtype daemon fork.
The server requires the fork pinned in this repository; installing stock Voxtype alone does not provide this mobile API.
Both projects are MIT licensed.

## What you need

- A Linux computer or server with enough memory for the selected recognition model.
  Setup below uses the Parakeet TDT batch model; Unified streaming is an optional later configuration.
- Git, Bash, Python 3.11+, a current stable Rust toolchain, C/C++ build tools, CMake, pkg-config, libclang, ALSA development headers, and the Opus runtime.
- A systemd user session for automatic service startup.
  Without one, the installer prints a manual launch command.
- Tailscale installed and connected on the server and phone, with HTTPS enabled in your tailnet and policy allowing the phone to reach the server on TCP 443.
- Android 10 or newer.
  Use a signed APK from [Releases](https://github.com/nick1udwig/voxtype-mobile/releases), when available, or build one as described below.

For example, install the server's native dependencies on Debian/Ubuntu:

```bash
sudo apt install git build-essential cmake pkg-config libclang-dev libasound2-dev libopus0 python3
```

On Arch Linux:

```bash
sudo pacman -S --needed git base-devel cmake pkgconf clang alsa-lib opus python
```

Install a current stable Rust toolchain separately if it is not already available.
Check `rustc --version` and `python3 --version` before continuing.
See [Tailscale installation](https://tailscale.com/download/linux) and [HTTPS setup](https://tailscale.com/docs/how-to/set-up-https-certificates).

## 1. Install the server

Run these commands as the user who will own the service, without `sudo`:

```bash
git clone --recurse-submodules https://github.com/nick1udwig/voxtype-mobile.git
cd voxtype-mobile
scripts/install
```

The installer builds the pinned daemon, installs it under `~/.local/share/voxtype-mobile/`, and creates or upgrades `voxtype.service`.
On a new installation it writes `~/.config/voxtype/config.toml` and downloads the `parakeet-tdt-0.6b-v3` model.
Model download and the first Rust build can take time.

Existing configurations and pairing tokens are preserved.
If your configuration is incompatible, the installer stops with a correction message.
This build needs `engine = "parakeet"` and `[parakeet] on_demand_loading = false`; streaming also requires `parakeet-unified-en-0.6b`.
See [model configuration](docs/streaming.md#configure-the-server) for downloading and selecting either supported model on an existing installation.

If you already run the standard Voxtype user service, installation restarts it with the mobile-capable executable.
The old daemon stops before the replacement starts, so there is only one model owner.
Custom service commands require manual review.
Stop an unmanaged daemon before installing; do not launch another daemon alongside it.
Failed readiness restores the previous service and executable.

Check the service:

```bash
systemctl --user status voxtype.service
journalctl --user -u voxtype -n 50 --no-pager
```

For a server that must keep running after you log out, enable lingering for this user if your system uses systemd-logind:

```bash
sudo loginctl enable-linger "$USER"
```

Paths above assume default XDG directories.
The installer honors `XDG_CONFIG_HOME` and `XDG_DATA_HOME`.
To install a daemon you have already built, use `scripts/install /path/to/voxtype-mobile-daemon`.

## 2. Connect the server to your tailnet

With Tailscale connected on the server:

```bash
scripts/expose-tailscale
```

This adds `/voxtype` to Tailscale Serve on HTTPS port 443, forwarding to `127.0.0.1:8765`.
It preserves other routes and refuses to overwrite a conflicting `/voxtype` route.
Use the hostname printed by `tailscale serve status` to form:

```text
wss://YOUR-SERVER.YOUR-TAILNET.ts.net/voxtype/v1/dictate
```

The listener is intended for your private tailnet.
Do not expose it through Tailscale Funnel or a public port.
HTTPS must be enabled in the tailnet; [Serve documentation](https://tailscale.com/docs/features/tailscale-serve) explains the setup.
The [example tailnet grant](infra/tailscale-grant.example.json) can be merged into your policy; grants apply to port 443, not individual URL paths.

## 3. Install and configure the Android app

Install the signed APK on your phone and connect Tailscale to the same tailnet.
Open Voxtype Mobile and save:

- **Server address:** the `wss://.../voxtype/v1/dictate` URL from the previous step.
- **Pairing token:** the contents of `~/.local/share/voxtype-mobile/token` on the server.

Read the token locally with:

```bash
cat "${XDG_DATA_HOME:-$HOME/.local/share}/voxtype-mobile/token"
```

Grant microphone permission and enable the **Voxtype Mobile accessibility service** in Android settings.
Sideloaded apps on Android 13+ may require **App info → menu → Allow restricted settings** first.
After an upgrade, toggle the accessibility service off and on if Android has not rebound it.

To build a development APK, install JDK 17, Android SDK platform 35 and Build Tools 35.0.0, then run:

```bash
scripts/build-android
adb install -r artifacts/voxtype-mobile-debug.apk
```

The helper also runs unit tests and lint.
It discovers tools under `.toolchains/` or uses your configured JDK and Android SDK.
For a distributable APK, follow [release signing and publication](docs/releases.md).

## Dictate

Focus an ordinary text field in an Android app.
A floating microphone appears:

- Hold it, speak, then release to finish; or tap, speak, and tap again to finish.
  Capture begins on touch-down, so you can speak immediately.
  Recording controls appear after a confirmed tap or the long-press delay.
- Tap the check to finish or **X** to discard.
  Drag the control to reposition it.
- The final transcript is inserted at the current selection.
  If the editor cannot accept it, or no text field is focused, the app uses the phone's clipboard.
  The settings app can also start clipboard dictation.

Password fields are excluded.
An active recording can continue through screen lock; unlock to finish.
A transcript received while locked waits in memory until unlock.
Android/OEM microphone restrictions and unusual editors need testing on your device; see the [device checklist](docs/device-validation.md).

Brief connection failures retry automatically.
If recovery stops, **Tap to retry** keeps the recording available; **X** discards it.
Recordings are limited to five minutes and 2 MiB of encoded audio, held only in memory.
Disabling the service or Android terminating the process loses pending audio.
See [connection and recovery](docs/protocol.md) for details.

Unified streaming supports two sessions total, including desktop dictation; batch models allow one.
Busy clients wait and retry.
Phone sessions return results to the phone and do not trigger Linux typing, clipboard, media controls, or output hooks.

## Update, troubleshoot, and remove

Update both the checkout and its pinned daemon before rebuilding:

```bash
git pull --ff-only
git submodule update --init --recursive
scripts/install
```

Install the corresponding APK signed with the same release key.
Protocol 2 is required; clients older than Android 0.1.9 cannot use this server.
If changing models, back up the configuration and obtain the matching model files before restarting; see [streaming setup](docs/streaming.md).

If dictation fails, check the server journal, Tailscale connectivity, the saved URL/token, and Android microphone/accessibility permissions.
A version error requires compatible app and server versions.
**Tap to retry** uses the latest saved address and token.

To rotate the token, replace its contents with a new random secret, keep file permissions at `0600`, restart `voxtype.service`, and save it in each client.

To restore a packaged Voxtype service that was upgraded with the mobile drop-in:

```bash
scripts/uninstall-daemon
tailscale serve --https=443 --set-path=/voxtype off
```

For a service created by this project on a new server, instead run `systemctl --user disable --now voxtype.service`, remove `~/.config/systemd/user/voxtype.service`, then run `systemctl --user daemon-reload` and remove the Tailscale route above.
The restore helper only removes the drop-in for an existing packaged service.
Keep any model files or configuration you still need.

## Further documentation

- [Development, builds, and application integrations](docs/development.md)
- [Daemon fork and upstream contributions](docs/daemon-upstream.md)
- [Stable upstream integration](docs/upstream-merge.md)
- [APK signing and GitHub releases](docs/releases.md)
- [Unified streaming](docs/streaming.md), [performance measurements](docs/performance.md), and [validation history](docs/validation.md)
