# Off-network latency investigation — 2026-10-03

Release latency includes queued upload, unfinished inference, final processing,
and delivery back to the phone. A roughly one-round-trip network penalty is
possible only when upload and inference have kept up throughout recording.
Connection setup, loss recovery and reconnect backoff add other delays.

## Evidence from the existing service

Metadata-only logs over the preceding seven days contained 252 completed
resumable sessions and five resume events. After the daemon received `finish`,
the median completion time was 473 ms and the 90th percentile was 716 ms.
The maximum was 72.165 seconds. These are server timings, not phone release
timings; they exclude audio still waiting to reach the daemon.

A 34.1-second recording on October 3 reconnected twice and occupied the daemon
session for 48.579 seconds, with 1.550 seconds after receipt of `finish`.
That is consistent with transport interruption/backlog, but does not identify
the exact instant the user released the button. The largest inference outliers
were on October 1 before the current service restart. One had 69.534 seconds of
worker queue-capacity wait, demonstrating a separate inference backlog.

A live Tailscale probe reached the online Android phone directly over IPv6 in
112 ms round trip. This is one current probe, not evidence about the earlier
slow network. The host was using its performance power profile.

## Experiment 1: CPU priority

The existing daemon ran at nice 0, with no CPU quota or configured CPU weight.
Its hard `RLIMIT_NICE` was 0 and noninteractive administrative access was not
available. Negative niceness was therefore **not tested**. Lower nice values
give greater priority; raising niceness would do the opposite.

As a separate, reversible scheduling experiment, we changed the existing user
service's runtime `CPUWeight` from the effective default 100 to 500. No model or
daemon was started for this experiment. All measurements used the same running
PID and the public `speech_long.wav` fixture, repeated twice with 500 ms silence
after each repetition: 10.506 seconds of 16 kHz mono PCM, paced in 100 ms frames.
Eight bounded CPU workers provided load in a sibling service under `app.slice`.

| Condition | Weight 100 release → final (s) | Weight 500 release → final (s) |
| --- | --- | --- |
| Unloaded, three runs | 0.726, 0.737, 0.712 | 0.734, 0.727, 0.734 |
| Loaded, initial blocks (500 first) | 3.224, 17.656, 10.895 | 2.466, 2.935, 2.371 |
| Loaded, continuing load, order 100 / 500 / 500 / 100 | 2.535, 2.418 | 2.540, 4.012 |

The initial apparent advantage did not survive the reversal. CPU pressure was
low, no cgroup quota throttling was recorded, and upload pacing lag was below
1 ms. Daemon completion logs agreed with the measured tails. Load can cause
large inference delays, but this experiment does not establish that CPUWeight
fixes them. Core placement, shared power/thermal limits and inference execution
behavior remain hypotheses; they were not isolated here.

**Decision:** do not add CPUWeight or negative Nice settings to the installer.
The runtime weight was restored to unset, all test workers stopped, and the
original daemon remained the sole model owner.

The reusable `scripts/benchmark-latency.py` records only timing and counts. Its
release metric uses the capture clock, so a slow sender cannot hide upload
backlog by starting the timer after upload. It never logs audio, transcripts,
transcript hashes or the pairing token. Run it only against an idle existing
service; a busy response is a failed trial, not permission to replace a session.

```sh
scripts/benchmark-latency.py --runs 3 --repeats 2 --label unloaded \
  --output artifacts/latency/unloaded.json
scripts/benchmark-latency.py --runs 3 --repeats 2 --workers 8 --label loaded \
  --output artifacts/latency/loaded.json
```

Original local measurements are in ignored `artifacts/latency/`. The initial CPU
comparison predates the explicit capture-clock field; its sub-millisecond local
upload lag does not materially affect the numbers above.

## Experiment 2: lossless audio compression

