#!/usr/bin/env python3
"""Bounded host Opus codec helpers; no model or file container.

Encoder is reused by the current protocol2 latency benchmark. The aggregate
helpers below retain the historical experiment envelope (16k pre-skip units),
not the production frame sequence/48k pre-skip envelope.

Aggregate envelope: version u8, flags u8 (first=1,last=2), pre-skip u16,
final original sample count u32, packet count u8; each packet has a u16 length.
Lengths/counts are big endian. Opus state persists across aggregates. This
experimental envelope is not the production daemon/Android protocol.
"""

import ctypes
import ctypes.util
import struct
import zlib

SAMPLE_RATE = 16000
PACKET_SAMPLES = 320
MAX_PACKET_BYTES = 1275
MAX_PACKETS = 5
MAX_AGGREGATE_BYTES = 9 + MAX_PACKETS * (2 + MAX_PACKET_BYTES)
MAX_SAMPLES = 300 * SAMPLE_RATE


class CodecError(Exception):
    pass


_opus = ctypes.CDLL(ctypes.util.find_library("opus") or "libopus.so.0")
_opus.opus_encoder_create.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                     ctypes.POINTER(ctypes.c_int)]
_opus.opus_encoder_create.restype = ctypes.c_void_p
_opus.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
_opus.opus_encoder_ctl.argtypes = [ctypes.c_void_p, ctypes.c_int]
_opus.opus_encoder_ctl.restype = ctypes.c_int
_opus.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16),
                             ctypes.c_int, ctypes.POINTER(ctypes.c_ubyte), ctypes.c_int]
_opus.opus_encode.restype = ctypes.c_int
_opus.opus_decoder_create.argtypes = [ctypes.c_int, ctypes.c_int,
                                     ctypes.POINTER(ctypes.c_int)]
_opus.opus_decoder_create.restype = ctypes.c_void_p
_opus.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
_opus.opus_decode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte),
                             ctypes.c_int, ctypes.POINTER(ctypes.c_int16),
                             ctypes.c_int, ctypes.c_int]
_opus.opus_decode.restype = ctypes.c_int
_opus.opus_packet_get_nb_samples.argtypes = [ctypes.POINTER(ctypes.c_ubyte),
                                            ctypes.c_int, ctypes.c_int]
_opus.opus_packet_get_nb_samples.restype = ctypes.c_int
_opus.opus_get_version_string.restype = ctypes.c_char_p
VERSION = _opus.opus_get_version_string().decode("ascii")


class Encoder:
    def __init__(self, bitrate=24000):
        if bitrate not in (24000, 32000):
            raise CodecError("benchmark bitrate must be 24000 or 32000")
        error = ctypes.c_int()
        self.state = _opus.opus_encoder_create(SAMPLE_RATE, 1, 2048, ctypes.byref(error))
        if not self.state or error.value:
            raise CodecError("Opus encoder creation failed")
        try:
            # Explicit unconstrained VBR, complexity 10; no DTX/FEC or loss.
            for request, value in ((4002, bitrate), (4006, 1), (4020, 0),
                                   (4010, 10), (4012, 0), (4014, 0), (4016, 0)):
                if _opus.opus_encoder_ctl(self.state, request, ctypes.c_int(value)):
                    raise CodecError("Opus encoder control failed")
            lookahead = ctypes.c_int()
            if _opus.opus_encoder_ctl(self.state, 4027, ctypes.byref(lookahead)):
                raise CodecError("Opus lookahead query failed")
            self.lookahead = lookahead.value
            if not 0 <= self.lookahead <= PACKET_SAMPLES:
                raise CodecError("unexpected Opus lookahead")
        except BaseException:
            self.close()
            raise

    def encode(self, pcm):
        if len(pcm) != PACKET_SAMPLES * 2:
            raise CodecError("Opus requires one 20 ms packet")
        samples = (ctypes.c_int16 * PACKET_SAMPLES).from_buffer_copy(pcm)
        output = (ctypes.c_ubyte * MAX_PACKET_BYTES)()
        length = _opus.opus_encode(self.state, samples, PACKET_SAMPLES,
                                   output, MAX_PACKET_BYTES)
        if not 1 <= length <= MAX_PACKET_BYTES:
            raise CodecError("Opus encode failed")
        return bytes(output[:length])

    def close(self):
        if self.state:
            _opus.opus_encoder_destroy(self.state)
            self.state = None


