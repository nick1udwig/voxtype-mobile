# Unified streaming — 2026-09-22

## Implemented architecture

Android sends 100 ms PCM frames over the existing `/voxtype/v1/dictate`
WebSocket. `remote.rs` discovers the preloaded transcriber's streaming trait and
feeds its bounded input channel. It forwards cumulative previews and returns the
completed transcript after the backend flushes its tail. It never calls batch
inference for a streaming session. The daemon applies final text processing once.

Desktop and phone use the same `ParakeetUnifiedHandle`. In the pinned
`parakeet-rs` 0.3.5 implementation, `from_shared_with_streaming_config` clones an
`Arc<Mutex<ParakeetUnifiedModel>>` holding the ONNX sessions. Each recording gets
new decoder state, not another model load. Global admission still allows only
one desktop or phone session. Disconnect, cancellation and errors join the worker
before that admission is released. Overloaded audio queues cancel instead of
silently losing audio. No remote results invoke desktop output handlers.

The streaming implementation uses bounded context windows and persistent decoder
state. It recomputes overlapping encoder context; describing it as fully
cache-aware encoder inference would be inaccurate.

## Integration fixes

- Default chunk/left/right durations are now **0.56/5.6/0.56 seconds**, matching
  NVIDIA and the pinned library. The old 0.5/1.5/0.5 defaults violated the library's
  80 ms frame alignment. Validation now occurs before any model loading.
- Each library result is newly emitted text. The wrapper accumulates it exactly
  once, preserving repeated words, subwords, whitespace and punctuation. An empty
  flush still returns the complete prior transcript; a failed flush returns an
  error instead of a truncated success.
- Cancellation wakes an idle worker and discards in-flight results after inference
  returns. It does not flush or emit a successful final. Dropping the cancellation
  sender alone is a no-op, consistent with the stream interface.
- Partial events can be coalesced under backpressure. Two reserved event slots
  ensure the final/error and end events cannot deadlock behind unread previews.
- Desktop Parakeet output observes cumulative previews and inserts the final text
  once. It applies text/post-processing to the complete final. Desktop push-to-talk
  remains enabled because previews no longer synthesize keystrokes while held.

## Model and deployment

The selected model is `parakeet-unified-en-0.6b`, an English-only model supporting
both offline and streaming operation. It replaces the previous TDT model in the
running daemon. The old files can remain on disk without loading another model.

The staged [ONNX export](https://huggingface.co/bobNight/parakeet-unified-en-0.6b-onnx)
is pinned at revision `09e9060322d99c5f070010724786e6ee090fd51d`; all five required
files were verified against repository hashes before deployment. The download is
about 2.52 GB. Configuration and the previously installed binary are backed up
before the service stops and the replacement starts.

The batch path is retained for installations that keep TDT. It still performs
cumulative batch previews and a whole-recording final pass; this was the source
of avoidable repeated work in the earlier phone implementation.

## Accuracy and speed

NVIDIA's published English WER numbers (lower is better) are 5.91% for Unified
offline, 6.29% at 1.12 s streaming context and 6.52% at 0.56 s. TDT v3's model card
reports 6.34% offline. These are separate evaluations and do not establish an
exact ranking for our ONNX runtime or this CPU.
Sources: [Unified model card](https://huggingface.co/nvidia/parakeet-unified-en-0.6b),
[TDT v3 model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3).

The 1.12 s value is chunk plus lookahead, not measured processing time or
release-to-result latency. Buffered streaming can use more compute than a single
offline pass because context is recomputed. Its practical benefit is completing
most work while the user speaks, leaving only queued audio and the flush tail
on release. Sustained CPU throughput must still be measured.

## Verification

The 13 Parakeet streaming tests use fake decoders and exercise the real worker.
The 25 tests selected by `remote::tests` cover batch compatibility, streaming
model reuse, no batch fallback, cancellation/joining, audio limits and WebSocket
transport. No test initializes a real recognition model.

See [validation.md](validation.md) for build and live deployment results and
[device-validation.md](device-validation.md) for phone-specific checks. Existing
Android APKs support the streaming protocol; no client update is needed.
