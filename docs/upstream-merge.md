# Stable upstream integration — 2026-10-09

## Release and result

The `mobile-api` fork now includes upstream's latest stable release, [v1.1.0](https://github.com/peteonrails/voxtype/releases/tag/v1.1.0).
The release tag points to `e2638ca63f566f1682bcedc0abea8e4b25211c21`.
The fork integration commit is `0073b494bdc6e42ec9d98583df78784d6d7a7e2c`.
The newer `v1.1.1-rc*` tags were excluded.
The merge was prepared on `merge/upstream-v1.1.0` from fork commit `c9b7bad67ed0bbe36087dd1b07a346af26c22abb`, then integrated into `mobile-api` after validation.
The original common release base was `v1.0.1`, `dda37ca72b71294d08b0c5bb49c5b24ca590d847`.

This was moderate integration work: seven conflict blocks in two files, plus call-site and ownership changes in code that merged automatically.
The merge and parent pin update are local until published.
No service was restarted and no real model was loaded for validation.

## Conflict resolutions

| File | Blocks | Resolution |
| --- | ---: | --- |
| `src/audio/levels.rs` | 1 | Retain upstream silence tracking and the fork's bounded, non-dropping streaming sends. |
| `src/daemon.rs` | 6 | Combine capture startup, audio forwarding, explicit/silence stop handling, output behavior and both sets of regression tests. |

Unified desktop Stop still drains captured audio and preserves the pending final transcript.
The shared stop helper now applies this behavior to upstream silence auto-stop as well as explicit stop.
It disarms silence tracking and runs the external-trigger stop hook once for the desktop session.

Unified previews remain separate from Linux typing, and its final passes through the normal desktop output pipeline once its worker joins.
CLI output overrides remain available until that final commit.
Upstream's ordinary streaming file-output accumulation and whole-text processing are retained.

Upstream moved the preloaded transcriber into a daemon field and added panic recovery.
Remote admission now borrows that field or an already-cached primary Whisper model without invoking model construction or desktop preparation.
If desktop panic recovery invalidates cached models, replacement waits for all active workers and other model borrowers to drain.
This prevents a replacement model from overlapping an existing phone worker's model.
For a mobile server, the daemon restores the resident primary model before admitting more input, including after a Whisper cache panic.

## Validation

The following checks passed with the staged toolchains:

- `cargo fmt --manifest-path daemon/Cargo.toml --all -- --check`.
- The full locked Rust suite with `--features parakeet`: 1,223 tests passed and two model-download tests remained ignored.
- Focused `remote::`, `parakeet_streaming`, `unified_desktop`, audio-level and model-manager tests.
- Added regressions for the shared stop helper and external hook, Unified CLI file overrides, resident-only model admission, and recovery while a phone/model borrower or Whisper cache borrower is active.
- `scripts/build-daemon`, producing the optimized local daemon artifact.
- `scripts/build-android`, including the debug APK, unit tests and lint.

Strict Clippy with `-D warnings` reports five existing lint findings in unchanged code paths: `needless_return`, `manual_is_multiple_of`, `result_large_err`, `large_enum_variant` and `manual_checked_ops`.
The same all-targets Clippy check passes with only those five lint categories allowed.
These findings remain a limitation of the strict upstream review gate on this host's Rust 1.98 toolchain.

The Rust suite used temporary runtime/config/data directories, and mobile/streaming regressions used fake transcribers.
The release binary was built without launching it alongside the resident daemon.
Real phone and desktop behavior still requires [device validation](device-validation.md) before deployment.

## Future updates and publication

Upstream has a stable `main` branch and a development `dev` branch, with no `master` branch.
For stable updates, fetch tags and select the latest published release that is not a prerelease.
Prepare the merge on a candidate branch or worktree, retain the shared-model and output-isolation regressions, then integrate it into `mobile-api` after validation.

This merge began with:

```bash
git -C daemon fetch upstream --tags
git -C daemon switch -c merge/upstream-v1.1.0 mobile-api
git -C daemon merge --no-commit --no-ff v1.1.0
```

Publish `mobile-api` to the fork before publishing a parent commit that pins its new daemon commit.
Build helpers do not fetch, switch branches or deploy the resulting binaries.
Feature contributions to upstream should target `dev`, as explained in [the contribution workflow](daemon-upstream.md).