def pack_aggregate(packets, first=False, final=False, pre_skip=0, total_samples=0):
    if not (1 <= len(packets) <= MAX_PACKETS and
            all(1 <= len(packet) <= MAX_PACKET_BYTES for packet in packets)):
        raise CodecError("invalid Opus packet bounds")
    if (not first and pre_skip) or not 0 <= pre_skip <= PACKET_SAMPLES:
        raise CodecError("invalid Opus pre-skip")
    if (not final and total_samples) or (final and not 1 <= total_samples <= MAX_SAMPLES):
        raise CodecError("invalid final sample count")
    return (struct.pack("!BBHIB", 1, int(first) | (int(final) << 1),
                        pre_skip, total_samples, len(packets)) +
            b"".join(struct.pack("!H", len(packet)) + packet for packet in packets))


def unpack_aggregate(payload):
    if not 9 <= len(payload) <= MAX_AGGREGATE_BYTES:
        raise CodecError("invalid aggregate length")
    version, flags, pre_skip, total, count = struct.unpack("!BBHIB", payload[:9])
    first, final = bool(flags & 1), bool(flags & 2)
    if version != 1 or flags > 3 or not 1 <= count <= MAX_PACKETS:
        raise CodecError("invalid aggregate header")
    if (not first and pre_skip) or not 0 <= pre_skip <= PACKET_SAMPLES:
        raise CodecError("invalid aggregate pre-skip")
    if (not final and total) or (final and not 1 <= total <= MAX_SAMPLES):
        raise CodecError("invalid aggregate duration")
    packets, offset = [], 9
    for _ in range(count):
        if offset + 2 > len(payload):
            raise CodecError("truncated packet length")
        length = struct.unpack("!H", payload[offset:offset + 2])[0]
        offset += 2
        if not 1 <= length <= MAX_PACKET_BYTES or offset + length > len(payload):
            raise CodecError("invalid/truncated packet")
        packets.append(payload[offset:offset + length])
        offset += length
    if offset != len(payload):
        raise CodecError("trailing aggregate bytes")
    return packets, first, final, pre_skip, total


class Decoder:
    def __init__(self):
        error = ctypes.c_int()
        self.state = _opus.opus_decoder_create(SAMPLE_RATE, 1, ctypes.byref(error))
        if not self.state or error.value:
            raise CodecError("Opus decoder creation failed")
        self.started = self.finished = False
        self.skip = self.output_samples = self.packet_count = 0

    def decode(self, payload):
        packets, first, final, pre_skip, total = unpack_aggregate(payload)
        if self.finished or first == self.started:
            raise CodecError("invalid Opus stream boundary")
        if first:
            self.skip = pre_skip
            self.started = True
        decoded = bytearray()
        for packet in packets:
            encoded = (ctypes.c_ubyte * len(packet)).from_buffer_copy(packet)
            if _opus.opus_packet_get_nb_samples(encoded, len(packet), SAMPLE_RATE) != PACKET_SAMPLES:
                raise CodecError("Opus packet is not 20 ms")
            output = (ctypes.c_int16 * PACKET_SAMPLES)()
            count = _opus.opus_decode(self.state, encoded, len(packet), output,
                                      PACKET_SAMPLES, 0)
            if count != PACKET_SAMPLES:
                raise CodecError("invalid Opus packet")
            raw = bytes(output)
            trim = min(self.skip, count)
            self.skip -= trim
            decoded.extend(raw[trim * 2:])
            self.packet_count += 1
        if final:
            remaining = total - self.output_samples
            if remaining < 0 or remaining * 2 > len(decoded) or self.skip:
                raise CodecError("Opus final duration cannot be restored")
            decoded = decoded[:remaining * 2]
            self.finished = True
        self.output_samples += len(decoded) // 2
        if self.output_samples > MAX_SAMPLES:
            raise CodecError("Opus duration exceeded")
        return bytes(decoded)

    def close(self):
        if self.state:
            _opus.opus_decoder_destroy(self.state)
            self.state = None


def zlib_envelope(payload, force=False):
    compressed = zlib.compress(payload, 1)
    codec = int(force or len(compressed) < len(payload))
    return bytes([codec]) + struct.pack("!I", len(payload)) + (compressed if codec else payload)


def unwrap_zlib(envelope, maximum):
    if len(envelope) < 6 or envelope[0] not in (0, 1):
        raise CodecError("invalid lossless envelope")
    expected = struct.unpack("!I", envelope[1:5])[0]
    if not 1 <= expected <= maximum:
        raise CodecError("lossless decoded size exceeded")
    if envelope[0] == 0:
        decoded = envelope[5:]
    else:
        stream = zlib.decompressobj()
        try:
            decoded = stream.decompress(envelope[5:], expected + 1)
        except zlib.error:
            raise CodecError("invalid lossless stream") from None
        if (not stream.eof or stream.unused_data or stream.unconsumed_tail):
            raise CodecError("truncated/trailing lossless stream")
    if len(decoded) != expected:
        raise CodecError("lossless length mismatch")
    return decoded
