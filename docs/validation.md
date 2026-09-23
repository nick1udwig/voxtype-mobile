# Validation — 2026-09-22

- Android debug APK **0.1.6 (versionCode 7)**: built successfully, development signature verified with
  Android SDK `apksigner` (v2 signature, one signer).
- Android JVM/Robolectric tests: **60 passed**, covering resumable uploads, cached results, explicit cancellation, prompt finish ordering, progress-aware final timeouts,
  unchanged-preview stall detection, faster stalled-upload recovery, and retained-audio replay, transient server errors, busy
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
- Rust: **38 remote tests passed** in the latest run, including session resumption.
  The earlier deployment also passed 13 Parakeet streaming worker/configuration
  tests. The WebSocket tests use
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

Version 0.1.6 is built and signed but has not been installed or exercised on the
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

## Latency update — 2026-09-22

Live configuration now uses chunk/left/right = **1.12/5.6/0.56 seconds**, keeping
Unified as the sole model. On the same 21.012-second burst fixture, completion
improved from **13.187 to 7.214 seconds**. A 63.036-second paced test retained all
12 sentences and completed **0.924 seconds after release**, with its first
partial at 1.732 seconds. These are single-run local fixture checks, not measured
phone latency or a representative recognition benchmark. The earlier config is
backed up in `~/.local/share/voxtype-mobile/backups/20260922-171212-streaming-timing/`.

Android v0.1.5 passes **53 tests**, lint has **0 errors / 24 warnings**, and its
APK signature verifies. Four new regressions cover FIFO finish delivery without
queue-empty polling, continued inference progress beyond the original timeout,
identical-preview stalls, and faster detection of blocked uploads. Existing
cancellation, bounded replay and exactly-once insertion tests continue to pass.
The daemon's 28 selected remote tests pass with the new timing diagnostics.
See [performance.md](performance.md) for the deployed profile and limitations.

The latency-instrumented release was deployed at 19:19:06 PDT, with rollback
binary/hashes at `/home/nick/.local/share/voxtype-mobile/backups/20260922-191906-latency`. PID 1073983 is the sole daemon,
and its running executable hash matches the artifact. It loaded Unified once in
2.27 seconds with the 1.12/5.6/0.56 profile. A paced 5.253-second post-deployment
check returned the complete sentence 0.351 seconds after release.
Both transport and worker logs report about 350 ms after finish and zero measured
queue-capacity wait. No model copy or alternate inference process was started.
The active submodule pin is `b5eb60f`; GitHub publication remains pending approval.


## Resumable sessions — 2026-09-22

Android **0.1.6 (code 7)** passes **60 unit tests**, with **0 lint errors / 24
warnings**; APK v2 development signature verifies. Seven new client regressions
cover acknowledged-prefix upload, checkpoints within a frame, cached final
retrieval, daemon restart replay, invalid checkpoints, session identity across
network versus inference failures, and explicit cancellation before readiness.

The daemon passes **38 tests matching `remote::`** (including the nested resume
module). Real WebSocket tests with fake inference verify one worker across a
reconnect, exact sample delivery without duplicated audio, cached final recovery,
stale-connection rejection, invalid offsets and cancellation before the next
admission. Virtual-clock tests verify reconnect expiry, final cache erasure,
offline completion after finish, byte limits and duplicate accounting. These
tests instantiate no real model. The independent daemon commit is `5e8311c`.

Brief disconnects retain the original streaming inference; completed results are
cached for 90 seconds or until acknowledgment/eviction. State is bounded and
memory-only. Restart/expiry/inference error can still require full replay from
the Android recording. Actual Wi-Fi/cellular handoff, editor insertion and
cancellation on the Pixel require [device checks](device-validation.md).

The release build completed in 2m 33s. The existing service was replaced at
20:09:03 PDT, with rollback binary and hashes at
`~/.local/share/voxtype-mobile/backups/20260922-200903-resume/`. PID 1158366 was
verified as the sole daemon, and its executable hash matches the release artifact.
The journal records one Unified load in 2.44 seconds with the unchanged
1.12/5.6/0.56 timing profile. Tailscale routing remains `/voxtype` on HTTPS 443.

A paced **10.506-second** public fixture test disconnected after three seconds,
resumed at **96,000 accepted PCM bytes**, disconnected again just after finish,
and recovered both complete test sentences **0.771 seconds after release**.
A further reconnect retrieved the identical cached final in **under 1 ms** locally,
without any audio upload or additional inference. The journal records one
10.506-second completed worker and three resumed attachments. The client then
acknowledged the final to clear the cache. These are single-run loopback timings,
not phone-network latency measurements. Real Wi-Fi/cellular handoff is untested.
