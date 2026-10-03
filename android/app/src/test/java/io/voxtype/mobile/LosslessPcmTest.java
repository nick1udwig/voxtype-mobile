package io.voxtype.mobile;

import static org.junit.Assert.*;

import java.util.Random;
import java.util.zip.Inflater;
import okio.Buffer;
import okio.ByteString;
import org.junit.Test;

public class LosslessPcmTest {
  static ByteString decode(ByteString frame) throws Exception {
    Buffer buffer = new Buffer().write(frame);
    int codec = buffer.readByte();
    int size = buffer.readInt();
    if (codec == 0) {
      assertEquals(size, buffer.size());
      return buffer.readByteString();
    }
    assertEquals(1, codec);
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(buffer.readByteArray());
      byte[] pcm = new byte[size + 1];
      assertEquals(size, inflater.inflate(pcm));
      assertTrue(inflater.finished());
      assertEquals(0, inflater.getRemaining());
      return ByteString.of(pcm, 0, size);
    } finally {
      inflater.end();
    }
  }

  @Test
  public void fixedCrossLanguageGoldenVectorPreservesEverySample() throws Exception {
    Buffer repeated = new Buffer();
    for (int i = 0; i < 8; i++) repeated.write(ByteString.decodeHex("0080ff7f00000100"));
    ByteString pcm = repeated.readByteString();
    ByteString golden = ByteString.decodeHex("010000004078016368f85fcfc0c0c8c040260d001f360ff9");
    assertEquals(pcm, decode(golden));
    // zlib streams can differ across platform encoder versions; PCM is canonical.
    assertEquals(pcm, decode(LosslessPcm.encode(pcm)));
  }

  @Test
  public void silenceCompressesAndIndependentFramesPreserveEverySample() throws Exception {
    ByteString silence = ByteString.of(new byte[3200]);
    ByteString encoded = LosslessPcm.encode(silence);
    assertEquals(1, encoded.getByte(0));
    assertTrue(encoded.size() < silence.size() / 10);
    assertEquals(silence, decode(encoded));
    ByteString extremes = ByteString.decodeHex("0080ff7f00000100");
    assertEquals(extremes, decode(LosslessPcm.encode(extremes)));
    assertEquals(silence, decode(LosslessPcm.encode(silence)));
  }

  @Test
  public void incompressibleFramesUseRawEnvelope() throws Exception {
    byte[] noise = new byte[3200];
    new Random(123).nextBytes(noise);
    ByteString pcm = ByteString.of(noise);
    ByteString encoded = LosslessPcm.encode(pcm);
    assertEquals(0, encoded.getByte(0));
    assertEquals(pcm.size() + 5, encoded.size());
    assertEquals(pcm, decode(encoded));
  }

  @Test
  public void lengthBoundsKeepRawFallbackWithinWebsocketMessageLimit() throws Exception {
    byte[] noise = new byte[LosslessPcm.MAX_PCM_BYTES];
    new Random(123).nextBytes(noise);
    ByteString pcm = ByteString.of(noise);
    assertEquals(pcm, decode(LosslessPcm.encode(pcm)));
    assertTrue(LosslessPcm.encode(pcm).size() + 8 <= 65536);
    for (int size : new int[] {0, 1, 3, LosslessPcm.MAX_PCM_BYTES + 2}) {
      assertThrows(
          IllegalArgumentException.class, () -> LosslessPcm.encode(ByteString.of(new byte[size])));
    }
  }
}
