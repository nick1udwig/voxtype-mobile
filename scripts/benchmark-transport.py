#!/usr/bin/env python3
"""Lossless/raw protocol trials through a bounded, packetized localhost bottleneck.

Uses only the existing daemon's model. This is userspace byte-stream shaping,
not a cellular/TLS/IP-loss simulation. It changes no global network settings.
Only metadata leaves memory; transcript equality is compared in memory.
"""

import argparse
import importlib.util
import json
import queue
import socket
import struct
import sys
import threading
import time
import zlib
from pathlib import Path


sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "latency_benchmark", Path(__file__).with_name("benchmark-latency.py"))
_latency = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_latency)
BenchmarkError = _latency.BenchmarkError
ENCODING = "pcm_s16le_zlib_v1"


class Direction:
    """Serialize bounded packets and pipeline a single propagation delay."""

    def __init__(self, source, destination, bytes_per_second, latency, stop):
        self.source, self.destination = source, destination
        self.rate, self.latency, self.stop = bytes_per_second, latency, stop
        self.packets = queue.Queue(maxsize=64)
        self.lock = threading.Condition()
        self.forwarded = 0
        self.last_forwarded = None
        self.received = 0
        self.max_queue_packets = 0
        self.max_schedule_seconds = 0
        self.error = False
        self.threads = [
            threading.Thread(target=self.capture, daemon=True),
            threading.Thread(target=self.deliver, daemon=True),
        ]

    def capture(self):
        next_free = time.monotonic()
        try:
            while not self.stop.is_set():
                try:
                    packet = self.source.recv(1200)
                except socket.timeout:
                    continue
                if not packet:
                    return
                now = time.monotonic()
                # Propagation is added after serialization; it never shifts
                # next_free. Sleeping per packet here would reduce throughput.
                next_free = max(next_free, now) + len(packet) / self.rate
                due = next_free + self.latency
                while not self.stop.is_set():
                    try:
                        self.packets.put((due, packet), timeout=0.2)
                        break
                    except queue.Full:
                        continue
                self.received += len(packet)
                self.max_queue_packets = max(self.max_queue_packets, self.packets.qsize())
                self.max_schedule_seconds = max(self.max_schedule_seconds, due - now)
        except OSError:
            if not self.stop.is_set():
                self.error = True

    def deliver(self):
        try:
            while not self.stop.is_set():
                try:
                    due, packet = self.packets.get(timeout=0.2)
                except queue.Empty:
                    continue
                if self.stop.wait(max(0, due - time.monotonic())):
                    return
                self.destination.sendall(packet)
                with self.lock:
                    self.forwarded += len(packet)
                    self.last_forwarded = time.monotonic()
                    self.lock.notify_all()
        except OSError:
            if not self.stop.is_set():
                self.error = True

    def delivered_at(self, target, timeout):
        deadline = time.monotonic() + timeout
        with self.lock:
            while self.forwarded < target:
                if self.stop.is_set() or self.error or time.monotonic() >= deadline:
                    raise BenchmarkError("shaped upload did not drain")
                self.lock.wait(min(0.2, max(0, deadline - time.monotonic())))
            return self.last_forwarded


class Bottleneck:
    def __init__(self, kbps, delay_ms, destination_port=8765):
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        self.stop = threading.Event()
        self.connected = threading.Event()
        self.rate, self.latency = kbps * 1000 / 8, delay_ms / 1000
        self.destination_port = destination_port
        self.sockets, self.directions = [], []
        self.error = False
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        try:
            while not self.stop.is_set():
                try:
                    client, _ = self.listener.accept()
                    break
                except socket.timeout:
                    continue
            else:
                return
            daemon = socket.create_connection(("127.0.0.1", self.destination_port), timeout=5)
            self.sockets = [client, daemon]
            for connection in self.sockets:
                connection.settimeout(0.2)
                connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.upstream = Direction(client, daemon, self.rate, self.latency, self.stop)
            self.downstream = Direction(daemon, client, self.rate, self.latency, self.stop)
            self.directions = [self.upstream, self.downstream]
            for direction in self.directions:
                for thread in direction.threads:
                    thread.start()
            self.connected.set()
        except OSError:
            self.error = True
            self.connected.set()

    def close(self):
        self.stop.set()
        self.listener.close()
        for connection in self.sockets:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self.thread.join(timeout=1)
        for direction in self.directions:
            for thread in direction.threads:
                thread.join(timeout=1)


