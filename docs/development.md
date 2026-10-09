# Development and integrations

## Repository structure

- `android/`: native Java Android app, Gradle wrapper, unit tests and resources.
- `daemon/`: independently maintained Voxtype fork, pinned as a Git submodule.
  Rust code, daemon tests and the canonical protocol belong here.
- `scripts/`: build, install, rollback, Tailscale setup and benchmark helpers.
- `docs/`: setup details, integration contracts and validation records.
- `artifacts/`: local APKs and executables, excluded from Git.

Initialize a checkout with `git submodule update --init --recursive`.
Make daemon changes and commits inside `daemon/`, publish those commits first, then update the parent pin.
See [the fork workflow](daemon-upstream.md).
Do not use `git submodule update --remote` to reproduce a release: it selects a moving tip instead of the parent's exact pin.

## Builds and checks

The prerequisites are listed in the [README](../README.md).
Build helpers discover local tools under `.toolchains/`.
Building does not replace the running daemon.

```bash
scripts/build-daemon
scripts/build-android
```

The default Android build produces a development-signed APK.
For an unsigned release APK, release unit tests and release lint, use:

```bash
scripts/build-android --release
```

See [release signing](releases.md) before distribution.

Focused daemon checks use fake transcribers and load no recognition model:

```bash
cargo test --manifest-path daemon/Cargo.toml --locked --features parakeet --lib remote::
cargo test --manifest-path daemon/Cargo.toml --locked --features parakeet --lib parakeet_streaming
cargo test --manifest-path daemon/Cargo.toml --locked --features parakeet --lib unified_desktop
```

If Rust is staged locally, first select it in the shell:

```bash
export CARGO_HOME="$PWD/.toolchains/cargo"
export RUSTUP_HOME="$PWD/.toolchains/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
```

Real microphone, codec, Android accessibility and network handoff behavior require [device validation](device-validation.md).
Builds and JVM/fake-model tests do not establish those results.
Never start a built daemon or load another model while the installed daemon is running.
Live tests must use the resident daemon's API.

## Integrating another application

The server API is documented in [daemon/docs/remote-api.md](../daemon/docs/remote-api.md).
It provides authenticated capability discovery, Opus protocol 2 dictation, partial/final results and resumable sessions.
A backend integration must encode Opus and retain complete frames for recovery; old PCM/zlib clients are incompatible.
A trusted backend holds the pairing token.
The daemon rejects browser Origin headers, so use a trusted backend proxy for browser applications rather than connecting a page directly.

A downloaded `scripts/install` accepts `VOXTYPE_MOBILE_REPO` pointing to a Git repository and clones it recursively.
It currently clones the default branch; a versioned installer URL alone does not pin the server code.
For reproducible dependencies, check out an exact parent commit or release tag, initialize its submodule pin, and invoke the installer from that checkout.

Termai can use a local checkout (`TERMAI_VOXTYPE_SOURCE`) or a published installer URL (`TERMAI_VOXTYPE_INSTALL_URL`).
Its Install button pastes a command for the user to run.
Termai advertises an available microphone with the accessibility label `Termai dictation microphone`.
The Android service hides its idle overlay only when that actionable control is visible in the focused application window; active recording controls remain available.

## Android input behavior

Android 13+ uses the accessibility input connection alongside the current keyboard.
Older versions use accessibility paste or safe text replacement, falling back to the phone's clipboard.
Input-connection commits have no editor acknowledgment, so an editor silently ignoring a commit cannot be detected.
Only final transcripts are inserted, once; previews are revisable and appear in the activity meter's accessibility description.

Dragging a provisional hold cancels that press's capture.
Dragging during an already-active toggle recording moves the controls without submitting.
Position is remembered and adjusted for rotation and the keyboard.
Screen-lock delivery, password handling, reconnects and cancellation are described in [the protocol guide](protocol.md) and checked in [device validation](device-validation.md).
