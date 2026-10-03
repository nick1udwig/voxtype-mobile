package io.voxtype.mobile;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded microphone handoff; codec startup and EOS never delay microphone start. */
final class OpusCapture {
  interface Encoder extends AutoCloseable {
    void audio(byte[] pcm, int length);

    void finish();

    @Override
    void close();
  }

  interface Factory {
    Encoder create() throws Exception;
  }

  static final int QUEUE_CHUNKS = 8, CHUNK_BYTES = 3200;
  private static final byte[] END = new byte[0];
  private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
  private final BooleanSupplier cancelled;
  private final Factory factory;
  private final Runnable finished;
  private final Consumer<Exception> failed;
  private volatile boolean terminal;

  OpusCapture(
      Factory factory, BooleanSupplier cancelled, Runnable finished, Consumer<Exception> failed) {
    this.factory = factory;
    this.cancelled = cancelled;
    this.finished = finished;
    this.failed = failed;
  }

  void start() {
    new Thread(this::encode, "voxtype-opus").start();
  }

  void audio(byte[] pcm, int length) throws InterruptedException {
    if (length <= 0 || length > CHUNK_BYTES || length > pcm.length || length % 2 != 0)
      throw new IllegalStateException("Invalid microphone chunk");
    if (cancelled.getAsBoolean()) return;
    if (terminal || !queue.offer(Arrays.copyOf(pcm, length)))
      throw new IllegalStateException("Opus encoder cannot keep up with microphone capture");
  }

  void finish() throws InterruptedException {
    enqueue(END);
  }

  private void enqueue(byte[] pcm) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!cancelled.getAsBoolean() && !terminal) {
      if (queue.offer(pcm, 50, TimeUnit.MILLISECONDS)) return;
      if (System.nanoTime() >= deadline)
        throw new IllegalStateException("Opus capture queue stalled");
    }
    if (!cancelled.getAsBoolean())
      throw new IllegalStateException("Opus encoder ended before capture");
  }

  private void encode() {
    try (Encoder encoder = factory.create()) {
      while (!cancelled.getAsBoolean()) {
        byte[] pcm = queue.poll(100, TimeUnit.MILLISECONDS);
        if (pcm == null) continue;
        if (pcm == END) {
          encoder.finish();
          if (!cancelled.getAsBoolean()) finished.run();
          return;
        }
        encoder.audio(pcm, pcm.length);
      }
    } catch (Exception error) {
      if (!cancelled.getAsBoolean()) failed.accept(error);
    } finally {
      terminal = true;
      queue.clear();
    }
  }

  int queuedChunks() {
    return queue.size();
  }
}
