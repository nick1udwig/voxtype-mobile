#!/usr/bin/env python3
"""Paced Opus protocol 2 timing against the existing daemon; no model or transcript storage.

Example (requires access to the host daemon and token):
    scripts/benchmark-latency.py --runs 3 --output artifacts/latency.json

Use --workers only for a short, deliberate contention experiment. Workers are
bounded to at most half the host's CPUs (and eight workers), stop in finally,
and have an independent deadline. This script never changes service priority.
"""

import argparse
import base64
import hashlib
import importlib.util
import json
import multiprocessing
import os
from pathlib import Path
import socket
import struct
import threading
import time
import uuid
import wave


class BenchmarkError(Exception):
    """Safe diagnostic that contains no credentials or server response text."""


class WebSocket:
    """Bounded RFC 6455 helper; legacy callers are historical experiments only."""

    def __init__(self, token, resumable, port=8765, audio_encoding=None, protocol=None):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.sock.settimeout(1)
        self.write_lock = threading.Lock()
        self.buffer = bytearray()
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        headers = [
            "GET /v1/dictate HTTP/1.1", f"Host: 127.0.0.1:{port}",
            "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Version: 13", f"Sec-WebSocket-Key: {key}",
            f"Authorization: Bearer {token}",
        ]
        if resumable:
            headers.append(f"X-Voxtype-Session: {uuid.uuid4().hex}")
        if protocol is not None:
            headers.append(f"X-Voxtype-Protocol: {protocol}")
        if audio_encoding is not None:
            headers.append(f"X-Voxtype-Audio: {audio_encoding}")
        upgrade = ("\r\n".join(headers) + "\r\n\r\n").encode("ascii")
        self.sock.sendall(upgrade)
        self.sent_bytes = len(upgrade)
        deadline = time.monotonic() + 10
        while b"\r\n\r\n" not in self.buffer:
            if time.monotonic() > deadline or len(self.buffer) > 16_384:
                self.close()
                raise BenchmarkError("websocket upgrade timed out or exceeded header limit")
            try:
                chunk = self.sock.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                self.close()
                raise BenchmarkError("daemon closed during websocket upgrade")
            self.buffer.extend(chunk)
        head, remaining = self.buffer.split(b"\r\n\r\n", 1)
        self.buffer = bytearray(remaining)
        lines = head.decode("ascii", errors="replace").split("\r\n")
        if lines[0].split(" ")[1:2] != ["101"]:
            self.close()
            raise BenchmarkError("daemon rejected websocket upgrade")
        values = {name.lower(): value for name, value in (
            line.split(":", 1) for line in lines[1:] if ":" in line
        )}
        expected = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
        ).digest()).decode("ascii")
        if values.get("sec-websocket-accept", "").strip() != expected:
            self.close()
            raise BenchmarkError("invalid websocket upgrade accept")

    def _read(self, length, deadline):
        while len(self.buffer) < length:
            if time.monotonic() > deadline:
                raise BenchmarkError("daemon response deadline exceeded")
            try:
                chunk = self.sock.recv(max(4096, length - len(self.buffer)))
            except socket.timeout:
                continue
            if not chunk:
                raise BenchmarkError("daemon closed before final result")
            self.buffer.extend(chunk)
        result = bytes(self.buffer[:length])
        del self.buffer[:length]
        return result

    def send(self, opcode, payload):
        mask = os.urandom(4)
        length = len(payload)
        header = bytes([0x80 | opcode])
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 65_536:
            header += bytes([0x80 | 126]) + struct.pack("!H", length)
        else:
            header += bytes([0x80 | 127]) + struct.pack("!Q", length)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        with self.write_lock:
            self.sock.sendall(header + mask + masked)
            self.sent_bytes += len(header) + len(mask) + len(masked)

    def receive(self, deadline):
        fragments = bytearray()
        while True:
            first, second = self._read(2, deadline)
            opcode, final = first & 0x0F, bool(first & 0x80)
            if second & 0x80:
                raise BenchmarkError("daemon returned masked websocket frame")
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read(2, deadline))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read(8, deadline))[0]
            if length > 1_048_576 or len(fragments) + length > 1_048_576:
                raise BenchmarkError("daemon message exceeds benchmark limit")
            payload = self._read(length, deadline)
            if opcode == 9:
                self.send(10, payload)
                continue
            if opcode == 10:
                continue
            if opcode == 8:
                raise BenchmarkError("daemon closed before final result")
            if opcode not in (0, 1):
                raise BenchmarkError("unexpected daemon websocket message")
            fragments.extend(payload)
            if final:
                try:
                    return json.loads(fragments)
                except (UnicodeDecodeError, json.JSONDecodeError):
                    raise BenchmarkError("invalid daemon JSON event") from None

    def close(self):
        self.sock.close()


