# Voxtype Mobile

Android dictation through the existing Voxtype daemon over Tailscale. This repo
contains the Android app and an independently maintained Voxtype fork pinned as a Git submodule. The replacement daemon
is the sole model owner: desktop and phone obtain the same loaded transcriber.
No inference sidecar, `voxtype transcribe` subprocess, or Android model.

## Layout

- `android/`: native Java Android app, Android 10+; Gradle wrapper.
- `daemon/`: Voxtype fork submodule, with full upstream ancestry and focused
  commits for upstream review. See `docs/daemon-upstream.md`; MIT license in
  `daemon/LICENSE`.
- `scripts/`: build, install into the existing service, expose via Tailscale,
  and restore the packaged daemon.
- `docs/protocol.md`: authentication, audio format and lifecycle.
- `docs/device-validation.md`: phone acceptance tests and known platform limits.
- `artifacts/`: locally built APK and daemon (ignored by Git).

## Checkout and daemon development

Clone this repository with `--recurse-submodules`, or initialize an existing
checkout with `git submodule update --init --recursive`. The daemon is pinned to
an exact commit; ordinary builds never fetch or advance it automatically.

Make daemon changes and commits inside `daemon/`, push that branch first, then
stage the new pin with `git add daemon` in this repository. Android code,
Tailscale/deployment scripts and integration documentation stay here. See
[the fork/upstream workflow](docs/daemon-upstream.md) for the commit split and
upstream contribution instructions.

For the deployed CPU streaming profile, measured latency, and recovery diagnostics,
see [performance.md](docs/performance.md).

## Behavior

A floating accessibility button appears for editable non-password fields. Hold
and release (300 ms threshold), or tap to start and tap again to finish. Audio
capture begins on down; the recording is retained in memory until completion or cancellation. The overlay
shows microphone activity, a check to finish and an X to cancel. Only the final transcript is inserted at the current
selection, preserving surrounding text. If no eligible field exists or the app
cannot edit it, the transcript is copied to the clipboard. Clipboard dictation
can also be started from the settings app. Locking or entering a password field
cancels recording.

Temporary transport, busy-daemon and transcription errors reconnect automatically
and replay retained audio, with backoff and at most eight retries. Full queues
wait for capacity. If recovery still fails, capture stops and **Tap to retry**
keeps the recording available; X discards it. The hard limit is five minutes
(9.6 MB PCM), held only in process memory. Closing the service, locking the phone
or entering a password field discards it. Only one final transcript is inserted;
retries cannot insert stale results or partial previews. See
[connection and recovery](docs/protocol.md).

Drag the microphone/check button to reposition it. Movement beyond Android's
standard touch threshold switches the press to dragging; holding still keeps
hold-to-dictate. A drag discards any recording started by that same press, while
an already-running dictation continues. The button follows your finger with
updates paced to display frames, including while waiting for a transcript.
Its position is remembered across service restarts, adapts to rotation, and is
temporarily kept above the keyboard. Finishing an existing session happens on
release so a drag cannot accidentally submit it.

On Android 13+, insertion uses the accessibility input connection to the current
editor, alongside your existing keyboard. This supports native/Compose and web
editors even when their accessibility nodes omit text or selection. Older Android
versions use accessibility paste or safe text replacement. Input-connection
commits have no success acknowledgment from the editor, so an app that silently
ignores them cannot be detected automatically. The idle control is a 48 dp
lavender rounded square with the app's microphone icon. During recording it becomes a
purple circular check, with a pill of measured microphone levels and an X to its
left. Near the left edge the controls expand to the right; in a narrow space
they stack vertically. The check stays under your finger as the controls expand. The server still
streams revisable text previews (exposed in the activity meter's accessibility
description); the visual overlay stays compact like the supplied reference.

After upgrading from 0.1.0, toggle the Voxtype Mobile accessibility service off
and back on so Android binds its new input connection capability.

The recommended `parakeet-unified-en-0.6b` configuration streams recognition
through Voxtype's existing Parakeet backend. Desktop and phone share one loaded
model; each session has its own decoder state. Release flushes the remaining
audio, without another full-recording pass. Final text processing runs once.
Unified is English-only and uses buffered context windows, not encoder caching.
The batch-model path remains available for `parakeet-tdt-0.6b-v3`; that path still
uses cumulative audio snapshots and a complete final pass.
There is one admitted session and one inference at a time. Cancellation waits
for running inference before releasing admission. Phone sessions do not use
Linux output hooks, keyboard, clipboard, media controls or desktop context.

