#!/usr/bin/env python3
"""Compare raw, PCM+zlib, Opus, and forced Opus+zlib on the existing model.

All modes traverse the same bounded packetized bottleneck and benchmark-only
codec adapter. The adapter decodes *after* shaped delivery, then forwards PCM
to the sole already-running daemon. No inference, model, daemon/service swap,
transcript storage, or global network changes occur here. Host codec CPU and a
loopback gateway are not production Android/server Opus measurements.
"""

import argparse
import base64
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import re
import socket
import struct
import sys
import threading
import time
import unicodedata

sys.dont_write_bytecode = True


def sibling_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


transport = sibling_module("opus_transport", "benchmark-transport.py")
latency = transport._latency
codec = sibling_module("opus_codec", "opus-codec.py")
BenchmarkError = latency.BenchmarkError
MODES = ("raw", "pcm_zlib", "opus", "opus_zlib")


class AdmissionBusy(BenchmarkError):
    """Only an explicit busy before admission is eligible for bounded retry."""


class ServerSocket:
    """One bounded, unfragmented RFC6455 connection used only by this harness."""
    def __init__(self, sock, buffer=b""):
        self.sock, self.buffer = sock, bytearray(buffer)
        self.lock = threading.Lock()

    def read(self, size, deadline):
        while len(self.buffer) < size:
            if time.monotonic() > deadline:
                raise BenchmarkError("adapter client deadline exceeded")
            try:
                data = self.sock.recv(max(4096, size - len(self.buffer)))
            except socket.timeout:
                continue
            if not data:
                raise BenchmarkError("adapter client disconnected")
            self.buffer.extend(data)
        data = bytes(self.buffer[:size])
        del self.buffer[:size]
        return data

    def receive(self, deadline):
        while True:
            first, second = self.read(2, deadline)
            if first & 0x70 or not first & 0x80 or not second & 0x80:
                raise BenchmarkError("invalid benchmark client frame")
            opcode, length = first & 15, second & 127
            if length == 126:
                length = struct.unpack("!H", self.read(2, deadline))[0]
            elif length == 127:
                length = struct.unpack("!Q", self.read(8, deadline))[0]
            if length > 65536:
                raise BenchmarkError("benchmark frame exceeds bound")
            mask = self.read(4, deadline)
            data = self.read(length, deadline)
            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
            if opcode == 9:
                self.send(10, payload)
                continue
            if opcode == 10:
                continue
            if opcode not in (1, 2):
                raise BenchmarkError("unexpected benchmark client opcode")
            return opcode, payload

    def send(self, opcode, payload):
        if len(payload) > 1048576:
            raise BenchmarkError("adapter event exceeds bound")
        header = bytes([0x80 | opcode])
        if len(payload) < 126:
            header += bytes([len(payload)])
        elif len(payload) < 65536:
            header += bytes([126]) + struct.pack("!H", len(payload))
        else:
            header += bytes([127]) + struct.pack("!Q", len(payload))
        with self.lock:
            self.sock.sendall(header + payload)

    def event(self, event):
        self.send(1, json.dumps(event, separators=(",", ":")).encode())


