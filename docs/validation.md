# Validation — 2026-09-22

This is a dated validation and deployment history.
Version numbers, process IDs, model profiles and publication notes describe the state at each recorded check, not the current installation.
For setup use the [README](../README.md); current fork publication and development are described in [daemon-upstream.md](daemon-upstream.md).

- Android debug APK **0.1.6 (versionCode 7)**: built successfully, development signature verified with Android SDK `apksigner` (v2 signature, one signer).
- Android JVM/Robolectric tests: **60 passed**, covering resumable uploads, cached results, explicit cancellation, prompt finish ordering, progress-aware final timeouts, unchanged-preview stall detection, faster stalled-upload recovery, and retained-audio replay, transient server errors, busy admission, stale/duplicate results, automatic retry exhaustion, manual retry with updated pairing settings, handshake/final timeouts, upload backpressure, bounded memory, and hold/tap/cancel/drag, raw pointer coordinates as the window moves, multitouch cancellation, drag during recording/transcription, persisted position, keyboard clamping, rotation, expansion without moving the main button, selection replacement, emoji offsets, missing accessibility text/selection, native editor input connections, stale connections after switching apps, password protection, hidden browser inputs, descendant focus lookup, paste fallback and PCM activity levels.
- Android lint: **0 errors, 24 warnings**.
  Remaining warnings are English UI strings, API annotation suggestions, the programmatic button constructor and intentional physical-left window gravity for screen-coordinate dragging.
- Updated overlay follows both supplied references and the subsequent icon request: lavender idle microphone, X / microphone activity pill / purple check while recording.
  Activity uses measured audio levels without another capture stream or recognition model.
- Version 0.1.2 reuses the app's microphone drawable for the idle button.
  The subsequent daemon streaming implementation is described in [streaming.md](streaming.md).
  Version 0.1.3 adds dragging using Android's touch threshold and frame-paced window updates.
  Position is saved on release; expanded controls adapt to edges while the microphone/check stays under the finger.
  A drag discards capture started by that press, but preserves an already-running dictation.
  This update changes only the Android app and continues using the deployed Unified daemon.
- Rust: **38 remote tests passed** in the latest run, including session resumption.
  The earlier deployment also passed 13 Parakeet streaming worker/configuration tests.
  The WebSocket tests use an authenticated loopback connection; every transcriber used is a fake.
- Daemon: optimized release build with `--features parakeet --bin voxtype` passed.
  `--version` returns 1.0.1; `ldd` resolves all required libraries on this host.
  ONNX Runtime is linked statically; the artifact does not need a separate ORT shared library or an inference service.
- Deployment scripts pass Bash syntax checking; upstream patch whitespace check passes.
  The installer was exercised for the Unified deployment; the failure rollback path was not forced on the live service.
- The build-created Gradle daemon was stopped after verification.

## Live Unified deployment

The old daemon was stopped before its replacement started.
The active `voxtype.service` process (PID 748866 at validation) loaded Unified once in 2.86 s.
Process inspection found exactly one Voxtype daemon; its RSS after the short and long tests was about 2.55 GiB.
The existing Tailscale HTTPS route remains `SERVER.TAILNET.ts.net:443/voxtype` → `127.0.0.1:8765`.

Configuration: `parakeet-unified-en-0.6b`, preloaded, streaming enabled, chunk/left/right = 0.56/5.6/0.56 seconds.
The previous binary and TDT configuration are backed up in `~/.local/share/voxtype-mobile/backups/20260922-161006-unified/`.

Live checks send the same public `speech_long.wav` repository fixture in paced 100 ms WebSocket frames, with 500 ms silence between repeats.
The two models were measured sequentially through their respective sole running daemon after the build completed.
These are single-run loopback checks, not phone-network latency or a representative accuracy benchmark.

| Audio duration | TDT batch release → final | Unified streaming release → final |
| --- | ---: | ---: |
| 5.253 s | 0.340 s | 0.748 s |
| 21.012 s | 2.006 s | 1.060 s |
| 63.036 s | Not measured | 1.130 s |

Unified sent its first partial at about 1.74 s and returned the complete opening and ending words.
The 21-second result contains all four repeated sentences; the minute-long result contains all twelve.
Release delay remained about a second through the minute-long test, with no audio queue overload.
The synthetic fixture has some recognition differences (e.g. “on” versus “of”); this is not evidence that one model is universally more accurate.

Artifacts are in `artifacts/`; their checksums are in `artifacts/SHA256SUMS`.
They are local build outputs and are intentionally excluded from Git.

## Remaining acceptance work

