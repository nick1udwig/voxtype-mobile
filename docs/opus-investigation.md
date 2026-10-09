# Opus transport experiment — 2026-10-03

This is a historical protocol 1 experiment.
Production Android 0.1.9 now uses Opus-only protocol 2; use `scripts/benchmark-latency.py` for current direct protocol timing.
Historical adapter/raw/zlib trials require the old daemon API.

The earlier Opus result was only an offline file-size check.
This experiment compares live encoding and transcription with four transports: raw PCM, the accepted PCM+zlib format, Opus, and Opus followed by zlib compression.

## Method

This is a benchmark implementation, not a production Android Opus integration.
A bounded localhost codec adapter receives encoded bytes **after** the simulated network, decodes them, and streams PCM through the existing daemon's remote API.
All four modes use the same adapter and the same daemon-owned transcriber.
The adapter has no model, performs no inference and invokes no desktop output.
The running daemon is neither replaced nor supplemented by another model owner.
The adapter uses legacy/non-resumable backend admission, so an attempt cannot abandon a disconnected phone session in the daemon's resume registry.
Only an explicit `busy` response before admission is retried, with bounded backoff.

The userspace bottleneck is the same calibrated packetized shaper used in the [lossless experiment](latency-investigation.md).
It includes WebSocket bytes and adds 50 ms propagation in each direction.
It excludes TLS/IP overhead, packet loss, radio behavior and Android execution costs.
The codec-adapter topology differs from the earlier direct protocol test, so fresh raw and PCM+zlib controls are required; historical medians are context rather than paired controls.

The host library is libopus 1.6.1.
Opus uses 16 kHz mono input, a 24 kbit/s target, the VOIP application, variable bitrate, complexity 10, and no DTX or forward-error correction.
Twenty-millisecond packets are encoded as capture makes them available and grouped into 100 ms transport frames.
The encoder's queried lookahead is included in flushing and pre-skip handling, and decoded output is trimmed to the original sample count.
Encoding is not performed offline before starting the latency clock.

For Opus+zlib, each packet aggregate is forcibly zlib-compressed at level 1, including frames where that expands the payload.
This directly tests the requested combination.
It is distinct from silently choosing an uncompressed fallback.
The lossless PCM baseline retains its production raw fallback.

Latency trials use the same 10.506-second synthetic fixture as the previous experiment, at 512, 192 and 128 kbit/s.
All four modes run three times per rate, with order rotated or reversed.
Release-to-final starts at the end of capture, so it includes codec flushing, queued upload and unfinished inference.

## Latency and byte results

All 36 fresh trials completed without busy retries.
Each cell below is the median release-to-final time in seconds, followed by the three-trial range.
An interrupted initial run is excluded: these results all use the same legacy backend admission and adapter topology.

| Uplink and downlink | Raw PCM | PCM+zlib | Opus | Opus+zlib |
| --- | --- | --- | --- | --- |
| 512 kbit/s | 0.926 (0.862–0.952) | 0.854 (0.838–0.881) | 0.882 (0.842–0.885) | 0.852 (0.843–0.855) |
| 192 kbit/s | 4.519 (4.515–4.532) | 1.022 (1.005–1.056) | 0.870 (0.856–0.885) | 0.897 (0.868–0.910) |
| 128 kbit/s | 11.576 (11.549–11.605) | 5.877 (5.817–5.900) | 0.875 (0.866–0.909) | 0.861 (0.852–0.870) |

| Mode | Audio body bytes | Audio plus finish WebSocket bytes |
| --- | ---: | ---: |
| Raw PCM | 336,192 | 337,911 |
| PCM+zlib | 242,692 | 244,391 |
| Opus | 28,827 | 30,514 |
| Opus+zlib | 30,067 | 31,758 |

For this fixture, Opus reduced audio bodies by **88.1% relative to PCM+zlib** and cut the 128 kbit/s median by **5.00 seconds**.
The upload-drain measurement explains the improvement: PCM+zlib still had 4.919 seconds of upload remaining after release; Opus had 0.054 seconds, approximately the configured one-way propagation.
Opus therefore removed the bandwidth backlog in this experiment.
The roughly 0.88-second final latency still includes unfinished recognition; it is not all network delay.

