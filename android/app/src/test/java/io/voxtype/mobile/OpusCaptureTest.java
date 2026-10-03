package io.voxtype.mobile;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class OpusCaptureTest {
  static final class Encoder implements OpusCapture.Encoder {
    final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    boolean finished, closed;

    public void audio(byte[] bytes, int length) {
      pcm.write(bytes, 0, length);
    }

    public void finish() {
      finished = true;
    }

    public void close() {
      closed = true;
    }
  }

  @Test
  public void quickFinishDuringDelayedCodecStartupDrainsAllCapturedPcm() throws Exception {
    Encoder encoder = new Encoder();
    CountDownLatch entered = new CountDownLatch(1),
        initialize = new CountDownLatch(1),
        complete = new CountDownLatch(1);
    AtomicReference<Exception> error = new AtomicReference<>();
    OpusCapture capture =
        new OpusCapture(
            () -> {
              entered.countDown();
              assertTrue(initialize.await(2, TimeUnit.SECONDS));
              return encoder;
            },
            () -> false,
            complete::countDown,
            error::set);
    capture.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    byte[] first = {1, 2, 3, 4}, last = {5, 6};
    capture.audio(first, first.length);
    capture.audio(last, last.length);
    capture.finish();
    first[0] = 99;
    assertEquals(3, capture.queuedChunks());
    initialize.countDown();
    assertTrue(complete.await(2, TimeUnit.SECONDS));
    assertNull(error.get());
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, encoder.pcm.toByteArray());
    assertTrue(encoder.finished);
  }

  @Test
  public void cancellationDuringStartupDiscardsQueueAndReleasesCodecWithoutFinal()
      throws Exception {
    Encoder encoder = new Encoder();
    AtomicBoolean cancelled = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1),
        initialize = new CountDownLatch(1),
        closed = new CountDownLatch(1);
    OpusCapture capture =
        new OpusCapture(
            () -> {
              entered.countDown();
              assertTrue(initialize.await(2, TimeUnit.SECONDS));
              return new OpusCapture.Encoder() {
                public void audio(byte[] pcm, int length) {
                  encoder.audio(pcm, length);
                }

                public void finish() {
                  encoder.finish();
                }

                public void close() {
                  encoder.close();
                  closed.countDown();
                }
              };
            },
            cancelled::get,
            () -> fail("Late finish"),
            error -> fail("Late error"));
    capture.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    for (int i = 0; i < OpusCapture.QUEUE_CHUNKS; i++) capture.audio(new byte[3200], 3200);
    assertEquals(OpusCapture.QUEUE_CHUNKS, capture.queuedChunks());
    cancelled.set(true);
    capture.audio(new byte[2], 2);
    initialize.countDown();
    assertTrue(closed.await(2, TimeUnit.SECONDS));
    assertTrue(encoder.closed);
    assertFalse(encoder.finished);
    assertEquals(0, encoder.pcm.size());
  }

  @Test
  public void saturatedLiveCaptureFailsImmediatelyInsteadOfSilentlyDroppingAudio()
      throws Exception {
    Encoder encoder = new Encoder();
    AtomicBoolean cancelled = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1),
        initialize = new CountDownLatch(1),
        closed = new CountDownLatch(1);
    OpusCapture capture =
        new OpusCapture(
            () -> {
              entered.countDown();
              assertTrue(initialize.await(2, TimeUnit.SECONDS));
              return new OpusCapture.Encoder() {
                public void audio(byte[] pcm, int length) {
                  encoder.audio(pcm, length);
                }

                public void finish() {
                  encoder.finish();
                }

                public void close() {
                  encoder.close();
                  closed.countDown();
                }
              };
            },
            cancelled::get,
            () -> fail("Truncated final"),
            error -> fail("Late error"));
    capture.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    for (int i = 0; i < OpusCapture.QUEUE_CHUNKS; i++) capture.audio(new byte[3200], 3200);
    long before = System.nanoTime();
    assertThrows(IllegalStateException.class, () -> capture.audio(new byte[3200], 3200));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100);
    // The capture owner treats saturation as a recording failure, cancels the
    // logical session and never claims a complete recording from truncated PCM.
    cancelled.set(true);
    initialize.countDown();
    assertTrue(closed.await(2, TimeUnit.SECONDS));
    assertFalse(encoder.finished);
    assertEquals(0, encoder.pcm.size());
  }
}
