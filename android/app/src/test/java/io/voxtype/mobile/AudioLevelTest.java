package io.voxtype.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class AudioLevelTest {
  @Test
  public void silenceAndEmptyReadsHaveNoActivity() {
    assertEquals(0, AudioLevel.pcm16(new byte[3200], 3200), 0);
    assertEquals(0, AudioLevel.pcm16(new byte[0], 0), 0);
  }

  @Test
  public void signedLittleEndianAudioHasSymmetricBoundedLevels() {
    assertEquals(
        AudioLevel.pcm16(new byte[] {0, 16}, 2), AudioLevel.pcm16(new byte[] {0, -16}, 2), .0001);
    assertEquals(1, AudioLevel.pcm16(new byte[] {-1, 127, 0, -128}, 4), 0);
    assertTrue(AudioLevel.pcm16(new byte[] {0, 16}, 2) > AudioLevel.pcm16(new byte[] {0, 1}, 2));
  }

  @Test
  public void meterOnlyReadsValidCapturedBytes() {
    assertEquals(0, AudioLevel.pcm16(new byte[] {0, 0, -1, 127}, 2), 0);
  }
}