Android 0.1.8 negotiates independent zlib frames with the daemon, retaining raw
PCM if the server does not acknowledge support. A frame that does not compress
uses a raw envelope. Decoding restores every PCM sample exactly; model input,
sample rate, inference settings and resume offsets remain unchanged. See the
[wire protocol](../daemon/docs/remote-api.md#optional-lossless-pcm-transport).

The tested release build temporarily replaced the existing service binary.
Systemd stopped the original daemon before starting the candidate, and all
trials used that candidate's sole existing model. No inference sidecar was used.
Raw and compressed trials used the same build and model, with no competing
benchmark workers or builds. The original deployed binary was retained for
restoration after the experiment; the new APK was not installed on the phone.
After the measurements, the original binary was restored and its SHA-256
matched the pre-experiment binary. Runtime CPUWeight remained unset and Nice 0.

`scripts/benchmark-transport.py` forwards the actual authenticated WebSocket
stream through a local userspace bottleneck. It serializes packets of at most
1,200 bytes, with bounded 64-packet queues and 50 ms propagation in each
direction. Propagation is pipelined, not charged serially for every packet.
A calibration transferred 64,000 bytes at 512 kbit/s plus 50 ms in 1.05021 s
(expected 1.05 s). The shaper includes WebSocket bytes but **does not simulate
TLS/IP overhead, cellular radio behavior, TCP loss or retransmissions**.

The same 10.506-second fixture was captured in 100 ms frames. Each condition
ran three times; raw/compressed order reversed on the second repetition. Times
below start at the end of capture, including any upload backlog.

| Upload limit, 100 ms RTT | Raw median release → final | Compressed median | Improvement |
| --- | ---: | ---: | ---: |
| 512 kbit/s | 0.896 s | 0.860 s | 0.037 s |
| 192 kbit/s | 4.512 s | 1.041 s | 3.471 s (77%) |
| 128 kbit/s | 11.558 s | 5.897 s | 5.661 s (49%) |

At 192 kbit/s, raw runs ranged from 4.484–4.527 s and compressed runs from
1.032–1.055 s. At 128 kbit/s, ranges were 11.556–11.579 s and 5.873–6.436 s.
At 512 kbit/s, ranges were 0.891–0.907 s and 0.842–0.864 s. This is a small
controlled experiment, not a production latency percentile estimate.

The median upload drain after release was 3.724 → 0.053 s at 192 kbit/s and
10.764 → 4.919 s at 128 kbit/s. Compression sometimes left a longer final
inference tail because it changed audio arrival timing, but the total delay
still improved. Compression does not make a sufficiently slow link cost only
one round trip: the 128 kbit/s case still had a backlog.

Audio frame bodies fell from 336,192 to 242,692 bytes (**27.81% smaller**).
Including WebSocket audio/finish framing, bytes fell from 337,911 to 244,391
(27.68%). All 18 final transcripts matched in memory; neither transcripts nor
hashes were written to diagnostics. Codec round-trip checks and cross-language
tests verified exact PCM restoration. Savings depend on the audio: this fixture
includes half-second silent gaps, and noisy/incompressible audio may use the raw
fallback plus its five-byte envelope.

**Decision:** accept lossless transport compression, with the backward-compatible
fallback, for local master. Do not add the unproven priority settings. A phone
release requires the new checks in [device validation](device-validation.md):
these host tests do not establish phone CPU cost, battery use, real cellular
performance, microphone behavior or editor insertion.

Validation completed: Android APK/unit tests/lint through `scripts/build-android`,
46 focused Rust `remote::` tests using fake models, and `scripts/build-daemon`.
Protocol tests cover old/new negotiation, corruption and decompression limits,
decoded-byte reconnect checkpoints, duplicate audio, and joined cancellation.

```sh
scripts/benchmark-transport.py --runs 3 --kbps 512 192 128 --one-way-ms 50 \
  --output artifacts/latency/transport-comparison.json
```

Run against an existing daemon supporting compression, while it is idle. The
script never installs a binary or loads a model. Host Python encoder CPU timings
are included in the output and exclude verification decompression; they are not
Android encoder measurements. Median host encoding cost was 8.88 ms per
10.506-second recording, with a maximum individual frame cost of 0.805 ms.
Local raw evidence is in
`artifacts/latency/transport-comparison.json` (ignored).

## Other hypotheses

- Raw PCM requires 256 kbit/s before transport overhead. Sustained upload below
  that rate produces a backlog proportional to recording duration. At 128 kbit/s,
  a 20-second recording takes approximately 40 seconds to upload even with
  negligible propagation latency. The compression experiment above confirms the
  practical effect of reducing this backlog.
- Each dictation opens a WebSocket. TCP/TLS/upgrade/admission can take multiple
  round trips, though immediate capture retains speech while the connection opens.
- TCP retransmissions and radio stalls can delay audio independently of the
  displayed signal strength. The current reconnect schedule starts at 500 ms and
  reaches 8 seconds; failure detection also takes time. Shortening these timers
  without measuring actual failure behavior could create unnecessary reconnects.
- A lossy speech codec offers a larger bandwidth reduction. An offline FFmpeg
  Opus 24 kbit/s encode produced an Ogg file about 9% of the fixture's PCM size.
  This is neither an Android implementation nor a recognition accuracy result;
  default adoption requires separate codec, resumption and phone validation.

Android documents Opus encoding from Android 10, and the
[Opus project](https://www.opus-codec.org/) describes its low-bitrate speech
support. See [Android media format support](https://developer.android.com/media/platform/supported-formats).
For service scheduling semantics, see
[systemd's execution documentation](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml).