Opus+zlib increased audio bodies by **4.3%** over Opus.
It expanded 97 of 106 frames and showed no consistent latency advantage across rates.
Even choosing the smaller raw/compressed body per aggregate would require 29,068 bytes with this five-byte envelope, 0.84% more than standalone Opus.
These results reject adding independent zlib compression to these Opus aggregates; they do not test a persistent compressor across the entire stream.

Across rate medians, host Opus encoding used 111–130 ms of thread CPU for the 10.506-second recording, versus 8–9 ms for PCM+zlib; Opus decoding used 26–29 ms.
Encoding occurred throughout capture.
The median per-trial maximum native packet encode time was 0.49–0.58 ms.
These are host measurements, not Android power or encoder performance measurements.
At 512 kbit/s the latency ranges overlap; there is no demonstrated Opus latency improvement over PCM+zlib there.

All latency trials preserved the original decoded sample count.
PCM+zlib restored the exact source PCM, and every combined-codec result restored the same decoded PCM as standalone Opus.
All 36 transcripts had the same normalized words as the raw control; Opus changed punctuation on this synthetic fixture.
Broader quality checks are described below.

## Human-speech sanity set

Quality trials use the same adapter at 10 Mbit/s with 50 ms propagation each way, so sustained bandwidth does not dominate the comparison.
Each fixture is tested once with each of the four modes.
The synthetic set includes the clean, quiet and padded speech fixtures; a mixed speech fixture; deterministic noise added to the long fixture (gain 0.65, uniform integer noise −1200…1200, seed 1742); and three-second silence and noise-only clips.