def encoded_frame(pcm):
    encoder_started = time.thread_time()
    compressed = zlib.compress(pcm, 1)
    codec, payload = (1, compressed) if len(compressed) < len(pcm) else (0, pcm)
    envelope = bytes([codec]) + struct.pack("!I", len(pcm)) + payload
    encoder_cpu_seconds = time.thread_time() - encoder_started
    decoded = zlib.decompress(payload) if codec else payload
    if decoded != pcm:
        raise BenchmarkError("lossless encoder changed PCM")
    return envelope, codec, encoder_cpu_seconds


def trial(token, audio, mode, kbps, delay_ms, timeout):
    proxy = Bottleneck(kbps, delay_ms)
    ws, reader = None, None
    done = threading.Event()
    errors, timeline, transcript = [], {}, []
    complete = False
    result = {"mode": mode, "kbps": kbps, "one_way_ms": delay_ms,
              "pcm_bytes": len(audio), "audio_seconds": len(audio) / 32000,
              "frame_ms": 100, "shaping": "packetized_userspace_websocket_bytes",
              "packet_bytes_max": 1200, "packet_queue_max": 64,
              "encoder": "host_python_zlib_level1_not_phone",
              "includes_tls_ip_overhead_or_loss": False}
    try:
        opened = time.monotonic()
        ws = _latency.WebSocket(token, True, port=proxy.port,
                               audio_encoding=ENCODING if mode == "zlib" else None)
        ready = ws.receive(opened + 10)
        if ready.get("type") != "ready" or not ready.get("resume"):
            raise BenchmarkError("daemon did not admit resumable benchmark session")
        if ready.get("accepted_bytes") != 0 or ready.get("finished"):
            raise BenchmarkError("fresh benchmark unexpectedly resumed")
        if (ready.get("sample_rate"), ready.get("channels"), ready.get("format")) != (16000, 1, "pcm_s16le"):
            raise BenchmarkError("incompatible daemon PCM format")
        if mode == "zlib" and ready.get("audio_encoding") != ENCODING:
            raise BenchmarkError("daemon did not acknowledge lossless transport")
        if mode == "raw" and ready.get("audio_encoding") == ENCODING:
            raise BenchmarkError("daemon selected compression without request")
        if not proxy.connected.wait(5) or proxy.error:
            raise BenchmarkError("shaping proxy could not connect")
        result["admission_seconds"] = time.monotonic() - opened
        start = time.monotonic()
        release = start + result["audio_seconds"]
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
                        raise BenchmarkError("daemon returned unexpected or terminal-error event")
            except (OSError, BenchmarkError) as error:
                errors.append(error)
                done.set()

        reader = threading.Thread(target=receive, daemon=True)
        reader.start()
        encoded_bytes = raw_frames = compressed_frames = 0
        encode_seconds = max_encode = capture_lag = 0.0
        upload_start_bytes = ws.sent_bytes
        for offset in range(0, len(audio), 3200):
            frame = audio[offset:offset + 3200]
            due = start + (offset + len(frame)) / 32000
            if done.wait(max(0, due - time.monotonic())):
                raise BenchmarkError("daemon ended benchmark before finish")
            capture_lag = max(capture_lag, time.monotonic() - due)
            if mode == "zlib":
                body, codec, elapsed = encoded_frame(frame)
                encode_seconds += elapsed
                max_encode = max(max_encode, elapsed)
                compressed_frames += codec
                raw_frames += 1 - codec
            else:
                body = frame
                raw_frames += 1
            encoded_bytes += len(body)
            ws.send(2, struct.pack("!Q", offset) + body)
        ws.send(1, b'{"type":"finish"}')
        finish_queued = time.monotonic()
        upload_target = ws.sent_bytes
        finish_delivered = proxy.upstream.delivered_at(upload_target, timeout)
        if not done.wait(max(0, deadline - time.monotonic())):
            raise BenchmarkError("shaped final deadline exceeded")
        if errors:
            raise BenchmarkError("shaped response failed")
        ws.send(1, b'{"type":"ack"}')
        proxy.upstream.delivered_at(ws.sent_bytes, 5)
        complete = True
        result.update(
            encoded_pcm_bytes=encoded_bytes,
            audio_finish_websocket_wire_bytes=upload_target - upload_start_bytes,
            compressed_frames=compressed_frames, raw_frames=raw_frames,
            encoder_cpu_seconds=encode_seconds, max_frame_encode_seconds=max_encode,
            first_partial_seconds=timeline.get("first_partial", timeline["final"]) - start,
            release_to_final_seconds=timeline["final"] - release,
            upload_drain_after_release_seconds=finish_delivered - release,
            daemon_tail_plus_downlink_seconds=timeline["final"] - finish_delivered,
            finish_queued_after_release_seconds=finish_queued - release,
            max_capture_scheduling_lag_seconds=capture_lag,
            proxy_upstream_bytes=proxy.upstream.forwarded,
            proxy_max_queue_packets=proxy.upstream.max_queue_packets,
            final_characters=len(transcript[0]), decoded_pcm_exact=True,
        )
        return result, transcript[0]
    finally:
        if ws is not None and not complete:
            try:
                ws.send(1, b'{"type":"cancel"}')
                proxy.upstream.delivered_at(ws.sent_bytes, 5)
            except (OSError, BenchmarkError, AttributeError):
                pass
        done.set()
        if ws is not None:
            ws.close()
        proxy.close()
        if reader is not None:
            reader.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path, default=Path.home() / ".local/share/voxtype-mobile/token")
    parser.add_argument("--fixture", type=Path, default=Path(__file__).resolve().parents[1] / "daemon/tests/fixtures/vad/speech_long.wav")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--kbps", type=int, nargs="+", default=[512, 192, 128])
    parser.add_argument("--one-way-ms", type=float, default=50)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--mode", choices=["both", "raw", "zlib"], default="both")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (1 <= args.runs <= 5 and all(64 <= rate <= 10000 for rate in args.kbps)
            and len(args.kbps) <= 5 and 0 <= args.one_way_ms <= 500 and 1 <= args.timeout <= 180):
        parser.error("benchmark settings exceed bounds")
    audio = _latency.load_fixture(args.fixture, 2, 0.5)
    token = args.token_file.read_text().strip()
    if not token or any(character in token for character in "\r\n"):
        raise BenchmarkError("invalid token file")
    measurements, reference = [], None
    for run in range(1, args.runs + 1):
        for rate in args.kbps:
            modes = ["raw", "zlib"] if args.mode == "both" else [args.mode]
            if run % 2 == 0:
                modes.reverse() # Interleave to reduce order/thermal confounding.
            for mode in modes:
                measurement, transcript = trial(token, audio, mode, rate, args.one_way_ms, args.timeout)
                if reference is None:
                    reference = transcript
                measurement.update(run=run, transcript_matches_reference=transcript == reference)
                measurements.append(measurement)
                print(json.dumps(measurement, sort_keys=True), flush=True)
                if args.output:
                    args.output.parent.mkdir(parents=True, exist_ok=True)
                    args.output.write_text(json.dumps(measurements, indent=2) + "\n")
                time.sleep(0.5)


if __name__ == "__main__":
    try:
        main()
    except (OSError, BenchmarkError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, BenchmarkError) else "host IO failed",
                          "kind": type(error).__name__}), flush=True)
        raise SystemExit(1) from None
