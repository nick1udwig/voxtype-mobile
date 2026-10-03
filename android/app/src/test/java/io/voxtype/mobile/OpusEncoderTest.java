package io.voxtype.mobile;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okio.ByteString;
import org.junit.Test;

public class OpusEncoderTest {
  static byte[] head(int skip) {
    return ByteBuffer.allocate(19)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        .put((byte) 1)
        .put((byte) 1)
        .putShort((short) skip)
        .putInt(16000)
        .putShort((short) 0)
        .put((byte) 0)
        .array();
  }

  static byte[] delay() {
    return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(6500000L).array();
  }

  static final byte[] PACKET = {(byte) 0xf8, (byte) 0xff, (byte) 0xfe};

  static final class Fake implements OpusEncoder.Driver, OpusEncoder.Clock {
    final Queue<OpusEncoder.Output> outputs = new ArrayDeque<>();
    boolean config, extra, eos, stalled, closed;
    int pending, samples;
    long time;
    final java.io.ByteArrayOutputStream inputBytes = new java.io.ByteArrayOutputStream();

    Fake(boolean extra) {
      this.extra = extra;
    }

    public long millis() {
      return time;
    }

    public boolean input(byte[] pcm, int offset, int length, long timestamp, boolean end) {
      time += 10;
      if (stalled) return false;
      assertEquals(samples * 1000000L / 16000, timestamp);
      if (!config) {
        config = true;
        outputs.add(new OpusEncoder.Output(head(104), delay(), true, false));
      }
      inputBytes.write(pcm, offset, length);
      samples += length / 2;
      pending += length / 2;
      while (pending >= 320) {
        outputs.add(new OpusEncoder.Output(PACKET, null, false, false));
        pending -= 320;
      }
      if (end) {
        eos = true;
        if (pending > 0 || extra) outputs.add(new OpusEncoder.Output(PACKET, null, false, false));
        outputs.add(new OpusEncoder.Output(new byte[0], null, false, true));
      }
      return true;
    }

    public OpusEncoder.Output output(boolean wait) {
      if (wait) time += 10;
      return outputs.poll();
    }

    public void close() {
      closed = true;
    }
  }

