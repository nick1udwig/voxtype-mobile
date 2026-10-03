package io.voxtype.mobile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import okio.Buffer;
import okio.ByteString;

/** Codec config and bounded packet framing, independent of Android codec scheduling. */
final class OpusFrames {
  static final String ENCODING = "opus_v1";
  static final int MAX_PACKET = 1275, MAX_PACKETS = 5, MAX_FRAMES = 15002;
  static final int MAX_BYTES = 2 * 1024 * 1024, MAX_SAMPLES = 300 * 16000;
  private final Consumer<ByteString> sink;
  private final List<byte[]> packets = new ArrayList<>();
  private byte[] originalHead;
  private int skip48 = -1, sequence, packetCount;
  private boolean ended;

  OpusFrames(Consumer<ByteString> sink) {
    this.sink = sink;
  }

  static byte[] bytes(ByteBuffer value) {
    ByteBuffer copy = value.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }

  void configure(byte[] data, byte[] delay) {
    if (ended) throw new IllegalStateException("Opus config after EOS");
    byte[] head = data;
    byte[] wrappedDelay = null;
    if (starts(data, "AOPUSHDR")) {
      ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
      head = null;
      while (input.hasRemaining()) {
        if (input.remaining() < 16) throw new IllegalStateException("Truncated Opus config");
        byte[] tag = new byte[8];
        input.get(tag);
        long size = input.getLong();
        if (size < 0 || size > input.remaining() || size > 256)
          throw new IllegalStateException("Invalid Opus config size");
        byte[] value = new byte[(int) size];
        input.get(value);
        String name = new String(tag, StandardCharsets.US_ASCII);
        if (name.equals("AOPUSHDR") && head == null) head = value;
        else if (name.equals("AOPUSDLY") && wrappedDelay == null) wrappedDelay = value;
        else if (!name.equals("AOPUSPRL"))
          throw new IllegalStateException("Invalid Opus config tag");
      }
    }
    if (head == null
        || head.length != 19
        || !starts(head, "OpusHead")
        || head[8] != 1
        || head[9] != 1
        || head[18] != 0
        || head[16] != 0
        || head[17] != 0) throw new IllegalStateException("Unsupported Opus encoder config");
    int value = (head[10] & 255) | ((head[11] & 255) << 8);
    byte[] override = delay != null ? delay : wrappedDelay;
    if (override != null) {
      if (override.length != 8) throw new IllegalStateException("Invalid Opus delay config");
      long nanos = ByteBuffer.wrap(override).order(ByteOrder.nativeOrder()).getLong();
      if (nanos < 0 || nanos > 80000000)
        throw new IllegalStateException("Unsupported Opus encoder delay");
      value = (int) ((nanos * 48000L + 500000000L) / 1000000000L);
    }
    if (override == null && skip48 >= 0) {
      if (!java.util.Arrays.equals(head, originalHead) && value != skip48)
        throw new IllegalStateException("Changed Opus encoder config");
      value = skip48;
    }
    if (originalHead == null) originalHead = head.clone();
    if (value > 3840 || value % 3 != 0 || (skip48 >= 0 && skip48 != value))
      throw new IllegalStateException("Unsupported or changed Opus encoder delay");
    skip48 = value;
  }

  private static boolean starts(byte[] bytes, String prefix) {
    byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length < expected.length) return false;
    for (int i = 0; i < expected.length; i++) if (bytes[i] != expected[i]) return false;
    return true;
  }

  int skipSamples() {
    if (skip48 < 0) throw new IllegalStateException("Opus encoder did not supply its delay");
    return skip48 / 3;
  }

  void packet(byte[] packet) {
    if (ended || skip48 < 0 || packet.length < 1 || packet.length > MAX_PACKET)
      throw new IllegalStateException("Invalid Opus output packet");
    int toc = packet[0] & 255, config = toc >> 3;
    int samples =
        config >= 16
            ? 40 << (config & 3)
            : config >= 12 ? 160 << (config & 1) : (config & 3) == 3 ? 960 : 160 << (config & 3);
    int count = (toc & 3) == 0 ? 1 : (toc & 3) == 3 ? (packet.length > 1 ? packet[1] & 63 : 0) : 2;
    if ((toc & 4) != 0 || samples * count != 320 || ++packetCount > 15006)
      throw new IllegalStateException("Opus encoder must emit mono 20 ms packets");
    if (packets.size() == MAX_PACKETS) emit(false, 0);
    packets.add(packet.clone());
  }

  void finish(int originalSamples) {
    if (ended || originalSamples < 0 || originalSamples > MAX_SAMPLES)
      throw new IllegalStateException("Invalid Opus recording duration");
    if (originalSamples == 0 && packetCount == 0) skip48 = 0;
    int available = packetCount * 320 - skipSamples();
    if (available < originalSamples || available - originalSamples > 640)
      throw new IllegalStateException("Opus encoder did not preserve the recording tail");
    ended = true;
    emit(true, originalSamples);
  }

  private void emit(boolean last, int total) {
    if (sequence >= MAX_FRAMES) throw new IllegalStateException("Opus frame limit reached");
    Buffer body =
        new Buffer()
            .writeLong(sequence)
            .writeByte(1)
            .writeByte((sequence == 0 ? 1 : 0) | (last ? 2 : 0))
            .writeShort(sequence == 0 ? skip48 : 0)
            .writeInt(total)
            .writeByte(packets.size());
    for (byte[] packet : packets) body.writeShort(packet.length).write(packet);
    sink.accept(body.readByteString());
    packets.clear();
    sequence++;
  }
}