class CodecAdapter:
    """No inference. One frame at a time; backpressure reaches the bottleneck."""
    def __init__(self, token, mode, timeout):
        self.token, self.mode, self.timeout = token, mode, timeout
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        self.stop = threading.Event()
        self.sockets = []
        self.backend = None
        self.reader = None
        self.error = None
        self.front = None
        self.failure_lock = threading.Lock()
        self.failure_sent = False
        self.metrics = {"decoder_cpu_seconds": 0.0, "max_frame_decode_seconds": 0.0,
                        "decoded_pcm_bytes": 0}
        self.pcm_digest = hashlib.sha256()
        self.thread = threading.Thread(target=self.run, name="codec-adapter", daemon=True)
        self.thread.start()

    def run(self):
        decoder = None
        completed = False
        try:
            while not self.stop.is_set():
                try:
                    client, _ = self.listener.accept()
                    break
                except socket.timeout:
                    continue
            else:
                return
            client.settimeout(1)
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.sockets.append(client)
            front = ServerSocket(client)
            self.front = front
            deadline = time.monotonic() + 10
            while b"\r\n\r\n" not in front.buffer:
                if len(front.buffer) > 16384 or time.monotonic() > deadline:
                    raise BenchmarkError("adapter handshake exceeds bound")
                try:
                    data = client.recv(4096)
                except socket.timeout:
                    continue
                if not data:
                    raise BenchmarkError("adapter handshake disconnected")
                front.buffer.extend(data)
            head, remaining = front.buffer.split(b"\r\n\r\n", 1)
            front.buffer = bytearray(remaining)
            lines = head.decode("ascii").split("\r\n")
            headers = {name.lower(): value.strip() for name, value in
                       (line.split(":", 1) for line in lines[1:] if ":" in line)}
            if (lines[0] != "GET /v1/dictate HTTP/1.1" or
                    not hmac.compare_digest(headers.get("authorization", ""),
                                            "Bearer " + self.token)):
                raise BenchmarkError("adapter authentication failed")
            key = headers.get("sec-websocket-key", "")
            if len(base64.b64decode(key, validate=True)) != 16:
                raise BenchmarkError("adapter websocket key invalid")
            # The original daemon sees only its existing raw PCM remote API.
            # Legacy admission cannot replace a disconnected resumable phone
            # session. A fresh resumable ID would abandon such a predecessor.
            self.backend = latency.WebSocket(self.token, False)
            ready = self.backend.receive(time.monotonic() + 10)
            accept = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            client.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept +
                            "\r\n\r\n").encode())
            front.event(ready)
            if ready.get("type") != "ready":
                completed = True  # No admitted model work to cancel.
                return
            if (ready.get("resume") or ready.get("accepted_bytes", 0) != 0 or
                    ready.get("finished") or
                    (ready.get("sample_rate"), ready.get("channels"), ready.get("format")) !=
                    (16000, 1, "pcm_s16le")):
                raise BenchmarkError("adapter backend format incompatible")
            deadline = time.monotonic() + 300 + self.timeout
            if self.mode.startswith("opus"):
                decoder = codec.Decoder()

            def receive_backend():
                try:
                    while not self.stop.is_set():
                        event = self.backend.receive(deadline)
                        if event.get("type") == "final":
                            self.metrics["backend_final_at"] = time.monotonic()
                        front.event(event)
                        if event.get("type") in ("final", "error"):
                            return
                except (OSError, BenchmarkError) as error:
                    if not self.stop.is_set():
                        self.error = error
                        self.fail_frontend()

            self.reader = threading.Thread(target=receive_backend, daemon=True)
            self.reader.start()
            source_offset = backend_offset = 0
            finished = False
            while not self.stop.is_set():
                opcode, payload = front.receive(deadline)
                if opcode == 1:
                    try:
                        kind = json.loads(payload).get("type")
                    except (ValueError, AttributeError):
                        raise BenchmarkError("adapter invalid control") from None
                    if kind == "finish":
                        if finished or (decoder is not None and not decoder.finished):
                            raise BenchmarkError("adapter audio missing final boundary")
                        self.metrics["adapter_finish_at"] = time.monotonic()
                        self.backend.send(1, payload)
                        self.metrics["backend_finish_sent_at"] = time.monotonic()
                        finished = True
                    elif kind == "ack":
                        if not finished or "backend_final_at" not in self.metrics:
                            raise BenchmarkError("adapter premature ack")
                        # The legacy backend has already closed after final.
                        completed = True
                        return
                    elif kind == "cancel":
                        self.backend.send(1, payload)
                        completed = True
                        return
                    else:
                        raise BenchmarkError("adapter unexpected control")
                    continue
                if finished or len(payload) <= 8:
                    raise BenchmarkError("adapter invalid audio")
                offset = struct.unpack("!Q", payload[:8])[0]
                if offset != source_offset:
                    raise BenchmarkError("adapter nonsequential audio")
                body = payload[8:]
                started = time.thread_time()
                if self.mode == "pcm_zlib":
                    pcm = codec.unwrap_zlib(body, 3200)
                    source_offset += len(pcm)
                elif self.mode.startswith("opus"):
                    aggregate = (codec.unwrap_zlib(body, codec.MAX_AGGREGATE_BYTES)
                                 if self.mode == "opus_zlib" else body)
                    packets, _, final, _, total = codec.unpack_aggregate(aggregate)
                    source_offset = total * 2 if final else source_offset + len(packets) * 640
                    pcm = decoder.decode(aggregate)
                else:
                    pcm = body
                    source_offset += len(pcm)
                elapsed = time.thread_time() - started
                self.metrics["decoder_cpu_seconds"] += elapsed
                self.metrics["max_frame_decode_seconds"] = max(
                    self.metrics["max_frame_decode_seconds"], elapsed)
                if len(pcm) > 3200 or len(pcm) % 2 or backend_offset + len(pcm) > 300 * 32000:
                    raise BenchmarkError("adapter PCM exceeds bound")
                self.pcm_digest.update(pcm)
                if pcm:
                    self.backend.send(2, pcm)
                    backend_offset += len(pcm)
                self.metrics["decoded_pcm_bytes"] = backend_offset
        except (OSError, BenchmarkError, codec.CodecError, ValueError) as error:
            if not self.stop.is_set():
                self.error = error
        finally:
            if decoder is not None:
                decoder.close()
            if self.backend is not None:
                if not completed:
                    # The daemon retains admission until its own inference joins.
                    try:
                        self.backend.send(1, b'{"type":"cancel"}')
                    except OSError:
                        pass
                self.backend.close()
            if self.error:
                self.fail_frontend()

    def fail_frontend(self):
        # The byte shaper does not forward EOF; send a bounded safe event before
        # shutdown so decoder/backend errors cannot wait out the final deadline.
        with self.failure_lock:
            if self.front and not self.failure_sent:
                self.failure_sent = True
                try:
                    self.front.event({"type": "error", "code": "benchmark_adapter_failure"})
                except (OSError, BenchmarkError):
                    pass
        for connection in self.sockets:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def close(self):
        self.stop.set()
        self.listener.close()
        for connection in self.sockets:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self.thread.join(timeout=2)
        if self.reader:
            self.reader.join(timeout=2)


