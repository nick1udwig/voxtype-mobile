# Mobile connection and recovery

The canonical, independently maintained wire protocol is in the daemon submodule:
[Remote dictation API](../daemon/docs/remote-api.md).

The Android endpoint is `wss://HOST/voxtype/v1/dictate`. Tailscale Serve uses the
existing HTTPS port 443, strips `/voxtype`, and forwards to the sole daemon at
`127.0.0.1:8765`. The pairing token is sent as an Authorization header.

Android retains PCM in memory until one final result is delivered or the user
cancels. The hard cap is five minutes (9.6 MB of PCM). Temporary connection,
admission, and transcription failures trigger up to eight automatic retries,
backing off from 0.5 to 8 seconds. Version 0.1.6 sends a random session ID and
resumes from the daemon's accepted PCM-byte checkpoint. Already accepted audio
is not uploaded or decoded again. Speech captured while reconnecting is retained
too. Upload is paced and bounded; backpressure delays it without discarding audio.

An unfinished disconnected session stays available for 30 seconds (two seconds
if it has no audio). Once finish is accepted, inference can complete offline within
the 480-second session limit. Completed results are cached in daemon memory for
90 seconds, bounded to eight entries; acknowledgment or eviction erases them.
Reconnect after finish can retrieve the same final without another model pass.
Explicit cancel releases the session after any in-flight inference joins; a
broken network can delay cancellation until expiry. A new dictation abandons a
disconnected unfinished predecessor.

Daemon restart, cache expiry, or an inference error requires full replay through
the existing model. Older daemons negotiate legacy raw PCM and full replay.
No model is loaded by the transport and no transcript is stored on disk.

After the retry budget is exhausted, capture stops and the control offers
**Tap to retry**. The recording remains in memory; a manual retry reads the
current saved address/token. X, locking, entering a password field, service
shutdown, or process termination discards it. Nothing is written to disk.
Authentication/protocol errors require manual recovery and do not loop.

Only the current connection can deliver a final transcript, and only once per
user dictation. Late callbacks and previews cannot insert text. There is no
cross-process durability guarantee; Android killing the app loses retained audio.
Permanent microphone, network, model, or device failures cannot be made infallible.