Eight utterances come from [hf-internal-testing/librispeech_asr_dummy](https://huggingface.co/datasets/hf-internal-testing/librispeech_asr_dummy), revision `5be91486e11a2d616f4ec5db8d3fd248585ac07a`.
The Parquet SHA-256 is `4e69a06fa5edc90921e5e7e39a7084881f8b3ed9c805c574f4f39c6fde27c603`.
The fixed IDs are `1272-128104-0000`, `0001`, `0002`, `0003`, `0005`, `0006`, `0007`, and `0008` (all with the same prefix).
Selection was fixed before any recognition results: eight of the first ten sorted IDs, excluding two longer recordings to bound the experiment duration.

These are 62.065 seconds of speech from **one narrator**, with 143 whitespace reference words.
They do not establish accuracy across speakers, accents, microphones or background noise.
Public reference transcripts are kept with the downloaded dataset; generated recognition text is compared only in memory.
Diagnostics contain counts and timing, not generated transcripts or their hashes.
The comparison normalizes Unicode with NFKC and casefold, then uses Unicode alphanumeric tokens, excluding underscores and splitting punctuation/apostrophes.
That yields 145 reference tokens.
Token error rates are Levenshtein edit counts divided by reference-token count; no synonym, abbreviation or number expansion is applied after seeing recognition results.

All **64 quality trials** completed without busy retries.
All four modes had **0 edits against 145 reference tokens** across the eight human recordings.
The Opus modes differed from raw punctuation on one recording, while keeping the normalized words identical.
This small, single-narrator check found no recognition regression; it does not establish general accuracy equivalence.

The clean, quiet, padded and mixed synthetic speech also matched raw normalized words.
On the synthetic noisy long clip, both Opus modes differed from raw by one of 14 tokens.
That comparison has no reference score, so it establishes a word change, not whether recognition improved or worsened.
Silence and noise-only inputs produced empty finals in all eight no-speech trials.

All quality trials preserved the original sample count.
Every raw/PCM+zlib trial restored exact source PCM and identical transcripts.
All 16 Opus+zlib quality trials restored exactly the same decoded PCM and transcripts as Opus.
No 32 kbit/s follow-up was run; the recorded-speech results did not justify changing the candidate after inspecting the results.

Download and preparation used the hf-cli skill, with the public dataset pinned to the revision above.
Audio was extracted from Parquet with DuckDB and converted by FFmpeg to 16 kHz mono signed 16-bit WAV.
Local data and the manifest are in ignored `.toolchains/opus-corpus/`; no corpus audio is committed to this repository.

To reconstruct the data without loading a model:

```sh
hf download hf-internal-testing/librispeech_asr_dummy --type dataset \
  --revision 5be91486e11a2d616f4ec5db8d3fd248585ac07a \
  --include '*.parquet' --include README.md --local-dir .toolchains/opus-corpus
python3 -m venv .toolchains/opus-data-env
.toolchains/opus-data-env/bin/pip install duckdb==1.5.6
.toolchains/opus-data-env/bin/python - <<'PY'
import duckdb, hashlib, json, pathlib, subprocess, wave
root = pathlib.Path('.toolchains/opus-corpus').resolve()
parquet = root / 'clean/validation-00000-of-00001.parquet'
checksum = hashlib.file_digest(parquet.open('rb'), 'sha256').hexdigest()
assert checksum == '4e69a06fa5edc90921e5e7e39a7084881f8b3ed9c805c574f4f39c6fde27c603'
rows = duckdb.connect().execute(
    'SELECT id,audio,text,speaker_id FROM read_parquet(?) ORDER BY id LIMIT 10',
    [str(parquet)]).fetchall()
(root / 'wav').mkdir(exist_ok=True)
fixtures = []
for index in (0, 1, 2, 3, 5, 6, 7, 8):
    ident, audio, reference, speaker = rows[index]
    path = root / 'wav' / f'{ident}.wav'
    subprocess.run(['ffmpeg', '-v', 'error', '-i', 'pipe:0', '-ar', '16000',
                    '-ac', '1', '-c:a', 'pcm_s16le', '-y', str(path)],
                   input=audio['bytes'], check=True)
    with wave.open(str(path)) as wav:
        seconds = wav.getnframes() / wav.getframerate()
    fixtures.append(dict(id=ident, path=str(path), reference=reference,
                         speaker_id=speaker, seconds=seconds))
(root / 'manifest.json').write_text(json.dumps(dict(fixtures=fixtures), indent=2))
PY
```

## Reproducing the measurements

With libopus installed and the existing remote daemon available, run:

```sh
python3 scripts/test-opus-codec.py
python3 scripts/test-opus-adapter.py
python3 scripts/benchmark-opus.py --runs 3 --kbps 512 192 128 \
  --one-way-ms 50 --output artifacts/latency/opus-comparison.json
python3 scripts/benchmark-opus.py --quality-only --quality \
  --quality-manifest .toolchains/opus-corpus/manifest.json \
  --output artifacts/latency/opus-quality.json
```

The default token path is `~/.local/share/voxtype-mobile/token`.
The benchmark uses the existing daemon on localhost port 8765.
It opens ephemeral loopback ports for the adapter and shaper and changes no system network configuration.
The codec checks need no daemon; the adapter checks replace the backend with a fake and exercise only localhost sockets.
Results are local ignored artifacts.
The retained `opus-comparison.json` contains the 36 fresh latency rows; `opus-quality.json` contains the 64 quality rows.
Nine codec checks and two fake adapter checks passed, including encoder-delay trimming, nonzero audio at both ends, sub-100 ms recordings, strict payload bounds and prompt failure delivery through the shaper.
Neither Android nor daemon production sources changed.

## Decision

Retain the reproducible benchmark and results.
**Standalone Opus merits a production implementation experiment**: it materially improved the weakest link without a human-reference regression in this small check.
**Reject the additional zlib stage**: it increased bytes and showed no consistent latency benefit.
Production has since moved to Opus-only protocol 2, with mandatory version/session headers, a session-owned decoder, complete-frame checkpoints and full encoded replay.
See [protocol](protocol.md).
The old experiment remains reproducible against protocol 1 for historical comparison, without being a supported client path.
Android MediaCodec and real-phone checks remain unvalidated in this workspace; the host benchmark used different codec application/VBR settings.