The user reports end-to-end dictation working on a GrapheneOS Pixel 10 Pro, but the original app copied text instead of inserting and missed the ChatGPT Remote native app's composer.
Version 0.1.1 addresses both via Android's accessibility input connection and broader focus discovery.
These fixes and the new control layout still require testing on that phone after disabling/re-enabling the accessibility service.
Input-connection commits have no editor acknowledgment.
See [device-validation.md](device-validation.md) for the remaining device and resident-model checks.
Unified replaces TDT in the existing service; no real model was started alongside it and no Tailscale route was changed.

Version 0.1.6 is built and signed but has not been installed or exercised on the Pixel from this workspace.
Actual drag smoothness, window/input focus behavior, keyboard changes and rotation require the added device checks.

The phone now uses the existing Unified streaming backend.
Desktop and phone sessions share its model weights.
Actual phone insertion and desktop hotkey behavior still require device checks; loopback and fake-backend tests do not establish every editor's behavior.

## Recovery regression — 2026-09-22

The live daemon logged `stream_overloaded` at 16:35:18 and 16:36:10 PDT.
The remote adapter used `try_send` into a 64-frame recognizer queue, treating a transient backlog as a fatal transcription error.
Both network admission and recognizer forwarding now wait for channel capacity while draining result events.
Queues remain bounded, no audio is dropped, and cancellation joins the worker.

**28 Rust tests matching `remote::tests` passed**, including three new tests: 300-frame recognition bursts, full authenticated WebSocket bursts beyond both queue capacities, and cancellation while backpressured.
Fake models assert complete sample delivery and release of the model before another admission.

Android adds 11 transport/recovery tests (49 total).
The final APK passes lint with zero errors and its development signature verifies.
Audio is memory-only; process death still loses it.
The phone's actual reconnect/retry UI remains a device acceptance check.

The optimized daemon build passed in 8m 19s and replaced the existing service at 16:54:41 PDT.
Readiness returned `preview: streaming`.
PID 841685 is the only Voxtype **daemon** (other Voxtype processes are status watchers); its executable hash matches the release artifact.
The journal shows one Unified load in 2.30 s.
The previous executable and before/after hashes are backed up at `~/.local/share/voxtype-mobile/backups/20260922-165441-backpressure/`.
No service configuration, Tailscale route or model selection changed.

Live burst validation sent 63.036 seconds of the known public speech fixture without real-time pacing, deliberately exceeding the old 64-frame queues.
It completed with all 12 repeated sentences and 94 partial events; no transcription errors appeared in the deployment journal.
The model caught up in 47.811 seconds after this instantaneous upload.
This is an overload/replay stress case, **not** normal streaming release latency; the phone normally uploads while the user speaks.

A paced 5.253-second follow-up returned its final transcript 0.619 seconds after release, with the complete test sentence.

## Repository separation

`daemon/` is a proper gitlink at `b472e5f`, with full upstream history and three focused fork commits.
A throwaway parent clone initialized that exact pin from a local URL override successfully.
The proposed GitHub fork URL remains awaiting publication approval, so a remote recursive clone is not yet verified/available.
The Android/build/deployment integration is committed in the parent; no vendored Rust files or generated model/build outputs are tracked there.

## Latency update — 2026-09-22

Live configuration now uses chunk/left/right = **1.12/5.6/0.56 seconds**, keeping Unified as the sole model.
On the same 21.012-second burst fixture, completion improved from **13.187 to 7.214 seconds**.
A 63.036-second paced test retained all 12 sentences and completed **0.924 seconds after release**, with its first partial at 1.732 seconds.
These are single-run local fixture checks, not measured phone latency or a representative recognition benchmark.
The earlier config is backed up in `~/.local/share/voxtype-mobile/backups/20260922-171212-streaming-timing/`.

Android v0.1.5 passes **53 tests**, lint has **0 errors / 24 warnings**, and its APK signature verifies.
Four new regressions cover FIFO finish delivery without queue-empty polling, continued inference progress beyond the original timeout, identical-preview stalls, and faster detection of blocked uploads.
Existing cancellation, bounded replay and exactly-once insertion tests continue to pass.
The daemon's 28 selected remote tests pass with the new timing diagnostics.
See [performance.md](performance.md) for the deployed profile and limitations.

The latency-instrumented release was deployed at 19:19:06 PDT, with rollback binary/hashes at `~/.local/share/voxtype-mobile/backups/20260922-191906-latency`.
PID 1073983 is the sole daemon, and its running executable hash matches the artifact.
It loaded Unified once in 2.27 seconds with the 1.12/5.6/0.56 profile.
A paced 5.253-second post-deployment check returned the complete sentence 0.351 seconds after release.
Both transport and worker logs report about 350 ms after finish and zero measured queue-capacity wait.
No model copy or alternate inference process was started.
The submodule revision tested here was `b5eb60f`.
The fork has since been published; see [daemon-upstream.md](daemon-upstream.md).


## Resumable sessions — 2026-09-22