The mobile interface accepts preloaded batch models or Parakeet Unified streaming.
It rejects on-demand loading, GPU isolation, other streaming backends, eager
processing, secondary models and meeting mode.
Accessibility behavior and microphone access still need testing on your actual
phone; arbitrary custom editors may require clipboard fallback.

## Build

Install Rust, C/C++ tools, CMake, libclang and ALSA development headers for the
daemon. For Android, install JDK 17 and Android SDK platform/build tools 35.
The build scripts also detect tools staged locally under `.toolchains/`.

```sh
scripts/build-daemon
scripts/build-android
```

The APK is signed with a development key. Keep that key for upgrade installs;
use your own release signing configuration before distribution.

## Deploy to this machine

The deployment is staged; building does not replace the running daemon.

For Unified, put the verified model files in
`~/.local/share/voxtype/models/parakeet-unified-en-0.6b` and configure:

```toml
[parakeet]
model = "parakeet-unified-en-0.6b"
on_demand_loading = false
streaming = true
streaming_chunk_secs = 0.56
streaming_left_context_secs = 5.6
streaming_right_context_secs = 0.56
```

See [streaming.md](docs/streaming.md) for the pinned model export and accuracy/speed
tradeoffs. Back up the current configuration before changing the model.

```sh
scripts/install-daemon
journalctl --user -u voxtype -n 40
scripts/expose-tailscale
```

The installer adds a drop-in to the existing `voxtype.service` and restarts it.
Systemd stops the old daemon before starting the replacement. No second daemon
is started for testing or warmup. The packaged executable and existing config
remain available for rollback. API readiness is logged after model loading.
When reverting a model switch, restore the matching configuration backup along
with the previous binary before restarting the service.

The mobile script mounts **/voxtype** on the existing Tailscale HTTPS listener
(port 443), preserving the other routes. It refuses to replace a conflicting
/voxtype target. Tailscale HTTPS must be enabled in the tailnet, and tailnet
policy must allow the phone to reach TCP 443. The example grant in `infra/` is
for merging into your policy; it applies to the shared port, not individual URL
paths. Serve strips /voxtype before forwarding to the daemon.

Install `artifacts/voxtype-mobile-debug.apk` on the phone (or use
`adb install -r artifacts/voxtype-mobile-debug.apk`). Enable Tailscale, then enter:

```text
wss://omafwchy.taila510b.ts.net/voxtype/v1/dictate
```

Copy the pairing token from `~/.local/share/voxtype-mobile/token` into the app.
Grant microphone permission and enable Voxtype Mobile accessibility. Android may
require **App info → ⋮ → Allow restricted settings** for a sideloaded APK.

To rotate the token, replace that file with a new random secret, keep mode 0600,
restart `voxtype.service`, and save the new token in the phone app.

To restore:

```sh
scripts/uninstall-daemon
tailscale serve --https=443 --set-path=/voxtype off
```

## Verification

```sh
cargo test --manifest-path daemon/Cargo.toml --features parakeet --lib remote::tests
android/gradlew -p android :app:testDebugUnitTest :app:lintDebug
```

Rust tests use a fake transcriber to check shared instance reuse, serialized
preview/final inference, cancellation draining, audio limits, PCM decoding and
an authenticated real WebSocket exchange. They load no model. Android unit tests
cover editor routing, stale focus, accessibility paste, selection replacement,
emoji offsets, password input variations, gestures and measured audio levels.
Parakeet streaming regression tests run with
`cargo test --manifest-path daemon/Cargo.toml --features parakeet --lib parakeet_streaming`.
See `docs/device-validation.md` for the required real-device and resident-model
acceptance checks. Build results do not imply these have passed.

## References

- [Voxtype 1.0.1 source](https://github.com/peteonrails/voxtype/tree/dda37ca72b71294d08b0c5bb49c5b24ca590d847)
- [Android accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android text actions](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_SET_TEXT)
- [Android accessibility input connections](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection)
- [Android microphone service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve)