def normalized_tokens(text):
    """NFKC, Unicode casefold, then Unicode alphanumeric words (underscore excluded)."""
    return re.findall(r"[^\W_]+", unicodedata.normalize("NFKC", text).casefold())


def edits(reference, hypothesis):
    reference, hypothesis = normalized_tokens(reference), normalized_tokens(hypothesis)
    previous = list(range(len(hypothesis) + 1))
    for row, token in enumerate(reference, 1):
        current = [row]
        for column, other in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[column] + 1,
                               previous[column - 1] + (token != other)))
        previous = current
    count = previous[-1]
    return {"token_edits": count, "reference_tokens": len(reference),
            "normalized_token_edit_rate": count / len(reference) if reference else None,
            "normalized_words_equal": reference == hypothesis}


def streaming_aggregates(audio, lookahead, wait_capture, encode_packet):
    """Encode on the capture clock; retain at most five packets, flush immediately.

    Shared by paced trials and duration tests. Native zero padding consumes CPU
    at release but does not pretend to be additional captured audio.
    """
    total = len(audio) // 2
    if len(audio) % 2 or not 1 <= total <= codec.MAX_SAMPLES:
        raise BenchmarkError("Opus source duration exceeds bounds")
    packets, aggregate_offset, native_samples, first = [], 0, 0, True
    for offset in range(0, len(audio), 640):
        pcm = audio[offset:offset + 640]
        wait_capture((offset + len(pcm)) // 2)
        packets.append(encode_packet(pcm + bytes(640 - len(pcm))))
        native_samples += 320
        if len(packets) == 5 and offset + len(pcm) < len(audio):
            yield packets, aggregate_offset, first, False
            first = False
            # Adapter-side Opus offsets count native packet samples (including
            # padding), while the daemon receives canonical decoded-PCM offsets.
            aggregate_offset = native_samples * 2
            packets = []
    while native_samples < total + lookahead:
        if len(packets) == 5:
            yield packets, aggregate_offset, first, False
            first = False
            aggregate_offset = native_samples * 2
            packets = []
        packets.append(encode_packet(bytes(640)))
        native_samples += 320
    if not packets:
        raise BenchmarkError("Opus stream has no final aggregate")
    yield packets, aggregate_offset, first, True


def trial(token, audio, mode, kbps, delay_ms, timeout, bitrate):
    adapter = CodecAdapter(token, mode, timeout)
    proxy = transport.Bottleneck(kbps, delay_ms, adapter.port)
    ws = reader = encoder = None
    done = threading.Event()
    errors, timeline, transcript = [], {}, []
    complete = False
    result = {"mode": mode, "kbps": kbps, "one_way_ms": delay_ms,
              "pcm_bytes": len(audio), "audio_seconds": len(audio) / 32000,
              "network_frame_ms": 100, "opus_packet_ms": 20, "opus_bitrate": bitrate,
              "shaping": "packetized_userspace_websocket_bytes_then_codec_adapter",
              "packet_bytes_max": 1200, "packet_queue_max": 64,
              "adapter_pending_audio_frames_max": 1,
              "encoder": "host_libopus_ctypes_or_python_zlib_level1_not_phone",
              "libopus_version": codec.VERSION,
              "includes_tls_ip_overhead_or_loss": False,
              "production_opus_implementation": False}
    try:
        opened = time.monotonic()
        ws = latency.WebSocket(token, True, port=proxy.port)
        ready = ws.receive(opened + 10)
        if ready.get("type") != "ready":
            if ready.get("type") == "error" and ready.get("code") == "busy":
                raise AdmissionBusy("daemon busy before benchmark admission")
            raise BenchmarkError("daemon did not admit benchmark (possibly busy)")
        if not proxy.connected.wait(5) or proxy.error:
            raise BenchmarkError("benchmark bottleneck did not connect")
        if mode.startswith("opus"):
            encoder = codec.Encoder(bitrate)
            result["opus_pre_skip_samples"] = encoder.lookahead
        result["admission_seconds"] = time.monotonic() - opened
        start = time.monotonic()
        release = start + len(audio) / 32000
        deadline = release + timeout

        def receive():
            try:
                while not done.is_set():
                    event = ws.receive(deadline)
                    now = time.monotonic()
                    if event.get("type") == "partial":
                        timeline.setdefault("first_partial", now)
                        result["partial_count"] = result.get("partial_count", 0) + 1
                    elif event.get("type") == "final":
                        timeline["final"] = now
                        transcript.append(event.get("text", ""))
                        done.set()
                    else:
                        raise BenchmarkError("unexpected/terminal daemon event")
            except (OSError, BenchmarkError) as error:
                errors.append(error)
                done.set()

        reader = threading.Thread(target=receive, daemon=True)
        reader.start()
        upload_start = ws.sent_bytes
        encoded_bytes = packets_bytes = envelope_bytes = frame_count = 0
        compressed_frames = forced_expanded = adaptive_bytes = 0
        encoder_seconds = max_packet_encode = max_frame_encode = capture_lag = 0.0

        def wait_capture(samples):
            nonlocal capture_lag
            due = start + samples / 16000
            if done.wait(max(0, due - time.monotonic())):
                raise BenchmarkError("benchmark ended before finish")
            capture_lag = max(capture_lag, time.monotonic() - due)

        def send_aggregate(packets, source_offset, first, final):
            nonlocal encoded_bytes, packets_bytes, envelope_bytes, frame_count
            nonlocal encoder_seconds, max_frame_encode, compressed_frames, forced_expanded, adaptive_bytes
            started = time.thread_time()
            payload = codec.pack_aggregate(packets, first, final,
                                           encoder.lookahead if first else 0,
                                           len(audio) // 2 if final else 0)
            packets_bytes += sum(map(len, packets))
            envelope_bytes += len(payload) - sum(map(len, packets))
            if mode == "opus_zlib":
                body = codec.zlib_envelope(payload, force=True)
                compressed_frames += 1
                forced_expanded += int(len(body) >= len(payload))
                adaptive_bytes += min(len(body), len(payload) + 5)
            else:
                body = payload
            elapsed = time.thread_time() - started
            encoder_seconds += elapsed
            max_frame_encode = max(max_frame_encode, elapsed)
            encoded_bytes += len(body)
            frame_count += 1
            ws.send(2, struct.pack("!Q", source_offset) + body)

        if encoder is None:
            for offset in range(0, len(audio), 3200):
                frame = audio[offset:offset + 3200]
                wait_capture((offset + len(frame)) // 2)
                if mode == "pcm_zlib":
                    body, compressed, elapsed = transport.encoded_frame(frame)
                    encoder_seconds += elapsed
                    max_frame_encode = max(max_frame_encode, elapsed)
                    compressed_frames += compressed
                else:
                    body = frame
                encoded_bytes += len(body)
                frame_count += 1
                ws.send(2, struct.pack("!Q", offset) + body)
        else:
            def encode_packet(pcm):
                nonlocal encoder_seconds, max_packet_encode
                started = time.thread_time()
                packet = encoder.encode(pcm)
                elapsed = time.thread_time() - started
                encoder_seconds += elapsed
                max_packet_encode = max(max_packet_encode, elapsed)
                return packet
            for packets, offset, first, final in streaming_aggregates(
                    audio, encoder.lookahead, wait_capture, encode_packet):
                send_aggregate(packets, offset, first, final)
        ws.send(1, b'{"type":"finish"}')
        finish_queued = time.monotonic()
        target = ws.sent_bytes
        delivered = proxy.upstream.delivered_at(target, timeout)
        if not done.wait(max(0, deadline - time.monotonic())) or errors:
            raise BenchmarkError("benchmark final failed/deadline exceeded")
        ws.send(1, b'{"type":"ack"}')
        proxy.upstream.delivered_at(ws.sent_bytes, 5)
        adapter.thread.join(timeout=2)
        if adapter.error:
            raise BenchmarkError("benchmark adapter failed")
        complete = True
        metrics = adapter.metrics
        if metrics["decoded_pcm_bytes"] != len(audio):
            raise BenchmarkError("decoded audio duration changed")
        digest = adapter.pcm_digest.digest()
        result.update(
            audio_body_bytes=encoded_bytes,
            audio_finish_websocket_wire_bytes=target - upload_start,
            opus_packet_bytes=packets_bytes, opus_aggregate_envelope_bytes=envelope_bytes,
            network_audio_frames=frame_count, compressed_frames=compressed_frames,
            forced_zlib_expanded_frames=forced_expanded,
            adaptive_opus_zlib_body_bytes=adaptive_bytes if mode == "opus_zlib" else None,
            encoder_cpu_seconds=encoder_seconds, max_native_packet_encode_seconds=max_packet_encode,
            max_network_frame_encode_seconds=max_frame_encode,
            decoder_cpu_seconds=metrics["decoder_cpu_seconds"],
            max_network_frame_decode_seconds=metrics["max_frame_decode_seconds"],
            first_partial_seconds=timeline.get("first_partial", timeline["final"]) - start,
            release_to_final_seconds=timeline["final"] - release,
            upload_drain_after_release_seconds=delivered - release,
            decoder_inference_tail_seconds=metrics["backend_final_at"] - delivered,
            downlink_and_adapter_result_seconds=timeline["final"] - metrics["backend_final_at"],
            daemon_tail_plus_downlink_seconds=timeline["final"] - delivered,
            adapter_finish_forward_seconds=metrics["backend_finish_sent_at"] - metrics["adapter_finish_at"],
            finish_queued_after_release_seconds=finish_queued - release,
            max_capture_scheduling_lag_seconds=capture_lag,
            proxy_upstream_bytes=proxy.upstream.forwarded,
            proxy_max_queue_packets=proxy.upstream.max_queue_packets,
            decoded_pcm_bytes=metrics["decoded_pcm_bytes"],
            decoded_pcm_exact=digest == hashlib.sha256(audio).digest(),
            final_characters=len(transcript[0]),
        )
        return result, transcript[0], digest
    finally:
        if ws is not None and not complete:
            try:
                ws.send(1, b'{"type":"cancel"}')
                proxy.upstream.delivered_at(ws.sent_bytes, 5)
            except (OSError, BenchmarkError, AttributeError):
                pass
        done.set()
        if encoder:
            encoder.close()
        if ws:
            ws.close()
        proxy.close()
        adapter.close()
        if reader:
            reader.join(timeout=2)


def write_results(path, measurements):
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(measurements, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--token-file", type=Path, default=Path.home() / ".local/share/voxtype-mobile/token")
    parser.add_argument("--fixture", type=Path, default=root / "daemon/tests/fixtures/vad/speech_long.wav")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--kbps", type=int, nargs="+", default=[512, 192, 128])
    parser.add_argument("--one-way-ms", type=float, default=50)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--bitrate", type=int, choices=[24000, 32000], default=24000)
    parser.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES))
    parser.add_argument("--quality", action="store_true")
    parser.add_argument("--quality-manifest", type=Path)
    parser.add_argument("--quality-only", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (1 <= args.runs <= 5 and 1 <= len(args.kbps) <= 5 and
            1 <= len(args.modes) <= 4 and len(set(args.modes)) == len(args.modes) and
            all(64 <= value <= 10000 for value in args.kbps) and
            0 <= args.one_way_ms <= 500 and 1 <= args.timeout <= 180):
        parser.error("benchmark settings exceed bounds")
    token = args.token_file.read_text().strip()
    if not token or any(value in token for value in "\r\n"):
        raise BenchmarkError("invalid benchmark token file")
    measurements, raw_text, opus_digest, opus_text = [], {}, {}, {}

    def perform(audio, fixture_id, category, run, rate, mode, reference=None):
        retries, retry_wait = 0, 0.0
        while True:
            try:
                measurement, text, digest = trial(token, audio, mode, rate, args.one_way_ms,
                                                  args.timeout, args.bitrate)
                break
            except AdmissionBusy:
                if retries >= 7:
                    raise AdmissionBusy("daemon remained busy; benchmark stopped without admission")
                delay = min(8, 2 ** (retries + 1))
                retries += 1
                retry_wait += delay
                print(json.dumps({"event": "admission_busy_retry", "attempt": retries,
                                  "wait_seconds": delay, "mode": mode, "fixture_id": fixture_id,
                                  "kbps": rate}), flush=True)
                time.sleep(delay)
        if mode == "raw":
            raw_text.setdefault(fixture_id, text)
        if mode == "opus":
            opus_digest.setdefault(fixture_id, digest)
            opus_text.setdefault(fixture_id, text)
        measurement.update(fixture_id=fixture_id, category=category, run=run,
                           order=len(measurements) + 1, admission_busy_retries=retries,
                           admission_busy_wait_seconds=retry_wait,
                           backend_admission="legacy_no_session_registry_replacement")
        if fixture_id in raw_text:
            measurement["transcript_matches_raw"] = text == raw_text[fixture_id]
            measurement["against_raw"] = edits(raw_text[fixture_id], text)
        if reference is not None:
            measurement["against_public_reference"] = edits(reference, text)
            if not reference:
                measurement["no_speech_nonempty_final"] = bool(text.strip())
        if mode == "opus_zlib" and fixture_id in opus_digest:
            measurement["decoded_pcm_matches_opus"] = digest == opus_digest[fixture_id]
            measurement["transcript_matches_opus"] = text == opus_text[fixture_id]
        measurements.append(measurement)
        print(json.dumps(measurement, sort_keys=True), flush=True)
        write_results(args.output, measurements)
        time.sleep(0.5)

    if not args.quality_only:
        audio = latency.load_fixture(args.fixture, 2, 0.5)
        for run in range(1, args.runs + 1):
            for rate in args.kbps:
                modes = list(args.modes)
                if run == 2:
                    modes.reverse()
                elif run > 2:
                    modes = modes[2:] + modes[:2]
                for mode in modes:
                    perform(audio, "speech_long_twice_500ms_gaps", "latency", run, rate, mode)
    if args.quality:
        import array
        import random
        fixtures = root / "daemon/tests/fixtures/vad"
        cases = [(name, latency.load_fixture(fixtures / (name + ".wav"), 1, 0.0))
                 for name in ("speech_hello", "speech_long", "speech_quiet", "speech_padded")]
        source = array.array("h", cases[1][1])
        rng = random.Random(1742)
        noisy = array.array("h", (max(-32768, min(32767, int(sample * 0.65) + rng.randint(-1200, 1200)))
                                  for sample in source)).tobytes()
        cases += [("speech_long_deterministic_noise", noisy),
                  ("speech_mixed_fixture", cases[0][1] + bytes(16000) + cases[1][1]),
                  ("silence_3s", bytes(96000)),
                  ("noise_3s", array.array("h", (rng.randint(-1200, 1200) for _ in range(48000))).tobytes())]
        for name, audio in cases:
            for mode in args.modes:
                perform(audio, name, "quality_synthetic", 1, 10000, mode,
                        reference="" if name in ("silence_3s", "noise_3s") else None)
    if args.quality_manifest:
        manifest = json.loads(args.quality_manifest.read_text())
        if len(manifest["fixtures"]) > 24:
            raise BenchmarkError("quality manifest exceeds bound")
        for fixture in manifest["fixtures"]:
            path = Path(fixture["path"])
            if not path.is_absolute():
                path = root / path
            audio = latency.load_fixture(path, 1, 0.0)
            for mode in args.modes:
                perform(audio, fixture["id"], "quality_human", 1, 10000, mode,
                        reference=fixture["reference"])


if __name__ == "__main__":
    try:
        main()
    except (OSError, BenchmarkError, codec.CodecError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, (BenchmarkError, codec.CodecError))
                          else "host IO failed", "kind": type(error).__name__}), flush=True)
        raise SystemExit(1) from None
