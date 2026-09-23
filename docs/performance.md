# Latency and recovery — 2026-09-22

## Deployed CPU streaming profile

The single existing Unified model now uses this profile on the i7-1280P host:

```toml
[parakeet]
model = "parakeet-unified-en-0.6b"
on_demand_loading = false
streaming = true
streaming_chunk_secs = 1.12
streaming_left_context_secs = 5.6
streaming_right_context_secs = 0.56
```

Only chunk duration changed. The backend re-encodes overlapping context for each
chunk; doubling the chunk size reduces that repeated work while retaining the
same left/right context. All durations remain aligned to 80 ms. The upstream
library defaults remain unchanged; this is a measured host-specific deployment
profile. Larger chunks can change recognition and initial-preview timing, so
compare actual dictation quality on the phone as well as speed.

## Measurements

These are sequential tests against the sole running daemon, using its existing
model and the public `daemon/tests/fixtures/vad/speech_long.wav` fixture. The
configurations were swapped by restarting the service, never loading two models.
The fixture repeats with 500 ms silence between repetitions.

| Test | Previous 0.56 s chunk | Deployed 1.12 s chunk |
| --- | ---: | ---: |
| 21.012 s audio uploaded as a burst: finish → final | 13.187 s | 7.214 s |
| 63.036 s audio uploaded in real time: release → final | — | 0.924 s |
| First partial during the paced minute | — | 1.732 s |
| 5.253 s paced audio after deployment: release → final | — | 0.351 s |

The burst comparison is about **1.8× faster** and leaves more headroom when audio
arrives faster than inference can run. It is not ordinary dictation release
latency. The minute-long paced test returned all twelve complete test sentences;
the burst tests retained all four. This fixture is not a broad accuracy benchmark.

## Android 0.1.5

- Queue `finish` directly behind the final audio frame. WebSocket FIFO preserves
  ordering; waiting for its upload queue to become empty is unnecessary.
- Use five-second WebSocket pings (previously fifteen) to detect a lost connection
  earlier. An upload that stops progressing for fifteen seconds can retry.
- Do not restart an inference that is still producing new partial text just
  because 180 seconds have elapsed since finish. Only new text extends the
  inactivity deadline; repeated identical previews cannot keep a stalled session
  alive indefinitely. The daemon's existing overall time limit still applies.
- Show **Sending…**, **Reconnecting…** and **Transcribing…** during the finish phase.
- Preserve the bounded recording, automatic replay, manual retry and single final
  insertion guarantees introduced in 0.1.4. Version 0.1.6 adds session resumption.

## Android 0.1.6 and resumable daemon sessions

Brief network losses no longer restart decoding from the beginning. The client
uses an authenticated session ID and byte offsets; the daemon returns the amount
of PCM already accepted and continues the same inference worker. Finished results
are cached for 90 seconds so a lost final response can be recovered immediately.
The client acknowledges delivery to remove that cached transcript early.

Unfinished disconnected sessions expire after 30 seconds (two seconds if empty).
Finished sessions can complete offline within the 480-second overall deadline.
Caches are memory-only, capped at eight entries, and erase text on expiry/eviction.
The audio spool is capped by the recording limit (at most 300 seconds / 19.2 MB
of float PCM, plus bounded transport and inference channels). Only one model
owner and one admitted dictation remain possible. Explicit cancellation and
connection replacement are distinguished from temporary disconnection.

A live 10.506-second fixture recovered disconnects during recording and after
finish, returning both complete sentences 0.771 seconds after release. A further
reconnect retrieved the cached result in under 1 ms with no additional inference.
These are local fixture measurements, not phone-network latency.

Daemon restart, expired cache or failed inference still replays retained audio;
resumption cannot fix slow hardware or a backend that stops making progress.


The phone was reachable directly through Tailscale during inspection. A cold
probe took 539 ms; three subsequent direct probes took 44, 289 and 124 ms. These probes are not an Android
end-to-end latency test. Changing the inference profile cannot eliminate radio,
network or application/editor delays.

## Diagnostics

The daemon emits metadata-only completion records:

- `Mobile transport completed`: received audio duration, session duration and
  time from receiving the finish control to the final/error event.
- `Mobile resumable session completed`: accepted audio duration, session duration
  and time after receiving finish for clients using resumption.
- `Mobile session attached`: whether this is a reconnect, accepted PCM bytes and
  whether finish was accepted; no session IDs are logged.
- `Mobile streaming worker completed`: audio duration, session duration, time
  after the worker receives finish, and time waiting for inference queue capacity.

Neither record contains audio, transcript text or pairing tokens. Compare these
with the phone's visible phase to distinguish upload delays from decoder backlog
and final processing. Transport timing includes queue drain and post-processing;
worker timing excludes daemon post-processing. Disconnects/cancellations can lack
transport completion records; the joined worker still records its outcome.
