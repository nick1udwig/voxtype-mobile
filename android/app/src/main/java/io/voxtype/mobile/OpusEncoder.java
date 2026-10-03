package io.voxtype.mobile;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okio.ByteString;

/** One synchronous encoder on the bounded encoding worker; no unbounded PCM or callback queues. */
final class OpusEncoder implements OpusCapture.Encoder {
  interface Driver extends AutoCloseable {
    boolean input(byte[] pcm, int offset, int length, long timestamp, boolean eos);

    Output output(boolean wait);

    @Override
    void close();
  }

  record Output(byte[] data, byte[] delay, boolean config, boolean eos) {}

  interface Clock {
    long millis();
  }

  private final Driver codec;
  private final BooleanSupplier cancelled;
  private final Clock clock;
  private final OpusFrames frames;
  private long submittedSamples;
  private int originalSamples;
  private boolean outputEos, closed;

  OpusEncoder(Consumer<ByteString> sink, BooleanSupplier cancelled) throws IOException {
    this(new Platform(), sink, cancelled, () -> android.os.SystemClock.elapsedRealtime());
  }

  OpusEncoder(Driver codec, Consumer<ByteString> sink, BooleanSupplier cancelled, Clock clock) {
    this.codec = codec;
    this.cancelled = cancelled;
    this.clock = clock;
    frames = new OpusFrames(sink);
  }

  public void audio(byte[] pcm, int length) {
    if (closed
        || length <= 0
        || length > pcm.length
        || length % 2 != 0
        || originalSamples + length / 2 > OpusFrames.MAX_SAMPLES)
      throw new IllegalStateException("Invalid microphone PCM");
    feed(pcm, length, false);
    originalSamples += length / 2;
  }

  private void feed(byte[] pcm, int length, boolean eos) {
    int offset = 0;
    long deadline = clock.millis() + 5000;
    do {
      if (cancelled.getAsBoolean()) throw new IllegalStateException("Recording cancelled");
      // Public driver contract limits each input to 20ms; platform buffers may be larger.
      int count = Math.min(640, length - offset);
      if (codec.input(pcm, offset, count, submittedSamples * 1000000L / 16000, eos)) {
        offset += count;
        submittedSamples += count / 2;
        drain(false);
        deadline = clock.millis() + 5000;
        if (offset >= length) return;
      } else {
        drain(true);
        if (clock.millis() >= deadline)
          throw new IllegalStateException("Opus encoder input stalled");
      }
    } while (offset < length || length == 0);
  }

  private void drain(boolean wait) {
    // Each poll waits at most10ms. Continuous malicious output is bounded by packet count.
    Output output;
    while ((output = codec.output(wait)) != null) {
      wait = false;
      if (cancelled.getAsBoolean()) throw new IllegalStateException("Recording cancelled");
      if (output.config()) frames.configure(output.data(), output.delay());
      else if (output.data().length != 0) frames.packet(output.data());
      if (output.eos()) {
        outputEos = true;
        return;
      }
    }
  }

  public void finish() {
    if (closed) throw new IllegalStateException("Encoder already closed");
    if (originalSamples > 0) {
      // Config has to precede valid encoded output. Wait boundedly for it if the
      // codec delayed its initial output, including sub20ms recordings.
      int alignment = (320 - originalSamples % 320) % 320;
      if (alignment > 0) feed(new byte[alignment * 2], alignment * 2, false);
      long deadline = clock.millis() + 5000;
      while (true) {
        drain(true);
        try {
          frames.skipSamples();
          break;
        } catch (IllegalStateException missing) {
          if (clock.millis() >= deadline) throw missing;
        }
      }
      int required = ((originalSamples + frames.skipSamples() + 319) / 320) * 320;
      int padding = (int) Math.max(0, required - submittedSamples);
      if (padding > 0) feed(new byte[padding * 2], padding * 2, false);
    }
    feed(new byte[0], 0, true);
    long deadline = clock.millis() + 5000;
    while (!outputEos) {
      drain(true);
      if (clock.millis() >= deadline) throw new IllegalStateException("Opus encoder EOS stalled");
    }
    if (cancelled.getAsBoolean()) throw new IllegalStateException("Recording cancelled");
    frames.finish(originalSamples);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      codec.close();
    }
  }

  private static final class Platform implements Driver {
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    Platform() throws IOException {
      codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
      try {
        MediaFormat format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 24000);
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 640);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
      } catch (RuntimeException error) {
        codec.release();
        throw error;
      }
    }

    public boolean input(byte[] pcm, int offset, int length, long timestamp, boolean eos) {
      int index = codec.dequeueInputBuffer(10000);
      if (index < 0) return false;
      ByteBuffer buffer = codec.getInputBuffer(index);
      if (buffer == null || buffer.capacity() < length)
        throw new IllegalStateException("Opus input buffer too small");
      buffer.clear();
      buffer.put(pcm, offset, length);
      codec.queueInputBuffer(
          index, 0, length, timestamp, eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
      return true;
    }

    public Output output(boolean wait) {
      int index = codec.dequeueOutputBuffer(info, wait ? 10000 : 0);
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat format = codec.getOutputFormat();
        if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16000
            || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
          throw new IllegalStateException("Unsupported Opus output format");
        ByteBuffer head = format.getByteBuffer("csd-0");
        ByteBuffer delay = format.getByteBuffer("csd-1");
        return head == null
            ? null
            : new Output(
                OpusFrames.bytes(head),
                delay == null ? null : OpusFrames.bytes(delay),
                true,
                false);
      }
      if (index < 0) return null;
      try {
        if (info.size < 0 || info.size > 256 + OpusFrames.MAX_PACKET)
          throw new IllegalStateException("Oversized Opus output");
        ByteBuffer output = codec.getOutputBuffer(index);
        if (output == null && info.size != 0)
          throw new IllegalStateException("Missing Opus output");
        byte[] bytes = new byte[info.size];
        if (output != null) {
          output.position(info.offset);
          output.limit(info.offset + info.size);
          output.get(bytes);
        }
        return new Output(
            bytes,
            null,
            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0,
            (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0);
      } finally {
        codec.releaseOutputBuffer(index, false);
      }
    }

    public void close() {
      try {
        codec.stop();
      } finally {
        codec.release();
      }
    }
  }
}
