# Mobile connection and recovery

The canonical, independently maintained wire protocol is in the daemon submodule:
[Remote dictation API](../daemon/docs/remote-api.md).

The Android endpoint is `wss://HOST/voxtype/v1/dictate`. Tailscale Serve uses the
existing HTTPS port 443, strips `/voxtype`, and forwards to the sole daemon at
`127.0.0.1:8765`. The pairing token is sent as an Authorization header.

Android retains PCM in memory until one final result is delivered or the user
cancels. The hard cap is five minutes (9.6 MB of PCM). Temporary connection,
admission, and transcription failures trigger up to eight automatic retries,
backing off from 0.5 to 8 seconds. Each connection replays the entire recording
through the existing model. Upload is paced and bounded; backpressure delays it
without throwing away audio. Speech captured while reconnecting is retained too.

After the retry budget is exhausted, capture stops and the control offers
**Tap to retry**. The recording remains in memory; a manual retry reads the
current saved address/token. X, locking, entering a password field, service
shutdown, or process termination discards it. Nothing is written to disk.
Authentication/protocol errors require manual recovery and do not loop.

Only the current connection can deliver a final transcript, and only once per
user dictation. Late callbacks and previews cannot insert text. There is no
cross-process durability guarantee; Android killing the app loses retained audio.
Permanent microphone, network, model, or device failures cannot be made infallible.
