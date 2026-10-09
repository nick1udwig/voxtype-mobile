# Upstream merge assessment — 2026-10-09

## Result

Merging upstream into `mobile-api` is **moderate integration work**.
The textual conflicts are concentrated, but they affect model ownership, audio draining and final-result delivery.
Publishing the current fork does not need to wait for it.

Upstream has `main` and `dev`, with no `master` branch.
This assessment treats “upstream master” as the stable `main` branch.
After fetching upstream:

- Fork: `c9b7bad67ed0bbe36087dd1b07a346af26c22abb` (`mobile-api`).
- Upstream stable: `b7792b0ecabb778b047b0e98703a7070803f0e8d` (`main`, Voxtype 1.1.0, release merge dated 2026-09-23).
- Common release base: `dda37ca72b71294d08b0c5bb49c5b24ca590d847` (1.0.1).
- Unique commits: 13 on the fork, 103 on upstream `main`.
- Upstream development tip observed: `6d2fc6ddbf791ac00d6a54dc002664010c16b100`.

A real `git merge --no-commit --no-ff` in a separate disposable clone produced **seven conflict blocks in two files**.
The integration branch, parent pin and running service were not changed.
No model or daemon was started.
The trial was not resolved or built, so this is not a validated merged release.

## What conflicts

| File | Blocks | Work needed |
| --- | ---: | --- |
| `src/audio/levels.rs` | 1 | Combine the documentation for upstream silence tracking with the fork's bounded, non-dropping streaming sends. The implementation merged automatically. |
| `src/daemon.rs` | 6 | Combine capture startup, audio forwarding, stop handling, partial/final output, and the two sets of regression tests. |

The daemon conflicts need these decisions:

1. **Capture startup:** upstream adds `track_silence`; retain that argument and the fork's capture-error reset.
2. **Audio forwarding:** preserve upstream silence observations while retaining bounded `send(...).await` in the forwarding task.
   Choosing upstream's `try_send` branch would silently drop audio when the backend queue fills.
3. **Stop handling:** upstream shares `stop_active_recording` between explicit stop and silence auto-stop.
   That helper retains legacy disown-on-stop behavior for ordinary streaming.
   Port the fork's Unified drain/final-preservation behavior into the shared helper so both stop paths preserve the final.
4. **Results and file output:** upstream adds silent accumulation for file-output sessions and whole-text processing.
   Preserve those improvements for relevant backends while keeping Parakeet's cumulative previews out of incremental typing and committing its final once through the normal desktop pipeline.
5. **Tests:** retain both the fork's isolated desktop/concurrency regressions and upstream's new tests.
   The large test-block conflict is not a reason to discard either set.

## Cleanly merged code still needs review

Upstream moves `transcriber_preloaded` from a local variable to a `Daemon` field so panic recovery can clear it.
Some remote admission/startup code merges textually but still refers to the removed local variable and old method signatures.
Update it to the new ownership arrangement and audit the lifetime of the remote shared handle.
All clients must continue to use the existing daemon's model; panic recovery must not retain a stale remote handle or create concurrent model owners.

Upstream's new `State::Streaming.file_output_path`, silence timeout and external trigger stop hook also need integration review.
A small conflict count does not establish correct cancellation, admission, shutdown or output isolation.

## Recommended workflow

Keep the deployed branch stable and work in a separate branch/worktree:

```bash
git -C daemon fetch upstream
git -C daemon worktree add .worktrees/upstream-main -b merge/upstream-main mobile-api
git -C daemon/.worktrees/upstream-main merge --no-commit --no-ff upstream/main
```

Resolve the lifecycle changes deliberately, update cleanly merged call sites, then run formatting, compile/build checks, the focused `remote::`, `parakeet_streaming`, and `unified_desktop` tests, and upstream's applicable Clippy/test gates.
Add regressions for silence auto-stop, file output and model-handle recovery with remote sessions; avoid loading real models in tests.

Build using `scripts/build-daemon` against an isolated recursive parent checkout pinned to the candidate daemon commit.
Do not deploy until the shared-model and real-phone checks in [device-validation.md](device-validation.md) have passed.
If accepted for deployment, publish the fork commit before updating/publishing the parent pin.
Feature contributions to upstream should target `dev`, as explained in [the contribution workflow](daemon-upstream.md).