  @Test
  public void shortAndAlignedRecordingsPreserveExactDurationWithBothEosForms() {
    for (boolean extra : new boolean[] {false, true})
      for (int samples : new int[] {1, 95 * 16, 100 * 16, 101 * 16, 320, 3200, 16001}) {
        Fake fake = new Fake(extra);
        List<ByteString> frames = new ArrayList<>();
        try (OpusEncoder encoder = new OpusEncoder(fake, frames::add, () -> false, fake)) {
          byte[] pcm = new byte[samples * 2];
          for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 13);
          encoder.audio(pcm, pcm.length);
          encoder.finish();
          byte[] submitted = fake.inputBytes.toByteArray();
          assertArrayEquals(pcm, Arrays.copyOf(submitted, pcm.length));
          for (int i = pcm.length; i < submitted.length; i++) assertEquals(0, submitted[i]);
        }
        assertTrue(fake.closed && fake.eos);
        assertFalse(frames.isEmpty());
        ByteString first = frames.get(0), last = frames.get(frames.size() - 1);
        assertEquals(312, ((first.getByte(10) & 255) << 8) | (first.getByte(11) & 255));
        assertEquals(samples, last.substring(12, 16).asByteBuffer().getInt());
        assertTrue((last.getByte(9) & 2) != 0);
        for (int i = 0; i < frames.size(); i++)
          assertEquals(i, frames.get(i).asByteBuffer().getLong());
      }
  }

  @Test
  public void emptyRecordingSupportsEmptyOrPacketEos() {
    for (boolean extra : new boolean[] {false, true}) {
      Fake fake = new Fake(extra);
      List<ByteString> frames = new ArrayList<>();
      try (OpusEncoder encoder = new OpusEncoder(fake, frames::add, () -> false, fake)) {
        encoder.finish();
      }
      assertEquals(1, frames.size());
      assertEquals(0, frames.get(0).substring(12, 16).asByteBuffer().getInt());
      if (!extra) assertEquals(0, frames.get(0).getByte(10) | frames.get(0).getByte(11));
    }
  }

  @Test
  public void cancellationAndInputStallReleaseEncoderWithoutFinalFrame() {
    for (boolean cancel : new boolean[] {false, true}) {
      Fake fake = new Fake(false);
      fake.stalled = !cancel;
      List<ByteString> frames = new ArrayList<>();
      AtomicBoolean cancelled = new AtomicBoolean(cancel);
      try (OpusEncoder encoder = new OpusEncoder(fake, frames::add, cancelled::get, fake)) {
        assertThrows(IllegalStateException.class, () -> encoder.audio(new byte[640], 640));
      }
      assertTrue(fake.closed);
      assertFalse(fake.eos);
      assertTrue(frames.isEmpty());
    }
  }

  @Test
  public void delayedEosHasBoundedDeadlineAndNeverEmitsFinal() {
    Fake fake = new Fake(false);
    List<ByteString> frames = new ArrayList<>();
    OpusEncoder.Driver missing =
        new OpusEncoder.Driver() {
          public boolean input(byte[] b, int o, int n, long t, boolean eos) {
            return fake.input(b, o, n, t, false);
          }

          public OpusEncoder.Output output(boolean wait) {
            return fake.output(wait);
          }

          public void close() {
            fake.close();
          }
        };
    try (OpusEncoder encoder = new OpusEncoder(missing, frames::add, () -> false, fake)) {
      encoder.audio(new byte[640], 640);
      assertThrows(IllegalStateException.class, encoder::finish);
    }
    assertTrue(fake.closed);
    assertTrue(frames.isEmpty());
  }

  @Test
  public void configOverrideWrappedHeadersAndDuplicateConfigAreHandled() {
    List<ByteString> frames = new ArrayList<>();
    OpusFrames stream = new OpusFrames(frames::add);
    stream.configure(head(104), delay());
    stream.configure(head(104), null);
    assertThrows(IllegalStateException.class, () -> stream.configure(head(300), null));
    assertEquals(104, stream.skipSamples());
    ByteBuffer wrapped = ByteBuffer.allocate(19 + 8 + 8 + 48).order(ByteOrder.nativeOrder());
    wrapped.put("AOPUSHDR".getBytes()).putLong(19).put(head(104));
    wrapped.put("AOPUSDLY".getBytes()).putLong(8).put(delay());
    wrapped.put("AOPUSPRL".getBytes()).putLong(8).putLong(80000000);
    stream.configure(wrapped.array(), null);
    stream.packet(PACKET);
    stream.finish(1);
    assertEquals(1, frames.size());
    assertThrows(IllegalStateException.class, () -> stream.packet(PACKET));
    assertThrows(
        IllegalStateException.class, () -> new OpusFrames(frames::add).configure(head(313), null));
  }

  @Test
  public void rejectsOversizedStereoWrongDurationAndMissingTail() {
    for (byte[] packet :
        new byte[][] {new byte[1276], new byte[] {(byte) 0xfc}, new byte[] {(byte) 0xf0}}) {
      OpusFrames stream = new OpusFrames(b -> {});
      stream.configure(head(312), null);
      assertThrows(IllegalStateException.class, () -> stream.packet(packet));
    }
    OpusFrames stream = new OpusFrames(b -> {});
    stream.configure(head(312), null);
    stream.packet(PACKET);
    assertThrows(IllegalStateException.class, () -> stream.finish(320));
  }

  @Test
  public void javaFramesExactlyMatchRustRealCodecGolden() throws Exception {
    ByteBuffer fixture =
        ByteBuffer.wrap(
            java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(System.getProperty("voxtype.opusFixture"))));
    List<ByteString> expected = new ArrayList<>(), actual = new ArrayList<>();
    OpusFrames frames = new OpusFrames(actual::add);
    frames.configure(head(312), null);
    while (fixture.hasRemaining()) {
      int size = fixture.getInt();
      byte[] encoded = new byte[size];
      fixture.get(encoded);
      expected.add(ByteString.of(encoded));
      ByteBuffer input = ByteBuffer.wrap(encoded);
      input.position(16);
      int count = input.get() & 255;
      for (int i = 0; i < count; i++) {
        int length = input.getShort() & 65535;
        byte[] packet = new byte[length];
        input.get(packet);
        frames.packet(packet);
      }
    }
    frames.finish(1601);
    assertEquals(expected, actual);
  }
}