Android **0.1.6 (code 7)** passes **60 unit tests**, with **0 lint errors / 24 warnings**; APK v2 development signature verifies.
Seven new client regressions cover acknowledged-prefix upload, checkpoints within a frame, cached final retrieval, daemon restart replay, invalid checkpoints, session identity across network versus inference failures, and explicit cancellation before readiness.

The daemon passes **38 tests matching `remote::`** (including the nested resume module).
Real WebSocket tests with fake inference verify one worker across a reconnect, exact sample delivery without duplicated audio, cached final recovery, stale-connection rejection, invalid offsets and cancellation before the next admission.
Virtual-clock tests verify reconnect expiry, final cache erasure, offline completion after finish, byte limits and duplicate accounting.
These tests instantiate no real model.
The independent daemon commit is `5e8311c`.

Brief disconnects retain the original streaming inference; completed results are cached for 90 seconds or until acknowledgment/eviction.
State is bounded and memory-only.
Restart/expiry/inference error can still require full replay from the Android recording.
Actual Wi-Fi/cellular handoff, editor insertion and cancellation on the Pixel require [device checks](device-validation.md).

The release build completed in 2m 33s.
The existing service was replaced at 20:09:03 PDT, with rollback binary and hashes at `~/.local/share/voxtype-mobile/backups/20260922-200903-resume/`.
PID 1158366 was verified as the sole daemon, and its executable hash matches the release artifact.
The journal records one Unified load in 2.44 seconds with the unchanged 1.12/5.6/0.56 timing profile.
Tailscale routing remains `/voxtype` on HTTPS 443.

A paced **10.506-second** public fixture test disconnected after three seconds, resumed at **96,000 accepted PCM bytes**, disconnected again just after finish, and recovered both complete test sentences **0.771 seconds after release**.
A further reconnect retrieved the identical cached final in **under 1 ms** locally, without any audio upload or additional inference.
The journal records one 10.506-second completed worker and three resumed attachments.
The client then acknowledged the final to clear the cache.
These are single-run loopback timings, not phone-network latency measurements.
Real Wi-Fi/cellular handoff is untested.


## Omarchy desktop regression — 2026-09-23

The desktop journal showed SIGUSR1 starting Unified recording and SIGUSR2 "disowning" its output session on stop.
That legacy incremental-streaming policy discarded the final transcript after this fork changed Unified to commit only at completion.
Omarchy's bar also recognizes `recording`, whereas the streaming path wrote `streaming`.
Mobile-only tests did not cover the compositor signal path, so the earlier desktop-compatibility claim was insufficiently verified.

Daemon commit `b407839` retains Unified's final through stop/release, joins its worker, then uses the ordinary desktop result/output pipeline.
This restores CLI file/clipboard/type overrides, text/profile processing, modifier-release handling and file completion results.
Public state progresses recording → transcribing → idle.
Explicit cancel still discards text; mobile sessions still use their separate remote result destination and share the same loaded model.

**3 desktop regressions, 38 remote tests and 13 Unified streaming tests passed.**
Desktop regressions run in isolated child processes so runtime CLI sentinels cannot affect the user's daemon.
They verify stop retains the final, output waits for worker completion, previews never become fallback results, configured text processing/file output work, and cancellation joins without insertion.

The release build passed in 2m 48s and replaced the sole daemon at 10:29:58 PDT.
Rollback: `~/.local/share/voxtype-mobile/backups/20260923-102958-desktop/`.
PID 2582715 matches the release artifact and loaded Unified once in 2.60 seconds.
The model/timing profile, Tailscale mount and Omarchy bindings were unchanged.

