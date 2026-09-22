# Validation — 2026-09-22

- Android debug APK **0.1.4 (versionCode 5)**: built successfully, development signature verified with
  Android SDK `apksigner` (v2 signature, one signer).
- Android JVM/Robolectric tests: **49 passed**, covering retained-audio replay, transient server errors, busy
  admission, stale/duplicate results, automatic retry exhaustion, manual retry
  with updated pairing settings, handshake/final timeouts, upload backpressure,
  bounded memory, and hold/tap/cancel/drag, raw
  pointer coordinates as the window moves, multitouch cancellation, drag during
  recording/transcription, persisted position, keyboard clamping, rotation,
  expansion without moving the main button, selection
  replacement, emoji offsets, missing accessibility text/selection, native editor
  input connections, stale connections after switching apps, password protection,
  hidden browser inputs, descendant focus lookup, paste fallback and PCM activity levels.
- Android lint: **0 errors, 24 warnings**. Remaining warnings are English UI
  strings, API annotation suggestions, the programmatic button constructor and
  intentional physical-left window gravity for screen-coordinate dragging.
- Updated overlay follows both supplied references and the subsequent icon request: lavender idle microphone,
  X / microphone activity pill / purple check while recording. Activity uses
  measured audio levels without another capture stream or recognition model.
- Version 0.1.2 reuses the app's microphone drawable for the idle button. The
  subsequent daemon streaming implementation is described in [streaming.md](streaming.md).
  Version 0.1.3 adds dragging using Android's touch threshold and frame-paced
  window updates. Position is saved on release; expanded controls adapt to edges
  while the microphone/check stays under the finger. A drag discards capture
  started by that press, but preserves an already-running dictation. This update
  changes only the Android app and continues using the deployed Unified daemon.
- Rust: **38 selected tests passed** (13 Parakeet streaming worker/configuration
  tests and 25 tests matching `remote::tests`). The WebSocket test uses
  an authenticated loopback connection; every transcriber used is a fake.
- Daemon: optimized release build with `--features parakeet --bin voxtype` passed.
  `--version` returns 1.0.1; `ldd` resolves all required libraries on this host.
  ONNX Runtime is linked statically; the artifact does not need a separate ORT
  shared library or an inference service.
- Deployment scripts pass Bash syntax checking; upstream patch whitespace check
  passes. The installer was exercised for the Unified deployment; the failure
  rollback path was not forced on the live service.
- The build-created Gradle daemon was stopped after verification.

## Live Unified deployment

The old daemon was stopped before its replacement started. The active
`voxtype.service` process (PID 748866 at validation) loaded Unified once in 2.86 s.
Process inspection found exactly one Voxtype daemon; its RSS after the short and
long tests was about 2.55 GiB. The existing Tailscale HTTPS route remains
`omafwchy.taila510b.ts.net:443/voxtype` → `127.0.0.1:8765`.

Configuration: `parakeet-unified-en-0.6b`, preloaded, streaming enabled,
chunk/left/right = 0.56/5.6/0.56 seconds. The previous binary and TDT configuration
are backed up in `~/.local/share/voxtype-mobile/backups/20260922-161006-unified/`.

Live checks send the same public `speech_long.wav` repository fixture in paced
100 ms WebSocket frames, with 500 ms silence between repeats. The two models
were measured sequentially through their respective sole running daemon after
the build completed. These are single-run loopback checks, not phone-network
latency or a representative accuracy benchmark.

| Audio duration | TDT batch release → final | Unified streaming release → final |
| --- | ---: | ---: |
| 5.253 s | 0.340 s | 0.748 s |
| 21.012 s | 2.006 s | 1.060 s |
| 63.036 s | Not measured | 1.130 s |

Unified sent its first partial at about 1.74 s and returned the complete opening
and ending words. The 21-second result contains all four repeated sentences;
the minute-long result contains all twelve. Release delay remained about a
second through the minute-long test, with no audio queue overload.
The synthetic fixture has some recognition differences (e.g. “on” versus “of”);
this is not evidence that one model is universally more accurate.

Artifacts are in `artifacts/`; their checksums are in `artifacts/SHA256SUMS`.
They are local build outputs and are intentionally excluded from Git.

## Remaining acceptance work

The user reports end-to-end dictation working on a GrapheneOS Pixel 10 Pro, but
the original app copied text instead of inserting and missed the ChatGPT Remote
native app's composer. Version 0.1.1 addresses both via Android's accessibility
input connection and broader focus discovery. These fixes and the new control
layout still require testing on that phone after disabling/re-enabling the
accessibility service. Input-connection commits have no editor acknowledgment.
See [device-validation.md](device-validation.md) for the remaining device and
resident-model checks. Unified replaces TDT in the existing service; no real
model was started alongside it and no Tailscale route was changed.

Version 0.1.4 is built and signed but has not been installed or exercised on the
Pixel from this workspace. Actual drag smoothness, window/input focus behavior,
keyboard changes and rotation require the added device checks.

The phone now uses the existing Unified streaming backend. Desktop and phone
sessions share its model weights. Actual phone insertion and desktop hotkey
behavior still require device checks; loopback and fake-backend tests do not
establish every editor's behavior.

## Recovery regression — 2026-09-22

The live daemon logged `stream_overloaded` at 16:35:18 and 16:36:10 PDT. The
remote adapter used `try_send` into a 64-frame recognizer queue, treating a
transient backlog as a fatal transcription error. Both network admission and
recognizer forwarding now wait for channel capacity while draining result events.
Queues remain bounded, no audio is dropped, and cancellation joins the worker.

**28 Rust tests matching `remote::tests` passed**, including three new tests:
300-frame recognition bursts, full authenticated WebSocket bursts beyond both
queue capacities, and cancellation while backpressured. Fake models assert
complete sample delivery and release of the model before another admission.

Android adds 11 transport/recovery tests (49 total). The final APK passes lint
with zero errors and its development signature verifies. Audio is memory-only;
process death still loses it. The phone's actual reconnect/retry UI remains a
device acceptance check.

The optimized daemon build passed in 8m 19s and replaced the existing service at
16:54:41 PDT. Readiness returned `preview: streaming`. PID 841685 is the only
Voxtype **daemon** (other Voxtype processes are status watchers); its executable
hash matches the release artifact. The journal shows one Unified load in 2.30 s.
The previous executable and before/after hashes are backed up at
`~/.local/share/voxtype-mobile/backups/20260922-165441-backpressure/`.
No service configuration, Tailscale route or model selection changed.

Live burst validation sent 63.036 seconds of the known public speech fixture
without real-time pacing, deliberately exceeding the old 64-frame queues. It
completed with all 12 repeated sentences and 94 partial events; no transcription
errors appeared in the deployment journal. The model caught up in 47.811 seconds
after this instantaneous upload. This is an overload/replay stress case, **not**
normal streaming release latency; the phone normally uploads while the user speaks.

A paced 5.253-second follow-up returned its final transcript
0.619 seconds after release, with the complete test sentence.

## Repository separation

`daemon/` is a proper gitlink at `b472e5f`, with full upstream history and three
focused fork commits. A throwaway parent clone initialized that exact pin from
a local URL override successfully. The proposed GitHub fork URL remains awaiting
publication approval, so a remote recursive clone is not yet verified/available.
The Android/build/deployment integration is committed in the parent; no vendored
Rust files or generated model/build outputs are tracked there.
