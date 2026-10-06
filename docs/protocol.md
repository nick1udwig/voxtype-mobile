# Mobile connection and recovery

The canonical, independently maintained wire protocol is in the daemon submodule:
[Remote dictation API](../daemon/docs/remote-api.md).

The Android endpoint is `wss://HOST/voxtype/v1/dictate`. Tailscale Serve uses the
existing HTTPS port 443, strips `/voxtype`, and forwards to the sole daemon at
`127.0.0.1:8765`. The pairing token is sent as an Authorization header.

Android 0.1.9 requires mobile protocol 2 and `opus_v1`; upgrade the app and
forked daemon together. Older raw PCM/PCM+zlib wire modes and fallback have been
removed. Unsupported clients fail with HTTP 426 before admission, and the new
app rejects old ready events without uploading or automatically retrying.

The microphone remains 16 kHz mono PCM internally, encoded during capture by
Android MediaCodec Opus at target 24 kbit/s. Android retains the original encoded
frames in memory until final delivery or discard, bounded to five minutes and
2 MiB. Complete frame sequences resume from `accepted_frames`; the daemon keeps
one stateful Opus decoder with the logical session across reconnects. Duplicates
are ignored before decoding and the decoder never resets for a retained suffix.
Codec pre-skip and final original sample count preserve the beginning and tail;
A bounded eight-chunk internal PCM handoff lets capture start before encoder
initialization and preserves short recordings released during codec startup.
If the encoder cannot keep up and this bounded handoff fills, recording fails
explicitly so lost microphone samples cannot become a successful transcript.
EOS is fully drained before finish. No PCM/zlib is sent on the mobile wire.
Temporary connection, busy and inference errors retry at most eight times,
backing off from 0.5 to 8 seconds; bounded paced upload retains offline speech.

An unfinished disconnected session stays available for 30 seconds (two seconds
if it has no audio). Once finish is accepted, inference can complete offline within
the 480-second session limit. Completed results are cached in daemon memory for
90 seconds, bounded to eight entries; acknowledgment or eviction erases them.
Reconnect after finish can retrieve the same final without another model pass.
Explicit cancel releases the session after any in-flight inference joins; a
broken network can delay cancellation until expiry. Session IDs are independent;
a new dictation cannot abandon another disconnected session. Unified admits two
streams total, including desktop; additional sessions return busy. Batch models
retain exclusive admission.

Daemon restart, cache expiry, or an inference error requires full replay through
the existing model. Replay restarts from encoded frame 0 with its original codec pre-skip.
Old daemons require a coordinated upgrade; no compatibility fallback is used.
No model is loaded by the transport and no transcript is stored on disk.

After the retry budget is exhausted, capture stops and the control offers
**Tap to retry**. The recording remains in memory; a manual retry reads the
current saved address/token. X, entering an app password field, service
shutdown, or process termination discards it. Nothing is written to disk.
Authentication/protocol errors require manual recovery and do not loop.

Only the current connection can deliver a final transcript, and only once per
user dictation. Late callbacks and previews cannot insert text. There is no
cross-process durability guarantee; Android killing the app loses retained audio.
Permanent microphone, network, model, or device failures cannot be made infallible.

## Screen lock during dictation

Android 0.1.7 keeps the existing microphone foreground service and audio capture
running when the display turns off or the keyguard takes focus. The accessibility
overlay hides while locked and returns after unlock. A system-cancelled touch is
abandoned without cancelling the dictation. Unlock to use the floating check/X
again. The normal five-minute recording limit still applies.

A final transcript received while locked stays only in process memory and is
delivered after unlock, using the current editor or clipboard fallback. A short
notification can say that a result is ready, without including transcript text.
Disabling the accessibility service or terminating the process still discards
recording and any pending result. Password fields in an unlocked app still cancel
recording; the keyguard's password field does not.

Android documents microphone foreground services as the mechanism for continued
background microphone access. The screen-on flag is supported in an Activity,
not this accessibility-service overlay, and would drain display power. See
[foreground microphone restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [screen-on guidance](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).
