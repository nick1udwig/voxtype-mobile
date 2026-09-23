# Voxtype Mobile

This repo contains the Android app and the Voxtype daemon Git submodule. See
`docs/daemon-upstream.md` for its upstream base and contribution workflow.
Commit daemon changes inside `daemon/`, then update the parent submodule pin.
Never copy daemon sources into the parent repository or leave its pin pointing
at an unpublished commit when publishing the parent repository.

The existing running daemon MUST remain the sole model owner. Remote input must
use the transcriber already held by the daemon. Never add an inference sidecar,
invoke `voxtype transcribe`, or load a second model for previews or tests.
Do not launch the built daemon alongside an installed running daemon.

Phone sessions must not invoke Linux typing, clipboard, media controls, output
hooks, or desktop dictation context. Cancellation cannot detach a blocking
inference and free admission before it completes. Audio queues must stay bounded.

Use `scripts/build-android` for APK, unit tests and lint. Use
`cargo test --manifest-path daemon/Cargo.toml --features parakeet --lib remote::`
and `scripts/build-daemon` for the fork. Build helpers discover `.toolchains/`.
Real phone behavior requires the checks in `docs/device-validation.md`.
