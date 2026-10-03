#!/usr/bin/env python3
"""Fake-backend localhost checks for safe admission and prompt shaped errors.

Requires localhost socket permission. Never connects to the daemon or loads a
model: the backend WebSocket constructor is replaced before starting threads.
"""

import importlib.util
import json
from pathlib import Path
import struct
import sys
import threading
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("opus_adapter_benchmark", Path(__file__).with_name("benchmark-opus.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)
actual_client = benchmark.latency.WebSocket


class FakeBackend:
    instances = []

    def __init__(self, token, resumable):
        if resumable:
            raise AssertionError("benchmark must never use replacing resumable admission")
        self.pcm = bytearray()
        self.controls = []
        self.first = True
        self.finished = threading.Event()
        self.closed = threading.Event()
        self.instances.append(self)

    def receive(self, deadline):
        if self.first:
            self.first = False
            return {"type": "ready", "sample_rate": 16000, "channels": 1,
                    "format": "pcm_s16le", "preview": "fake"}
        while not self.finished.wait(0.01):
            if self.closed.is_set() or time.monotonic() > deadline:
                raise benchmark.BenchmarkError("fake backend closed")
        return {"type": "final", "text": ""}

    def send(self, opcode, payload):
        if opcode == 2:
            if len(payload) > 3200 or len(self.pcm) + len(payload) > 204800:
                raise AssertionError("fake backend PCM bound exceeded")
            self.pcm.extend(payload)
        else:
            self.controls.append(json.loads(payload)["type"])
            if len(self.controls) > 8:
                raise AssertionError("fake backend controls exceeded")
            if self.controls[-1] == "finish":
                self.finished.set()

    def close(self):
        self.closed.set()


class AdapterTests(unittest.TestCase):
    def setUp(self):
        FakeBackend.instances = []

    def test_legacy_pcm_forwarding_and_padded_native_offsets(self):
        for samples in (1520, 44773):
            with self.subTest(samples=samples), patch.object(benchmark.latency, "WebSocket", FakeBackend):
                adapter = benchmark.CodecAdapter("fake-token", "opus", 5)
                client = encoder = None
                try:
                    client = actual_client("fake-token", True, port=adapter.port)
                    self.assertEqual(client.receive(time.monotonic() + 2)["type"], "ready")
                    encoder = benchmark.codec.Encoder()
                    for packets, offset, first, final in benchmark.streaming_aggregates(
                            bytes(samples * 2), encoder.lookahead, lambda value: None, encoder.encode):
                        aggregate = benchmark.codec.pack_aggregate(
                            packets, first, final, encoder.lookahead if first else 0,
                            samples if final else 0)
                        client.send(2, struct.pack("!Q", offset) + aggregate)
                    client.send(1, b'{"type":"finish"}')
                    self.assertEqual(client.receive(time.monotonic() + 2)["type"], "final")
                    client.send(1, b'{"type":"ack"}')
                    adapter.thread.join(timeout=2)
                    self.assertFalse(adapter.thread.is_alive())
                    self.assertIsNone(adapter.error)
                    self.assertEqual(len(FakeBackend.instances[-1].pcm), samples * 2)
                    self.assertEqual(FakeBackend.instances[-1].controls, ["finish"])
                finally:
                    if encoder:
                        encoder.close()
                    if client:
                        client.close()
                    adapter.close()

    def test_bad_codec_surfaces_error_through_shaper_without_final_timeout(self):
        with patch.object(benchmark.latency, "WebSocket", FakeBackend):
            adapter = benchmark.CodecAdapter("fake-token", "opus_zlib", 5)
            proxy = benchmark.transport.Bottleneck(512, 5, adapter.port)
            client = None
            try:
                client = actual_client("fake-token", True, port=proxy.port)
                self.assertEqual(client.receive(time.monotonic() + 2)["type"], "ready")
                start = time.monotonic()
                client.send(2, struct.pack("!Q", 0) + b"bad-zlib")
                event = client.receive(start + 2)
                self.assertEqual(event, {"type": "error", "code": "benchmark_adapter_failure"})
                self.assertLess(time.monotonic() - start, 1)
                adapter.thread.join(timeout=2)
                self.assertIn("cancel", FakeBackend.instances[0].controls)
                self.assertTrue(FakeBackend.instances[0].closed.is_set())
            finally:
                if client:
                    client.close()
                proxy.close()
                adapter.close()


if __name__ == "__main__":
    unittest.main()
