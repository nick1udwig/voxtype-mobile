# Daemon fork and upstream contributions

`daemon/` is a Git submodule with its own commits and full Voxtype ancestry.
The parent repository owns the Android app, private-network setup and deployment integration.
The fork owns the Rust code, tests and canonical [remote API](../daemon/docs/remote-api.md).

## Repositories and base

- Parent: https://github.com/nick1udwig/voxtype-mobile
- Fork: https://github.com/nick1udwig/voxtype, branch `mobile-api`
- Upstream: https://github.com/peteonrails/voxtype
- Original base: `v1.0.1`, `dda37ca72b71294d08b0c5bb49c5b24ca590d847`
- Latest merged stable release: `v1.1.0`, `e2638ca63f566f1682bcedc0abea8e4b25211c21`
- Current fork pin: `0073b494bdc6e42ec9d98583df78784d6d7a7e2c`

The fork and parent repositories are published.
Before the 2026-10-09 stable-release merge, remote `mobile-api` contained `c9b7bad67ed0bbe36087dd1b07a346af26c22abb`.
The v1.1.0 merge and updated parent pin are local changes until published.
Every future parent release must likewise point to a commit available at the submodule URL.
Publish daemon changes before publishing a new parent pin.

The fork preserves upstream's MIT license and attribution.
Model files, compiled binaries, pairing tokens and Android sources do not belong in it.

## Change history

These commits describe the evolution of the fork, not independent patches that can all be cherry-picked without their dependencies:

| Commit | Change |
| --- | --- |
| `2446355` | Unified timing defaults, delta accumulation, stream lifecycle and desktop final insertion. |
| `f9242ba` | Authenticated remote audio/result interface sharing the resident model. |
| `b472e5f` | Bounded backpressure and cancellation/burst regressions. |
| `b5eb60f` | Metadata-only latency records and prompt forwarding of small frames. |
| `5e8311c` | Resumable sessions and expiring unacknowledged-result cache. |
| `b407839` | Desktop Unified stop output and Omarchy-compatible recording state. |
| `dd27f15` | Skip final batch inference after remote cancellation. |
| `35af85a` | Join desktop streaming workers before remote admission. |
| `68fe995` | Reject Whisper CLI mode for mobile input. |
| `92bceeb` | Capability discovery without model admission. |
| `59a5be2` | Historical lossless PCM transport; subsequently replaced by protocol 2. |
| `4b2cc65` | Opus-only protocol 2, frame resumption, strict decoding bounds and codec fixture. |
| `c9b7bad` | Two isolated Unified streams through the shared model; desktop capture draining/error handling. |
| `0073b49` | Merge stable v1.1.0; preserve mobile ownership, audio draining, CLI overrides and safe model recovery. |

The current protocol is Opus-only.
Earlier PCM transport descriptions in dated benchmark and validation records are historical.
See [protocol.md](protocol.md) for current client behavior and [validation.md](validation.md) for recorded checks and their limits.

## Normal development

Submodule initialization normally leaves a detached checkout.
Before editing, create or switch to the integration branch.
On a new clone, create it with `git -C daemon switch --track origin/mobile-api`.

```bash
git submodule update --init --recursive
git -C daemon switch mobile-api
# Edit daemon sources and run the focused checks in development.md.
git -C daemon add src docs
git -C daemon commit -m 'Describe the daemon change'
git -C daemon push origin mobile-api
# Once the commit is available at the submodule URL:
git add daemon
git commit -m 'Update the pinned Voxtype daemon'
```

Use `git submodule update --init --recursive` to reproduce a parent pin.
`git submodule update --remote` instead selects a moving branch tip.
Build helpers do not fetch or change Git state.

Adding the upstream remote is a per-clone step, if it is not already configured:

```bash
git -C daemon remote add upstream https://github.com/peteonrails/voxtype.git
git -C daemon fetch upstream
```

## Updating the fork versus contributing upstream

For bringing stable upstream changes into the fork, see the [v1.1.0 integration record](upstream-merge.md).
Upstream's stable branch is `main`; its default development branch is `dev`.
There is no upstream `master`.
Merge the latest stable release tag rather than a release candidate or the moving development branch.
Keep a candidate merge in a separate branch/worktree until it has been validated.

For upstream contributions, use a separate branch based on current `upstream/dev`.
Propose applicable streaming/desktop fixes separately from the optional remote API.
Port the required changes, resolve integration differences, and run upstream's formatting, Clippy and test gates.
Feature discussion should precede a large API proposal; follow [upstream CONTRIBUTING.md](https://github.com/peteonrails/voxtype/blob/dev/CONTRIBUTING.md).
Do not claim current-upstream compatibility from tests against the original base.

For example, begin a review branch without disturbing the deployed integration:

```bash
git -C daemon fetch upstream
git -C daemon worktree add .worktrees/upstream-review -b fix/unified-streaming upstream/dev
# Port the applicable fix, retaining current upstream behavior and tests.
```

If upstream accepts equivalent functionality, change the submodule URL and pin to an upstream commit, rerun app/server integration checks, and commit the `.gitmodules` and gitlink changes in the parent.
The Android app and deployment integration can continue as an independent companion project.
