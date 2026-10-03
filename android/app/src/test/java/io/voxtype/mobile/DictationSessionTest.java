package io.voxtype.mobile;

import static org.junit.Assert.*;

import java.util.*;
import okhttp3.*;
import okio.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DictationSessionTest {
  static final String READY =
      "{\"type\":\"ready\",\"protocol\":2,\"sample_rate\":16000,\"channels\":1,\"format\":\"opus\",\"max_seconds\":300,\"resume\":true,\"audio_encoding\":\"opus_v1\",\"max_encoded_bytes\":2097152,\"framing\":\"sequence_opus_v1\",\"accepted_frames\":%d,\"finished\":%s}";

  static final class Clock implements DictationSession.Scheduler {
    record Job(long at, long order, Runnable work) {}

    long time, sequence;
    PriorityQueue<Job> jobs =
        new PriorityQueue<>(Comparator.comparingLong(Job::at).thenComparingLong(Job::order));

    public long now() {
      return time;
    }

    public void after(long delay, Runnable work) {
      jobs.add(new Job(time + delay, sequence++, work));
    }

    void advance(long millis) {
      long end = time + millis;
      while (!jobs.isEmpty() && jobs.peek().at <= end) {
        Job job = jobs.remove();
        time = job.at;
        job.work.run();
      }
      time = end;
    }
  }

  static final class Socket implements WebSocket {
    final Request request;
    final List<ByteString> frames = new ArrayList<>();
    int finishes;
    final List<String> controls = new ArrayList<>();
    long queued;
    boolean cancelled, reject;

    Socket(Request request) {
      this.request = request;
    }

    public Request request() {
      return request;
    }

    public long queueSize() {
      return queued;
    }

    public boolean send(ByteString bytes) {
      if (reject) return false;
      frames.add(bytes);
      return true;
    }

    public boolean send(String text) {
      if (reject) return false;
      controls.add(text);
      if (text.contains("finish")) finishes++;
      return true;
    }

    public boolean close(int code, String reason) {
      return true;
    }

    public void cancel() {
      cancelled = true;
    }
  }

  static final class Listener implements DictationClient.Listener {
    final List<String> results = new ArrayList<>(), recoverable = new ArrayList<>();

    public void state(String text) {}

    public void partial(String text) {}

    public void level(float level) {}

    public void error(String text) {
      fail(text);
    }

    public void recoverable(String text) {
      recoverable.add(text);
    }

    public void result(String text) {
      results.add(text);
    }
  }

  final Clock clock = new Clock();
  final Listener listener = new Listener();
  final List<Socket> sockets = new ArrayList<>();
  final DictationSession session =
      new DictationSession(
          listener,
          (request, callback) -> {
            Socket socket = new Socket(request);
            sockets.add(socket);
            return socket;
          },
          clock);

  void start() {
    session.start(new Request.Builder().url("https://example.invalid/voxtype/v1/dictate").build());
  }

  Socket current() {
    return sockets.get(sockets.size() - 1);
  }

  void ready() {
    resumeReady(0, false);
  }

  void resumeReady(long accepted, boolean finished) {
    session.onMessage(current(), String.format(java.util.Locale.ROOT, READY, accepted, finished));
  }

  void lost(Socket socket) {
    session.onFailure(socket, new java.io.IOException("disconnected"), null);
  }

  void finalText(Socket socket) {
    session.onMessage(socket, "{\"type\":\"final\",\"text\":\"hello\"}");
  }

  ByteString frame(int sequence, boolean last) {
    return new okio.Buffer()
        .writeLong(sequence)
        .writeByte(1)
        .writeByte((sequence == 0 ? 1 : 0) | (last ? 2 : 0))
        .writeShort(sequence == 0 ? 312 : 0)
        .writeInt(last ? 320 : 0)
        .writeByte(1)
        .writeShort(3)
        .write(new byte[] {(byte) 0xf8, (byte) 0xff, (byte) 0xfe})
        .readByteString();
  }

  void saved() {
    session.audio(frame(0, true));
  }

  @Test
  public void requiresOpusAndSendsCompleteOriginalFrames() {
    start();
    saved();
    ready();
    session.finish();
    clock.advance(20);
    assertEquals("2", current().request.header("X-Voxtype-Protocol"));
    assertEquals("opus_v1", current().request.header("X-Voxtype-Audio"));
    assertEquals(List.of(frame(0, true)), current().frames);
    assertEquals(1, current().finishes);
    finalText(current());
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
    assertEquals(0, session.retainedBytes());
    assertTrue(current().controls.contains("{\"type\":\"ack\"}"));
  }

  @Test
  public void reconnectUsesFrameCheckpointAndKeepsOfflineSpeech() {
    start();
    session.audio(frame(0, false));
    ready();
    clock.advance(20);
    String id = current().request.header("X-Voxtype-Session");
    lost(current());
    session.audio(frame(1, true));
    session.finish();
    clock.advance(500);
    resumeReady(1, false);
    clock.advance(20);
    assertEquals(id, current().request.header("X-Voxtype-Session"));
    assertEquals(List.of(frame(1, true)), current().frames);
    assertEquals(44, session.retainedBytes());
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void restartReplaysEncodedBytesFromTrueBeginning() {
    start();
    saved();
    ready();
    clock.advance(20);
    Socket old = current();
    lost(old);
    clock.advance(500);
    ready();
    session.finish();
    clock.advance(20);
    assertEquals(old.frames, current().frames);
    finalText(old);
    assertTrue(listener.results.isEmpty());
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void cachedFinalRequiresFinishedCheckpointAndInsertsOnce() {
    start();
    saved();
    session.finish();
    ready();
    clock.advance(20);
    lost(current());
    clock.advance(500);
    resumeReady(1, true);
    finalText(current());
    finalText(current());
    assertTrue(current().frames.isEmpty());
    assertEquals(0, current().finishes);
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void invalidCheckpointOrPrematureFinalKeepsAudio() {
    start();
    saved();
    resumeReady(2, false);
    assertTrue(session.canRetry());
    assertEquals(22, session.retainedBytes());
    session.retry();
    resumeReady(1, true);
    assertTrue(session.canRetry());
    assertTrue(listener.results.isEmpty());
    session.retry();
    ready();
    finalText(current());
    assertTrue(session.canRetry());
    assertTrue(listener.results.isEmpty());
  }

  @Test
  public void legacyDaemonFailsClearlyWithoutUploadOrAutomaticRetries() {
    start();
    saved();
    session.onMessage(
        current(),
        "{\"type\":\"ready\",\"protocol\":1,\"sample_rate\":16000,\"channels\":1,\"format\":\"pcm_s16le\",\"max_seconds\":300}");
    clock.advance(60000);
    assertEquals(1, sockets.size());
    assertTrue(current().frames.isEmpty());
    assertTrue(session.canRetry());
    assertTrue(
        listener.recoverable.get(0).contains("upgrade")
            || listener.recoverable.get(0).contains("Upgrade"));
  }

  @Test
  public void http426AndAuthenticationErrorsArePermanentAndRetainAudio() {
    start();
    saved();
    for (int code : new int[] {426, 401, 403}) {
      if (session.canRetry()) session.retry();
      int attempts = sockets.size();
      Response response =
          new Response.Builder()
              .request(current().request)
              .protocol(Protocol.HTTP_1_1)
              .code(code)
              .message("Rejected")
              .build();
      session.onFailure(current(), new java.io.IOException(), response);
      clock.advance(60000);
      assertEquals(attempts, sockets.size());
      assertTrue(session.canRetry());
      assertEquals(22, session.retainedBytes());
    }
  }

  @Test
  public void manualRetryUsesUpdatedPairingAndFreshSessionId() {
    start();
    saved();
    String id = current().request.header("X-Voxtype-Session");
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"unauthorized\"}");
    session.retry(
        new Request.Builder()
            .url("https://replacement.invalid/voxtype/v1/dictate")
            .header("Authorization", "Bearer replacement")
            .build());
    assertEquals("replacement.invalid", current().request.url().host());
    assertEquals("Bearer replacement", current().request.header("Authorization"));
    assertNotEquals(id, current().request.header("X-Voxtype-Session"));
    ready();
    session.finish();
    clock.advance(20);
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void silentHandshakeAndStalledUploadTriggerBoundedRetries() {
    start();
    saved();
    clock.advance(15500);
    assertEquals(2, sockets.size());
    ready();
    current().queued = 64000;
    session.finish();
    clock.advance(16000);
    assertEquals(3, sockets.size());
    assertEquals(22, session.retainedBytes());
  }

  @Test
  public void explicitCancelBeforeReadySendsControlAndInvalidatesPump() {
    start();
    saved();
    session.cancel();
    clock.advance(60000);
    assertTrue(current().controls.contains("{\"type\":\"cancel\"}"));
    assertTrue(current().cancelled);
    assertEquals(1, sockets.size());
    assertEquals(0, session.retainedBytes());
  }

  @Test
  public void finishQueuesBehindLastFrameWithoutWaitingForEmptySocket() {
    start();
    ready();
    saved();
    current().queued = 100;
    session.finish();
    clock.advance(20);
    assertEquals(1, current().frames.size());
    assertEquals(1, current().finishes);
  }

  @Test
  public void inferenceFailureChangesSessionIdButBusyKeepsIt() {
    start();
    ready();
    String id = current().request.header("X-Voxtype-Session");
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"busy\"}");
    clock.advance(500);
    assertEquals(id, current().request.header("X-Voxtype-Session"));
    ready();
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"transcription_failed\"}");
    clock.advance(1000);
    assertNotEquals(id, current().request.header("X-Voxtype-Session"));
  }

  @Test
  public void backpressureDoesNotLoseFramesOrSendFinishEarly() {
    start();
    ready();
    current().queued = 64000;
    for (int i = 0; i < 100; i++) session.audio(frame(i, i == 99));
    session.finish();
    clock.advance(100);
    assertTrue(current().frames.isEmpty());
    assertEquals(0, current().finishes);
    current().queued = 0;
    clock.advance(100);
    assertEquals(100, current().frames.size());
    assertEquals(1, current().finishes);
  }

  @Test
  public void progressingPartialExtendsFinalDeadlineButIdenticalPartialDoesNot() {
    start();
    saved();
    ready();
    session.finish();
    clock.advance(20);
    clock.advance(170000);
    session.onMessage(current(), "{\"type\":\"partial\",\"text\":\"progress\"}");
    clock.advance(20000);
    assertEquals(1, sockets.size());
    session.onMessage(current(), "{\"type\":\"partial\",\"text\":\"progress\"}");
    clock.advance(160500);
    assertEquals(2, sockets.size());
  }

  @Test
  public void cancelledSessionDiscardsAudioAndIgnoresLateCallbacks() {
    start();
    saved();
    lost(current());
    session.cancel();
    clock.advance(600000);
    finalText(current());
    assertTrue(session.isCancelled());
    assertEquals(1, sockets.size());
    assertEquals(0, session.retainedBytes());
    assertTrue(listener.results.isEmpty());
  }

  @Test
  public void retryBudgetRetainsEncodedRecordingForManualReplay() {
    start();
    saved();
    for (int i = 0; i < 9; i++) {
      lost(current());
      clock.advance(8000);
    }
    assertTrue(session.canRetry());
    assertEquals(22, session.retainedBytes());
    session.retry();
    ready();
    session.finish();
    clock.advance(20);
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void retainedEncodedRecordingHasHardByteAndFrameBounds() {
    start();
    session.audio(ByteString.of(new byte[DictationSession.MAX_BYTES]));
    assertThrows(IllegalStateException.class, () -> session.audio(frame(0, true)));
    assertEquals(DictationSession.MAX_BYTES, session.retainedBytes());
    session.cancel();
    assertEquals(0, session.retainedBytes());
  }
}
