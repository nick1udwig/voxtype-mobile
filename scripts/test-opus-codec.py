#!/usr/bin/env python3
"""Fake/audio-only codec framing and trim checks; never loads a model."""

import array
import importlib.util
import math
from pathlib import Path
import struct
import sys
import unittest
import zlib

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("opus_benchmark", Path(__file__).with_name("benchmark-opus.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)
codec = benchmark.codec


def roundtrip(audio, wrapped=False):
    encoder, decoder = codec.Encoder(), codec.Decoder()
    output, payloads = bytearray(), []
    total = len(audio) // 2
    expected_offset = 0
    try:
        for packets, offset, first, final in benchmark.streaming_aggregates(
                audio, encoder.lookahead, lambda samples: None, encoder.encode):
            if offset != expected_offset:
                raise AssertionError("native packet offset is not sequential")
            expected_offset += len(packets) * 640
            payload = codec.pack_aggregate(packets, first, final,
                                          encoder.lookahead if first else 0,
                                          total if final else 0)
            payloads.append(payload)
            if wrapped:
                restored = codec.unwrap_zlib(codec.zlib_envelope(payload, force=True),
                                             codec.MAX_AGGREGATE_BYTES)
                if restored != payload:
                    raise AssertionError("zlib changed Opus packets")
                payload = restored
            output.extend(decoder.decode(payload))
            if len(output) > len(audio):
                raise AssertionError("non-final aggregate emitted padded tail")
        return bytes(output), payloads, encoder.lookahead
    finally:
        encoder.close()
        decoder.close()


class OpusTests(unittest.TestCase):
    def test_duration_boundaries_and_stateful_zlib_identity(self):
        for samples in (1, 96, 319, 320, 321, 1440, 1520, 1573, 1600, 1616, 44773, 168096):
            with self.subTest(samples=samples):
                pcm = array.array("h", (int(12000 * math.sin(2 * math.pi * 440 * i / 16000))
                                        for i in range(samples))).tobytes()
                decoded, packets, lookahead = roundtrip(pcm)
                wrapped, wrapped_packets, _ = roundtrip(pcm, True)
                self.assertEqual(len(decoded), len(pcm))
                self.assertEqual(decoded, wrapped)
                self.assertEqual(packets, wrapped_packets)
                self.assertGreater(lookahead, 0)
                self.assertTrue(codec.unpack_aggregate(packets[0])[1])
                self.assertTrue(codec.unpack_aggregate(packets[-1])[2])

    def test_flush_never_advances_capture_clock(self):
        for samples in (1440, 1520, 1600, 1616, 44773):
            encoder = codec.Encoder()
            captured = []
            try:
                list(benchmark.streaming_aggregates(bytes(samples * 2), encoder.lookahead,
                                                    captured.append, encoder.encode))
                self.assertEqual(captured[-1], samples)
                self.assertEqual(len(captured), math.ceil(samples / 320))
                self.assertEqual(sorted(captured), captured)
            finally:
                encoder.close()

    def test_start_and_nonzero_tail_are_not_cropped(self):
        # Tone spans both edges. Correlation verifies sample alignment after
        # pre-skip; edge energy catches accidentally dropping first/last speech.
        samples = [int(15000 * math.sin(2 * math.pi * 330 * i / 16000)) for i in range(3360)]
        decoded, _, _ = roundtrip(array.array("h", samples).tobytes())
        values = array.array("h", decoded)
        for start, stop in ((0, 640), (2720, 3360)):
            energy = sum(value * value for value in values[start:stop])
            self.assertGreater(energy, 640 * 2000 ** 2)
        def correlation(shift):
            a, b = samples[400:2900], values[400 + shift:2900 + shift]
            return sum(x * y for x, y in zip(a, b)) / math.sqrt(
                sum(x * x for x in a) * sum(y * y for y in b))
        self.assertGreater(correlation(0), 0.90)
        self.assertGreater(correlation(0), correlation(104))
        self.assertGreater(correlation(0), correlation(-104))

    def test_packet_and_envelope_bounds(self):
        for packets in ([], [b"a"] * 6, [b""], [bytes(1276)]):
            with self.assertRaises(codec.CodecError):
                codec.pack_aggregate(packets)
        with self.assertRaises(codec.CodecError):
            codec.pack_aggregate([b"a"], pre_skip=1)
        for payload in (b"", bytes(codec.MAX_AGGREGATE_BYTES + 1),
                        struct.pack("!BBHIB", 1, 0, 0, 0, 0)):
            with self.assertRaises(codec.CodecError):
                codec.unpack_aggregate(payload)

    def test_truncation_trailing_and_bad_packets(self):
        _, payloads, _ = roundtrip(bytes(640))
        payload = payloads[0]
        for invalid in (payload[:-1], payload + b"a", payload[:9],
                        bytes([2]) + payload[1:]):
            with self.assertRaises(codec.CodecError):
                codec.unpack_aggregate(invalid)
        decoder = codec.Decoder()
        try:
            invalid = codec.pack_aggregate([b"\xff"], True, True, 104, 100)
            with self.assertRaises(codec.CodecError):
                decoder.decode(invalid)
        finally:
            decoder.close()

    def test_stream_boundary_and_wrong_final_count(self):
        _, payloads, _ = roundtrip(bytes(640))
        decoder = codec.Decoder()
        try:
            decoder.decode(payloads[0])
            with self.assertRaises(codec.CodecError):
                decoder.decode(payloads[0])
        finally:
            decoder.close()
        decoder = codec.Decoder()
        try:
            wrong = payloads[0][:4] + struct.pack("!I", 10000) + payloads[0][8:]
            with self.assertRaises(codec.CodecError):
                decoder.decode(wrong)
        finally:
            decoder.close()

    def test_pcm_zlib_matches_accepted_encoding(self):
        for pcm in (bytes.fromhex("0080ff7f00000100") * 8, bytes(3200),
                    bytes(range(256)) * 12):
            accepted, _, _ = benchmark.transport.encoded_frame(pcm)
            self.assertEqual(codec.zlib_envelope(pcm), accepted)
            self.assertEqual(codec.unwrap_zlib(accepted, 3200), pcm)

    def test_zlib_strict_decode_and_forced_compression(self):
        payload = bytes(range(100))
        envelope = codec.zlib_envelope(payload, force=True)
        self.assertEqual(envelope[0], 1)
        self.assertEqual(envelope[5:], zlib.compress(payload, 1))
        self.assertEqual(codec.unwrap_zlib(envelope, 100), payload)
        for invalid in (envelope[:-1], envelope + b"x", envelope + envelope[5:],
                        b"\x01" + struct.pack("!I", 1) + zlib.compress(bytes(10000))):
            with self.assertRaises(codec.CodecError):
                codec.unwrap_zlib(invalid, 100)

    def test_normalized_edit_distance(self):
        self.assertTrue(benchmark.edits("HELLO, world!", "Hello world.")["normalized_words_equal"])
        self.assertEqual(benchmark.edits("one two three", "one four three five")["token_edits"], 2)


if __name__ == "__main__":
    unittest.main()
