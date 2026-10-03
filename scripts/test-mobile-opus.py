#!/usr/bin/env python3
"""Current benchmark protocol/codec checks with a fake backend; no daemon/model."""
import importlib.util
from pathlib import Path
import struct
import threading
import time
import unittest
from unittest.mock import patch


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


benchmark = module('mobile_benchmark', 'benchmark-latency.py')
codec = module('mobile_codec', 'opus-codec.py')


class Backend:
    last = None

    def __init__(self, token, resumable, audio_encoding, protocol):
        assert resumable and audio_encoding == 'opus_v1' and protocol == 2
        self.first = True
        self.finished = threading.Event()
        self.decoder = codec.Decoder()
        self.pcm = bytearray()
        self.sequence = 0
        self.acked = False
        Backend.last = self

    def receive(self, deadline):
        if self.first:
            self.first = False
            return dict(type='ready', protocol=2, sample_rate=16000, channels=1,
                        format='opus', audio_encoding='opus_v1', resume=True,
                        framing='sequence_opus_v1', accepted_frames=0, finished=False)
        if not self.finished.wait(max(0, deadline - time.monotonic())):
            raise benchmark.BenchmarkError('fake backend timeout')
        return dict(type='final', text='')

    def send(self, opcode, payload):
        if opcode == 2:
            sequence, version, flags, skip48, total, count = struct.unpack('!QBBHIB', payload[:17])
            assert sequence == self.sequence and skip48 % 3 == 0
            assert version == 1 and 1 <= count <= 5
            # Same packet envelope; benchmark decoder uses16k pre-skip units.
            adapted = struct.pack('!BBHIB', version, flags, skip48 // 3, total, count) + payload[17:]
            self.pcm.extend(self.decoder.decode(adapted))
            self.sequence += 1
        elif payload == b'{"type":"finish"}':
            assert self.decoder.finished
            self.finished.set()
        elif payload == b'{"type":"ack"}':
            self.acked = True

    def close(self):
        self.decoder.close()


class Tests(unittest.TestCase):
    def test_current_benchmark_encodes_paced_frames_and_trims_original_duration(self):
        for samples in (1, 1520, 1600, 1616):
            with self.subTest(samples=samples), patch.object(benchmark, 'WebSocket', Backend):
                result = benchmark.trial('fake-token', bytes(samples * 2), 2)
                self.assertEqual(len(Backend.last.pcm), samples * 2)
                self.assertTrue(Backend.last.acked)
                self.assertEqual(result['protocol'], 2)
                self.assertEqual(result['audio_encoding'], 'opus_v1')
                self.assertGreater(result['encoded_audio_bytes'], 0)


if __name__ == '__main__':
    unittest.main()
