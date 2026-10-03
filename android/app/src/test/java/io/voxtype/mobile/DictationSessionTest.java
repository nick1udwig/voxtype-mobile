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
      "{\"type\":\"ready\",\"protocol\":1,\"sample_rate\":16000,\"channels\":1,\"format\":\"pcm_s16le\",\"max_seconds\":300}";

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
    session.onMessage(current(), READY);
  }

  void lost(Socket socket) {
    session.onFailure(socket, new java.io.IOException("test disconnect"), null);
  }

  void finalText(Socket socket) {
    session.onMessage(socket, "{\"type\":\"final\",\"text\":\"hello\"}");
  }

  void resumeReady(long accepted, boolean finished) {
    session.onMessage(
        current(),
        READY.substring(0, READY.length() - 1)
            + ",\"resume\":true,\"accepted_bytes\":"
            + accepted
            + ",\"finished\":"
            + finished
            + ",\"framing\":\"offset_pcm_s16le\"}");
  }

  ByteString framed(long offset, byte... bytes) {
    return new okio.Buffer().writeLong(offset).write(bytes).readByteString();
  }

  void compressedReady(long accepted) {
    session.onMessage(
        current(),
        READY.substring(0, READY.length() - 1)
            + ",\"resume\":true,\"accepted_bytes\":"
            + accepted
            + ",\"finished\":false,\"framing\":\"offset_pcm_s16le\",\"audio_encoding\":\""
            + LosslessPcm.ENCODING
            + "\"}");
  }

  @Test
  public void compressionIsNegotiatedAndResumeCanSwitchToOldServerInsideFrame() throws Exception {
    start();
    assertEquals(LosslessPcm.ENCODING, current().request().header("X-Voxtype-Audio"));
    byte[] pcm = new byte[3200];
    pcm[3199] = 1;
    session.audio(pcm);
    compressedReady(0);
    clock.advance(20);
    okio.Buffer frame = new okio.Buffer().write(current().frames.get(0));
    assertEquals(0, frame.readLong());
    assertEquals(ByteString.of(pcm), LosslessPcmTest.decode(frame.readByteString()));
    lost(current());
    clock.advance(500);
    resumeReady(1600, false);
    clock.advance(20);
    assertEquals(
        new okio.Buffer().writeLong(1600).write(pcm, 1600, 1600).readByteString(),
        current().frames.get(0));
    assertEquals(3200, session.retainedBytes());
  }

  @Test
  public void compressedResumeReencodesOnlyRemainingDecodedPcm() throws Exception {
    start();
    byte[] pcm = new byte[3200];
    pcm[3199] = 1;
    session.audio(pcm);
    resumeReady(0, false);
    clock.advance(20);
    lost(current());
    clock.advance(500);
    compressedReady(1600);
    clock.advance(20);
    okio.Buffer frame = new okio.Buffer().write(current().frames.get(0));
    assertEquals(1600, frame.readLong());
    assertEquals(ByteString.of(pcm, 1600, 1600), LosslessPcmTest.decode(frame.readByteString()));
  }

  @Test
  public void noAcknowledgmentFallsBackToLegacyRawAndUnknownEncodingRetainsAudio() {
    start();
    session.audio(new byte[] {1, 2});
    ready();
    clock.advance(20);
    assertEquals(List.of(ByteString.of((byte) 1, (byte) 2)), current().frames);
    lost(current());
    clock.advance(500);
    session.onMessage(
        current(), READY.substring(0, READY.length() - 1) + ",\"audio_encoding\":\"unknown\"}");
    assertTrue(session.canRetry());
    assertEquals(2, session.retainedBytes());
    assertTrue(current().frames.isEmpty());
  }

  @Test
  public void resumeSkipsAcceptedAudioAndHandlesCheckpointInsideFrame() {
    start();
    session.audio(new byte[] {1, 2, 3, 4});
    session.audio(new byte[] {5, 6});
    resumeReady(0, false);
    clock.advance(20);
    String id = current().request().header("X-Voxtype-Session");
    lost(current());
    clock.advance(500);
    resumeReady(2, false);
    clock.advance(20);
    assertEquals(id, current().request().header("X-Voxtype-Session"));
    assertEquals(
        List.of(framed(2, (byte) 3, (byte) 4), framed(4, (byte) 5, (byte) 6)), current().frames);
    assertEquals(6, session.retainedBytes());
  }

  @Test
  public void cachedFinalIsAcceptedWithoutUploadOrAnotherFinishAndAcknowledgedOnce() {
    start();
    session.audio(new byte[] {1, 2});
    session.finish();
    resumeReady(0, false);
    clock.advance(20);
    Socket old = current();
    lost(old);
    clock.advance(500);
    resumeReady(2, true);
    finalText(old);
    finalText(current());
    finalText(current());
    clock.advance(100);
    assertTrue(current().frames.isEmpty());
    assertEquals(0, current().finishes);
    assertEquals(List.of("hello"), listener.results);
    assertEquals(List.of("{\"type\":\"ack\"}"), current().controls);
    assertEquals(0, session.retainedBytes());
  }

  @Test
  public void serverRestartCanRequestFullReplayWithoutLosingSpeech() {
    start();
    session.audio(new byte[] {1, 2});
    resumeReady(0, false);
    clock.advance(20);
    lost(current());
    session.audio(new byte[] {3, 4});
    clock.advance(500);
    resumeReady(0, false);
    clock.advance(20);
    assertEquals(
        List.of(framed(0, (byte) 1, (byte) 2), framed(2, (byte) 3, (byte) 4)), current().frames);
  }

  @Test
  public void invalidResumeCheckpointPausesWithoutInsertingOrDiscarding() {
    start();
    session.audio(new byte[] {1, 2});
    resumeReady(4, false);
    assertTrue(session.canRetry());
    assertEquals(2, session.retainedBytes());
    assertTrue(listener.results.isEmpty());
  }

  @Test
  public void serverCannotClaimFinishedWhilePhoneStillRecording() {
    start();
    session.audio(new byte[] {1, 2});
    resumeReady(2, true);
    assertTrue(session.canRetry());
    assertTrue(listener.results.isEmpty());
  }

  @Test
  public void inferenceFailureUsesFreshSessionIdButConnectionFailureKeepsIt() {
    start();
    String first = current().request().header("X-Voxtype-Session");
    resumeReady(0, false);
    lost(current());
    clock.advance(500);
    assertEquals(first, current().request().header("X-Voxtype-Session"));
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"transcription_failed\"}");
    clock.advance(1000);
    assertNotEquals(first, current().request().header("X-Voxtype-Session"));
  }

  @Test
  public void explicitCancelIsSentEvenBeforeReadyAndTerminatesRetries() {
    start();
    Socket old = current();
    session.cancel();
    assertEquals(List.of("{\"type\":\"cancel\"}"), old.controls);
    clock.advance(5000);
    assertTrue(old.cancelled);
    assertEquals(1, sockets.size());
  }

  @Test
  public void reconnectReplaysWholeAudioIncludingSpeechCapturedOffline() {
    start();
    ready();
    session.audio(new byte[] {1, 2});
    clock.advance(20);
    Socket old = current();
    lost(old);
    session.audio(new byte[] {3, 4});
    clock.advance(500);
    ready();
    clock.advance(20);
    assertTrue(old.cancelled);
    assertEquals(
        List.of(ByteString.of((byte) 1, (byte) 2), ByteString.of((byte) 3, (byte) 4)),
        current().frames);
    assertEquals(4, session.retainedBytes());
    assertFalse(session.canRetry());
  }

  @Test
  public void failureAfterFinishIgnoresOldResultsAndDeliversOnlyOnce() {
    start();
    ready();
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(20);
    Socket old = current();
    lost(old);
    clock.advance(500);
    ready();
    clock.advance(20);
    finalText(old);
    assertTrue(listener.results.isEmpty());
    finalText(current());
    finalText(current());
    lost(current());
    clock.advance(10000);
    assertEquals(List.of("hello"), listener.results);
    assertEquals(0, session.retainedBytes());
    assertEquals(2, sockets.size());
    assertEquals(1, current().finishes);
  }

  @Test
  public void inferenceFailureRetriesAndBusyWaitsForOldWorkerToReleaseModel() {
    start();
    ready();
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(20);
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"transcription_failed\"}");
    clock.advance(500);
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"busy\"}");
    clock.advance(1000);
    ready();
    clock.advance(20);
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
    assertTrue(listener.recoverable.isEmpty());
  }

  @Test
  public void exhaustedRetriesKeepAudioForManualRetry() {
    start();
    session.audio(new byte[] {1, 2});
    session.finish();
    for (int i = 0; i < 9; ++i) {
      lost(current());
      if (i < 8) clock.advance(Math.min(8000, 500L << i));
    }
    assertTrue(session.canRetry());
    assertEquals(2, session.retainedBytes());
    assertEquals(1, listener.recoverable.size());
    int before = sockets.size();
    clock.advance(600000);
    assertEquals(before, sockets.size());
    session.retry();
    ready();
    clock.advance(20);
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void authenticationFailureDoesNotLoopOrDiscardAudio() {
    start();
    session.audio(new byte[] {1, 2});
    Response response =
        new Response.Builder()
            .request(current().request())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build();
    session.onFailure(current(), new Exception(), response);
    clock.advance(60000);
    assertEquals(1, sockets.size());
    assertTrue(session.canRetry());
    assertEquals(2, session.retainedBytes());
  }

  @Test
  public void manualRetryUsesUpdatedPairingSettings() {
    start();
    session.audio(new byte[] {1, 2});
    session.onMessage(current(), "{\"type\":\"error\",\"code\":\"unsupported_protocol\"}");
    assertTrue(session.canRetry());
    Request replacement =
        new Request.Builder()
            .url("https://new.example.invalid/voxtype/v1/dictate")
            .header("Authorization", "Bearer replacement")
            .build();
    session.finish();
    session.retry(replacement);
    ready();
    clock.advance(20);
    finalText(current());
    assertEquals("new.example.invalid", current().request().url().host());
    assertEquals("Bearer replacement", current().request().header("Authorization"));
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void silentHandshakeAndFinalTimeoutsRetry() {
    start();
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(15500);
    assertEquals(2, sockets.size());
    ready();
    clock.advance(20);
    clock.advance(181000);
    assertEquals(3, sockets.size());
    assertEquals(2, session.retainedBytes());
  }

  @Test
  public void slowUploadPausesSendingAndFinishesOnlyAfterEveryFrameDrains() {
    start();
    ready();
    current().queued = 64000;
    for (int i = 0; i < 100; i++) session.audio(new byte[3200]);
    session.finish();
    clock.advance(1000);
    assertTrue(current().frames.isEmpty());
    assertEquals(0, current().finishes);
    current().queued = 0;
    clock.advance(500);
    assertEquals(100, current().frames.size());
    assertEquals(1, current().finishes);
    assertTrue(listener.recoverable.isEmpty());
  }

  @Test
  public void finishQueuesBehindAudioWithoutWaitingForSocketToDrain() {
    start();
    ready();
    current().queued = 3200;
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(20);
    assertEquals(1, current().frames.size());
    assertEquals(1, current().finishes);
    assertTrue(listener.results.isEmpty());
  }

  @Test
  public void progressingTranscriptionDoesNotRestartAtOriginalDeadline() {
    start();
    ready();
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(20);
    clock.advance(170000);
    session.onMessage(current(), "{\"type\":\"partial\",\"text\":\"progress\"}");
    clock.advance(20000);
    assertEquals(1, sockets.size());
    finalText(current());
    assertEquals(List.of("hello"), listener.results);
  }

  @Test
  public void repeatedIdenticalPartialCannotKeepStalledInferenceAliveForever() {
    start();
    ready();
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(20);
    session.onMessage(current(), "{\"type\":\"partial\",\"text\":\"same\"}");
    clock.advance(170000);
    session.onMessage(current(), "{\"type\":\"partial\",\"text\":\"same\"}");
    clock.advance(10500);
    assertEquals(2, sockets.size());
    assertEquals(2, session.retainedBytes());
  }

  @Test
  public void stalledUploadRetriesWithoutWaitingAMinute() {
    start();
    ready();
    current().queued = 64000;
    session.audio(new byte[] {1, 2});
    session.finish();
    clock.advance(15500);
    assertEquals(2, sockets.size());
    assertEquals(2, session.retainedBytes());
  }

  @Test
  public void cancelDiscardsAudioAndInvalidatesPendingReconnect() {
    start();
    session.audio(new byte[] {1, 2});
    lost(current());
    session.cancel();
    clock.advance(600000);
    finalText(current());
    assertEquals(1, sockets.size());
    assertEquals(0, session.retainedBytes());
    assertTrue(listener.results.isEmpty());
  }

  @Test
  public void retainedAudioHasHardFiveMinuteBound() {
    start();
    session.audio(new byte[DictationSession.MAX_BYTES]);
    session.audio(new byte[2]);
    assertTrue(session.canRetry());
    assertEquals(DictationSession.MAX_BYTES, session.retainedBytes());
    session.cancel();
    assertEquals(0, session.retainedBytes());
  }

  @Test
  public void prematureFinalDoesNotInsertAnIncompleteTranscript() {
    start();
    ready();
    session.audio(new byte[] {1, 2});
    finalText(current());
    assertTrue(listener.results.isEmpty());
    assertTrue(session.canRetry());
    assertEquals(2, session.retainedBytes());
  }
}