Live desktop validation used the packaged `/usr/bin/voxtype record toggle` (the command behind Omarchy's shortcut), with a temporary PipeWire virtual microphone playing the public speech fixture and a per-recording file output.
Status JSON reported recording; stop progressed recording → transcribing → idle and wrote the complete sentence **0.343 seconds after stop**, with no output before stop.
A subsequent start/cancel returned idle without writing text.
The original default audio source/sink were restored and the virtual sink removed.
Initial test harness attempts were cancelled while identifying ALSA's capture node (it exposes a node name rather than a process ID).
No physical microphone audio was used.
Actual focused-editor injection and visual bar rendering still require user confirmation; the live check verified their command/status contract and the restored desktop file-output pipeline.

A subsequent live mobile resumption test on the same daemon retained all 10.506 seconds of fixture speech across disconnects during recording and after finish.
Final latency was 0.808 seconds; an unacknowledged final was retrieved from cache in under 1 ms locally.
No second model or daemon was started.


## Lock-screen dictation — 2026-09-27

Android 0.1.7 continues an already-started microphone foreground recording through screen off and keyguard focus.
The service no longer cancels dictation on `ACTION_SCREEN_OFF`, on accessibility feedback interruption, or merely because the keyguard shows a password field.
It hides the overlay while locked and abandons a cancelled pointer without discarding the audio session.
An already-completed transcript waits in memory for unlock, with no transcript text in the ready notification.
The five-minute cap remains in force.

The app did not need a new inference service or model.
Actual GrapheneOS lock and background microphone behavior still needs the Pixel checks listed in [device-validation.md](device-validation.md).

The 0.1.7 APK (versionCode 8) was built with `scripts/build-android`: **65 Android unit tests passed**, Android lint reported **0 errors / 26 warnings**, and the APK v2 development signature verified with `apksigner`.
New regressions cover system touch cancellation preserving capture, completion being withheld through screen off and keyguard, one-time delivery after unlock, and explicit discard.
The daemon and model were unchanged.
No Android device was attached over ADB, so the foreground microphone and actual editor insertion while locking remain device acceptance checks.


## Opus-only implementation — 2026-10-03

Android 0.1.9 (versionCode 10) and daemon commit `4b2cc65` require mobile protocol 2 with `opus_v1`.
Raw PCM/zlib wire paths and compatibility fallback are removed.
The `/v1/dictate` route remains stable for existing proxy mounts.
These are local build and fake-model results; the installed app and running sole daemon/model owner were not changed or launched by this work.

Validation completed:

- `scripts/build-android`: APK assembly, 70 JVM tests and lint passed, with zero failures, errors or skipped tests.
  The 27 transport/codec tests include 17 session recovery cases, seven encoder/framing cases and three bounded capture startup/cancellation/saturation cases.
  Remaining tests cover existing editor, gesture, screen-lock and accessibility behavior.
- Required `cargo test --manifest-path daemon/Cargo.toml --locked --features parakeet --lib remote::`: 47 passed.
  Fake-model WebSocket/actor checks cover busy/cancel ownership, backpressure, cached-final replay/ack, expiry, stale socket epochs, sequence gaps, duplicates, invalid frames, duration bounds and full original replay.
  No real model is loaded.
- Focused `parakeet_streaming`: 13 passed; `unified_desktop`: four passed.
- `scripts/build-daemon`: locked release build passed.
  The built binary was copied to artifacts and was never launched alongside the installed daemon.
- Python current Opus benchmark fake-backend check: one test passed (four exact-duration subcases).
  Codec-only reference checks: nine passed; historical fake adapter checks: two passed; fake installer checks: five passed.
- Rust formatting, shell syntax and Git whitespace checks passed.

The synthetic real-libopus golden fixture contains a 440 Hz tone beginning immediately and ending at original sample 1601.
Rust checks exact sample count, first/tail energy and pre-skip alignment; its actor output matches uninterrupted native decoding after reconnect, a corrupted duplicate and a stale socket.
Android reconstructs identical production wire frames from that fixture.
Fake codec lifecycle tests cover source lengths 1 sample and 95/100/101 ms, empty and packet EOS, split PCM content and zero padding, actual delay config, missing EOS, input stall and cancellation.
Delayed codec initialization preserves already-captured short audio; a full eight-chunk live PCM handoff fails explicitly instead of blocking microphone reads and silently dropping samples.

Built artifacts (local, ignored):

- `artifacts/voxtype-mobile-debug.apk`, SHA-256 `6ad791e7f24527015318ed7b280b364ae2a778d1be21e60cead282579f81caa7`.
- `artifacts/voxtype-mobile-daemon`, SHA-256 `fafb7405be62136782db726605bf6a23cceee6cacc87708e79ccebcc0683b935`.

No connected Android device or emulator was available.
Actual MediaCodec/OEM config, latency, microphone behavior, accessibility insertion, cellular recovery and battery use remain unvalidated.
Follow `docs/device-validation.md` on the phone before deploying the coordinated upgrade.
The earlier host VOIP Opus benchmarks use different application/VBR choices from Android's AOSP C2 encoder; their latency/size numbers do not establish phone performance.

The current `scripts/benchmark-latency.py` uses direct Opus protocol 2.
The raw/zlib/Opus+zlib comparative scripts remain explicitly historical and require the removed protocol 1 API.
The fork was unpublished at this check and has since been published.
Future parent pins must remain fetchable from the fork URL.


## Release build and signing instructions — 2026-10-09

`scripts/build-android --release` built the unsigned 0.2.0 release APK and ran **81 JVM tests**, with zero failures, errors or skipped tests.
Release lint reported **0 errors / 27 warnings**.

The documented keytool, zipalign and apksigner sequence was exercised with a new temporary 3072-bit RSA key.
The resulting APK passed signature verification (v3) and alignment verification.
The test key and signed test APK were deleted; no production release key was generated and no APK was installed or published.
The unsigned artifact remains in the ignored `artifacts/` directory.

This checked the build/signing procedure, not physical-phone behavior.
The upstream merge was inspected separately in a disposable clone and was not resolved or deployed; see [upstream-merge.md](upstream-merge.md).
