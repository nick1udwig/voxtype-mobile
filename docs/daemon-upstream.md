# Independent Voxtype fork

`daemon/` is a Git submodule: it has its own commits and full upstream ancestry.
The parent repository owns the Android app, private-network setup and deployment
integration. The fork owns the Rust code, daemon tests and wire protocol in
[`daemon/docs/remote-api.md`](../daemon/docs/remote-api.md).

## Remote publication status

The local submodule and independent branch are ready. Its proposed origin is
`https://github.com/nick1udwig/voxtype.git`; publishing that fork awaits approval.
Until published, this workspace builds normally, but a fresh remote clone cannot
fetch the pin. Do not publish the parent with an unavailable submodule commit.

## Upstream base and reviewable commits

- Upstream: https://github.com/peteonrails/voxtype
- Base: `v1.0.1`, `dda37ca72b71294d08b0c5bb49c5b24ca590d847`
- Fork branch: `mobile-api`
- `2446355`: Unified timing defaults, delta accumulation, stream lifecycle and
  desktop final insertion. This can be proposed independently of mobile support.
- `f9242ba`: authenticated remote audio/result interface sharing the daemon model.
- `b472e5f`: bounded backpressure instead of queue-overflow failure, with burst
  and cancellation regression tests plus standalone protocol/agent documentation.

The full parentage is preserved; this is not an unrelated initial import or a
squash of upstream. No model files, compiled binaries, pairing tokens or Android
sources are included in the fork. The upstream MIT license remains intact.

- `b5eb60f`: metadata-only transport/worker latency records and prompt forwarding
  of small TCP frames.
- `5e8311c`: optional resumable remote sessions, bounded PCM spool, expiring
  unacknowledged result cache and reconnect/cancellation regression tests.
- `b407839`: preserve desktop Unified results on stop/release, use the normal
  desktop output pipeline, and publish Omarchy-compatible recording states.
- `59a5be2`: negotiate independent lossless PCM frames, retain decoded-byte
  resume checkpoints, and reject malformed or oversized compressed audio.
  Validated with fake-model protocol tests and sequential live trials against
  the sole daemon; see [latency measurements](latency-investigation.md).
  This commit is local and must be published before publishing the parent pin.

- `4b2cc65`: require Opus-only mobile protocol 2 and complete-frame resumption;
  each logical session owns its libopus decoder, and stale/duplicate frames are
  checked before native decoding. Raw PCM/zlib wire modes and legacy sessions
  are removed. Includes strict framing/duration bounds and a real codec golden
  fixture shared with Android tests. System libopus is a runtime dependency.
  This commit is local and must be published before publishing the parent pin.

- `c9b7bad`: admit two Unified streams through the daemon's existing shared
  model (two phones, or desktop plus phone), retaining exclusive batch admission.
  Keep result destinations, resume IDs and cancellation independent; join all
  workers on shutdown. Desktop Stop drains queued audio and the resampler tail;
  callback overflow/device failure prevents truncated successful output.
  Validated with 72 focused fake-model tests and the Android build/unit/lint
  helper. Strict Clippy has five existing warnings; checks pass with those lint
  categories excluded. Physical microphone/phone validation remains separate.
  This commit is local and must be published before publishing the parent pin.

## Normal development

```bash
git submodule update --init --recursive
git -C daemon switch mobile-api
# Edit daemon sources, then run its tests.
git -C daemon add src docs
git -C daemon commit -m 'Describe the daemon change'
git -C daemon push origin mobile-api
# Only after the commit is available at the submodule URL:
git add daemon
git commit -m 'Update the pinned Voxtype daemon'
```

Submodule initialization normally checks out a detached commit. Create/switch to
a branch before making new commits. On a fresh clone, `git switch --track
origin/mobile-api` creates the local branch. Adding `upstream` is a per-clone step:

```bash
git -C daemon remote add upstream https://github.com/peteonrails/voxtype.git
git -C daemon fetch upstream
```

Use `git submodule update --init --recursive` to reproduce the parent pin, not
`git submodule update --remote`, which would select a moving branch tip. The
build script checks for an initialized checkout and does not modify Git state.

## Contributing upstream

Upstream's CONTRIBUTING.md targets `dev`, which has advanced beyond this pinned
release. Keep the deployed integration branch stable. Create a separate branch
or worktree based on current `upstream/dev`, then cherry-pick the relevant
commits, resolve conflicts and run upstream's fmt/clippy/test gates. Streaming
fixes and the optional remote API can be reviewed as separate contributions.
Do not claim compatibility with current upstream dev until that rebase is tested.

```bash
git -C daemon fetch upstream
git -C daemon worktree add .worktrees/upstream-review -b fix/unified-streaming upstream/dev
git -C daemon/.worktrees/upstream-review cherry-pick 2446355
```

The existing integration was verified with focused fake-model tests; a full
upstream CI run and maintainer review remain separate steps. No PR is submitted
by this restructuring. If the changes are accepted, switch the submodule URL
and pin to an upstream commit containing the same functionality, rerun integration
checks, and commit the `.gitmodules`/gitlink update here.
