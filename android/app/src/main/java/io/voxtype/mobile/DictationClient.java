package io.voxtype.mobile;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** One microphone recording, retained across recoverable transport/inference failures. */
final class DictationClient {
  interface Listener {
    void state(String state);

    void partial(String text);

    void level(float level);

    void result(String text);

    void error(String text);

    void recoverable(String text);
  }

  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.SECONDS)
          .pingInterval(5, TimeUnit.SECONDS)
          .build();
  private final Listener listener;
  private final DictationSession session;
  private volatile boolean recording = true;
  private long capturedBytes;

  DictationClient(Listener listener) {
    this.listener = listener;
    session =
        new DictationSession(
            new Listener() {
              public void state(String text) {
                listener.state(text);
              }

              public void partial(String text) {
                listener.partial(text);
              }

              public void level(float level) {
                listener.level(level);
              }

              public void result(String text) {
                recording = false;
                listener.result(text);
              }

              public void error(String text) {
                recording = false;
                listener.error(text);
              }

              public void recoverable(String text) {
                recording = false;
                listener.recoverable(text);
              }
            },
            HTTP,
            DictationSession.CLOCK);
  }

  void start(String url, String token) {
    session.start(
        new Request.Builder().url(url).header("Authorization", "Bearer " + token).build());
    new Thread(this::capture, "voxtype-microphone").start();
  }

  private void capture() {
    AudioRecord recorder = null;
    try {
      int size =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (size <= 0)
        throw new IllegalStateException("16 kHz recording is unavailable on this phone");
      recorder =
          new AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              16000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(size, 6400));
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IllegalStateException("Microphone could not initialize");
      if (recording) recorder.startRecording();
      byte[] chunk = new byte[3200]; // 100 ms, s16le mono
      while (recording) {
        int n = recorder.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
        if (n <= 0) {
          if (recording) throw new IllegalStateException("Microphone read failed");
          break;
        }
        android.media.AudioRecordingConfiguration configuration =
            recorder.getActiveRecordingConfiguration();
        if (configuration != null && configuration.isClientSilenced()) {
          throw new IllegalStateException(
              "Android has silenced the microphone. Check other recording apps and microphone"
                  + " privacy settings.");
        }
        capturedBytes += n;
        if (capturedBytes > session.maxSeconds() * 32000L) {
          recording = false;
          break;
        }
        listener.level(AudioLevel.pcm16(chunk, n));
        session.audio(java.util.Arrays.copyOf(chunk, n));
      }
    } catch (SecurityException e) {
      fail("Microphone permission was revoked. Allow it in Android Settings.");
    } catch (Exception e) {
      fail("Microphone: " + e.getMessage());
    } finally {
      if (recorder != null) {
        try {
          recorder.stop();
        } catch (Exception ignored) {
        }
        recorder.release();
      }
      session.finish();
    }
  }

  void finish() {
    recording = false;
    listener.state("Transcribing…");
  }

  boolean canRetry() {
    return session.canRetry();
  }

  void retry(String url, String token) {
    session.retry(
        new Request.Builder().url(url).header("Authorization", "Bearer " + token).build());
  }

  void cancel() {
    recording = false;
    session.cancel();
  }

  private void fail(String error) {
    cancel();
    listener.error(error);
  }
}