def load_fixture(path, repeats, gap_seconds):
    with wave.open(str(path), "rb") as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (16_000, 1, 2):
            raise BenchmarkError("fixture must be 16 kHz mono signed 16-bit WAV")
        pcm = wav.readframes(wav.getnframes())
    gap = bytes(round(gap_seconds * 16_000) * 2)
    # Trailing silence matches the previously published fixture measurements.
    audio = (pcm + gap) * repeats
    if len(audio) > 300 * 32_000:
        raise BenchmarkError("fixture exceeds daemon recording limit")
    return audio


def trial(token, audio, timeout):
    opened = time.monotonic()
    ws = WebSocket(token, True, audio_encoding="opus_v1", protocol=2)
    done = threading.Event()
    result = {"pcm_bytes": len(audio), "audio_seconds": len(audio) / 32_000}
    reader_error = []
    timeline = {}
    thread = None
    completed = False
    encoder = None
    try:
        ready = ws.receive(opened + 10)
        if ready.get("type") != "ready":
            raise BenchmarkError("daemon did not admit benchmark session (may be busy)")
        if (ready.get("sample_rate"), ready.get("channels"), ready.get("format")) != (16_000, 1, "opus"):
            raise BenchmarkError("daemon advertised incompatible audio format")
        if (ready.get("protocol"), ready.get("audio_encoding"), ready.get("framing"), ready.get("resume")) != (2,"opus_v1","sequence_opus_v1",True):
            raise BenchmarkError("upgrade app and daemon together: Opus protocol 2 required")
        if ready.get("accepted_frames") != 0 or ready.get("finished", False):
            raise BenchmarkError("fresh benchmark session unexpectedly resumed")
        result["protocol"] = 2
        result["audio_encoding"] = "opus_v1"
        result["preview"] = ready.get("preview")
        result["admission_seconds"] = time.monotonic() - opened
        start = time.monotonic()
        timeline["start"] = start

        def consume():
            try:
                deadline = start + result["audio_seconds"] + timeout
                while not done.is_set():
                    event = ws.receive(deadline)
                    now = time.monotonic()
                    kind = event.get("type")
                    if kind == "partial":
                        timeline.setdefault("first_partial", now)
                        timeline["last_partial"] = now
                        result["partial_count"] = result.get("partial_count", 0) + 1
                    elif kind == "final":
                        timeline["final"] = now
                        # Keep no transcript or transcript hash in diagnostics.
                        result["final_characters"] = len(event.get("text", ""))
                        done.set()
                    elif kind == "error":
                        raise BenchmarkError("daemon returned terminal error")
                    else:
                        raise BenchmarkError("unexpected daemon protocol event")
            except (BenchmarkError, OSError) as error:
                reader_error.append(error)
                done.set()

        thread = threading.Thread(target=consume, name="benchmark-events", daemon=True)
        thread.start()
        spec = importlib.util.spec_from_file_location("mobile_opus", Path(__file__).with_name("opus-codec.py"))
        codec = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(codec)
        encoder = codec.Encoder()
        packets, sequence, audio_bytes = [], 0, 0
        def upload(last):
            nonlocal sequence, audio_bytes
            frame = struct.pack("!QBBHIB",sequence,1,(1 if sequence==0 else 0)|(2 if last else 0),
                encoder.lookahead*3 if sequence==0 else 0,len(audio)//2 if last else 0,len(packets))
            frame += b"".join(struct.pack("!H",len(packet))+packet for packet in packets)
            ws.send(2,frame);audio_bytes += len(frame);sequence += 1;packets.clear()
        lag = 0.0
        padded_samples = ((len(audio)//2 + encoder.lookahead + 319)//320)*320
        for offset in range(0,padded_samples*2,640):
            if done.is_set(): raise BenchmarkError("daemon ended session before finish")
            captured = min(offset+640,len(audio))
            due = start + captured / 32000
            remaining = due-time.monotonic()
            if remaining>0: time.sleep(remaining)
            lag=max(lag,time.monotonic()-due)
            pcm=audio[offset:offset+640]
            if len(packets)==5: upload(False)
            packets.append(encoder.encode(pcm+bytes(640-len(pcm))))
        upload(True)
        result["encoded_audio_bytes"] = audio_bytes
        result["audio_frames"] = sequence
        timeline["finish"] = time.monotonic()
        ws.send(1, b'{"type":"finish"}')
        if not done.wait(timeout):
            raise BenchmarkError("final result deadline exceeded")
        if reader_error:
            raise BenchmarkError(str(reader_error[0]) if isinstance(reader_error[0], BenchmarkError) else "socket failed while receiving result")
        ws.send(1, b'{"type":"ack"}')
        completed = True
        # Release follows the capture clock, including any pending upload delay.
        release = start + result["audio_seconds"]
        result["release_to_final_seconds"] = timeline["final"] - release
        result["finish_to_final_seconds"] = timeline["final"] - timeline["finish"]
        result["upload_backlog_at_release_seconds"] = max(0, timeline["finish"] - release)
        result["session_seconds"] = timeline["final"] - start
        result["upload_seconds"] = timeline["finish"] - start
        result["max_pacing_lag_seconds"] = lag
        result["first_partial_seconds"] = timeline.get("first_partial", timeline["final"]) - start
        result["last_partial_after_finish_seconds"] = timeline.get("last_partial", timeline["finish"]) - timeline["finish"]
        return result
    finally:
        if encoder is not None: encoder.close()
        if not completed:
            try:
                ws.send(1, b'{"type":"cancel"}')
            except OSError:
                pass
        done.set()
        ws.close()
        if thread is not None:
            thread.join(timeout=2)


def contend(stop, deadline, duty):
    """CPU-only synthetic work, no model, with a fail-safe monotonic deadline."""
    while not stop.is_set() and time.monotonic() < deadline:
        busy_until = time.monotonic() + 0.1 * duty
        value = 1
        while time.monotonic() < busy_until:
            for index in range(1000):
                value = (value * 1_664_525 + index + 1_013_904_223) & 0xFFFFFFFF
        stop.wait(0.1 * (1 - duty))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).resolve().parents[1] / "daemon/tests/fixtures/vad/speech_long.wav")
    parser.add_argument("--token-file", type=Path, default=Path.home() / ".local/share/voxtype-mobile/token")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--gap-seconds", type=float, default=0.5)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--duty", type=float, default=1.0)
    parser.add_argument("--label", default="baseline")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    max_workers = min(8, max(1, (os.cpu_count() or 1) // 2))
    if not (1 <= args.runs <= 10 and 1 <= args.repeats <= 50 and 0 <= args.gap_seconds <= 5 and 1 <= args.timeout <= 180):
        parser.error("invalid benchmark bounds")
    if not (0 <= args.workers <= max_workers and 0 < args.duty <= 1):
        parser.error(f"workers must be 0..{max_workers}, duty must be >0..1")
    audio = load_fixture(args.fixture, args.repeats, args.gap_seconds)
    token = args.token_file.read_text().strip()
    if not token or any(character in token for character in "\r\n"):
        raise BenchmarkError("invalid token file")
    context = multiprocessing.get_context("spawn")
    stop = context.Event()
    deadline = time.monotonic() + args.runs * (len(audio) / 32_000 + args.timeout + 10) + 10
    workers = [context.Process(target=contend, args=(stop, deadline, args.duty)) for _ in range(args.workers)]
    measurements = []
    try:
        for worker in workers:
            worker.start()
        if workers:
            time.sleep(1)
        for index in range(args.runs):
            measurement = trial(token, audio, args.timeout)
            measurement.update(label=args.label, run=index + 1, workers=args.workers, duty=args.duty)
            measurements.append(measurement)
            print(json.dumps(measurement, sort_keys=True), flush=True)
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(measurements, indent=2) + "\n")
            time.sleep(0.5)
    finally:
        stop.set()
        for worker in workers:
            if worker.pid is not None:
                worker.join(timeout=2)
                if worker.is_alive():
                    worker.terminate()
                    worker.join(timeout=2)


if __name__ == "__main__":
    try:
        main()
    except (BenchmarkError, OSError) as error:
        # OS diagnostics deliberately omit filenames, response bodies and token.
        print(json.dumps({"error": str(error) if isinstance(error, BenchmarkError) else "host IO failed", "kind": type(error).__name__}), flush=True)
        raise SystemExit(1) from None
