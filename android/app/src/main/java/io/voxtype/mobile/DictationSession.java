package io.voxtype.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/**
 * Retains one bounded recording until delivery or explicit discard; retries never insert previews.
 */
final class DictationSession extends WebSocketListener {
  interface Scheduler {
    long now();

    void after(long millis, Runnable work);
  }

  private static final ScheduledExecutorService TIMER =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "voxtype-recovery");
            thread.setDaemon(true);
            return thread;
          });
  static final Scheduler CLOCK =
      new Scheduler() {
        public long now() {
          return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }

        public void after(long millis, Runnable work) {
          TIMER.schedule(work, millis, TimeUnit.MILLISECONDS);
        }
      };
  static final int MAX_BYTES = 300 * 32000;
  private final DictationClient.Listener listener;
  private final WebSocket.Factory factory;
  private final Scheduler scheduler;
  private final List<ByteString> audio = new ArrayList<>();
  private Request request;
  private WebSocket socket;
  private int bytes, nextFrame, retries;
  private int frameOffset, sentBytes;
  private boolean resumable;
  private String sessionId = newSessionId();

  private static String newSessionId() {
    return java.util.UUID.randomUUID().toString().replace("-", "");
  }

  private int maxSeconds = 300;
  private boolean ready, finished, finishSent, done, paused;
  private long generation, deadline, lastProgress, lastQueue;
  private String lastPartial = "";
  private String lastState = "";

  private void state(String value) {
    if (!value.equals(lastState)) {
      lastState = value;
      listener.state(value);
    }
  }

  DictationSession(
      DictationClient.Listener listener, WebSocket.Factory factory, Scheduler scheduler) {
    this.listener = listener;
    this.factory = factory;
    this.scheduler = scheduler;
  }

  synchronized void start(Request request) {
    this.request = request;
    connect();
  }

  private void connect() {
    if (done || paused) return;
    ready = finishSent = false;
    lastPartial = "";
    nextFrame = 0;
    frameOffset = sentBytes = 0;
    deadline = scheduler.now() + 15000;
    long id = ++generation;
    try {
      socket =
          factory.newWebSocket(
              request.newBuilder().header("X-Voxtype-Session", sessionId).build(), this);
      state(finished ? "Transcribing… reconnecting" : "Connecting…");
      scheduler.after(20, () -> pump(id));
    } catch (Exception e) {
      recover("Cannot connect to Voxtype", true);
    }
  }

  synchronized int maxSeconds() {
    return maxSeconds;
  }

  synchronized boolean canRetry() {
    return paused && !done;
  }

  synchronized int retainedBytes() {
    return bytes;
  }

  synchronized void audio(byte[] chunk) {
    if (done) return;
    if (finished) throw new IllegalStateException("Audio after capture ended");
    if (bytes + chunk.length > MAX_BYTES) {
      pause("Recording limit reached. Tap the check to retry the saved audio.");
      return;
    }
    audio.add(ByteString.of(chunk));
    bytes += chunk.length;
  }

  synchronized void finish() {
    if (done) return;
    finished = true;
    if (!paused) state(ready ? "Transcribing… sending audio" : "Transcribing… reconnecting");
  }

  synchronized void retry() {
    if (!canRetry()) return;
    paused = false;
    retries = 0;
    connect();
  }

  synchronized void retry(Request replacement) {
    if (!canRetry()) return;
    if (!replacement.url().equals(request.url())
        || !java.util.Objects.equals(
            replacement.header("Authorization"), request.header("Authorization"))) {
      sessionId = newSessionId();
    }
    request = replacement;
    retry();
  }

  synchronized void cancel() {
    if (done) return;
    done = true;
    ++generation;
    ready = false;
    WebSocket old = socket;
    socket = null;
    if (old != null) {
      // Flush explicit cancellation, including during the opening handshake.
      // Abruptly closing a resumable socket means "retain for reconnect".
      if (old.send("{\"type\":\"cancel\"}")) {
        old.close(1000, "cancelled");
        scheduler.after(5000, old::cancel);
      } else old.cancel();
    }
    audio.clear();
    bytes = 0;
  }

  private void disconnect() {
    ++generation;
    ready = false;
    WebSocket old = socket;
    socket = null; // Late callbacks from any old attempt are ignored.
    if (old != null) old.cancel();
  }

  private synchronized void pump(long id) {
    if (done || paused || id != generation) return;
    long now = scheduler.now();
    if (!ready) {
      if (now >= deadline) {
        recover("Voxtype did not become ready", true);
        return;
      }
    } else {
      long queued = socket.queueSize();
      if (queued < lastQueue || queued == 0) lastProgress = now;
      lastQueue = queued;
      if (queued > 0 && now - lastProgress >= 15000) {
        recover("Audio upload stalled", true);
        return;
      }
      // Pace replay in small bursts and let TCP backpressure slow uploads without losing audio.
      int sent = 0;
      while (nextFrame < audio.size() && socket.queueSize() < 64000 && sent < 16000) {
        ByteString payload = audio.get(nextFrame).substring(frameOffset);
        ByteString frame =
            resumable
                ? new okio.Buffer().writeLong(sentBytes).write(payload).readByteString()
                : payload;
        if (!socket.send(frame)) {
          recover("Audio connection interrupted", true);
          return;
        }
        ++nextFrame;
        frameOffset = 0;
        sentBytes += payload.size();
        sent += payload.size();
        lastProgress = now;
      }
      // WebSocket messages are FIFO. Queue finish immediately behind the last
      // audio frame; waiting for queueSize()==0 adds an unnecessary polling gap.
      if (finished && nextFrame == audio.size() && !finishSent) {
        if (!socket.send("{\"type\":\"finish\"}")) {
          recover("Connection ended before transcription", true);
          return;
        }
        finishSent = true;
        deadline = now + 180000;
      }
      if (finished) {
        state(
            finishSent && socket.queueSize() == 0
                ? "Transcribing…"
                : "Transcribing… sending audio");
      }
      if (finishSent && now >= deadline) {
        recover("Voxtype did not return a transcript", true);
        return;
      }
    }
    scheduler.after(20, () -> pump(id));
  }

  private void recover(String reason, boolean automatic) {
    if (done || paused) return;
    disconnect();
    if (!automatic || retries >= 8) {
      pause(reason + ". Audio kept on this phone. Tap the check to retry, or X to discard.");
      return;
    }
    long delay = Math.min(8000, 500L << retries++);
    state((finished ? "Transcribing… " : "Recording. ") + "Reconnecting (" + retries + "/8)…");
    long id = generation;
    scheduler.after(
        delay,
        () -> {
          synchronized (DictationSession.this) {
            if (!done && !paused && generation == id) connect();
          }
        });
  }

  private void pause(String message) {
    disconnect();
    paused = true;
    listener.recoverable(message);
  }

  @Override
  public synchronized void onMessage(WebSocket ws, String text) {
    if (done || paused || ws != socket) return;
    try {
      JSONObject event = new JSONObject(text);
      switch (event.getString("type")) {
        case "ready":
          int seconds = event.getInt("max_seconds");
          if (ready
              || event.getInt("protocol") != 1
              || event.getInt("sample_rate") != 16000
              || event.getInt("channels") != 1
              || !event.getString("format").equals("pcm_s16le")
              || seconds < 1
              || seconds > 300) {
            recover("Unsupported server protocol", false);
            return;
          }
          if (bytes > seconds * 32000L) {
            recover("Server recording limit is shorter than the saved audio", false);
            return;
          }
          maxSeconds = seconds;
          resumable = event.optBoolean("resume", false);
          long accepted = resumable ? event.getLong("accepted_bytes") : 0;
          boolean serverFinished = resumable && event.getBoolean("finished");
          if (accepted < 0
              || accepted > bytes
              || accepted % 2 != 0
              || (resumable && !event.getString("framing").equals("offset_pcm_s16le"))
              || (serverFinished && (!finished || accepted != bytes))) {
            recover("Invalid resume checkpoint", false);
            return;
          }
          sentBytes = (int) accepted;
          frameOffset = sentBytes;
          nextFrame = 0;
          while (nextFrame < audio.size() && frameOffset >= audio.get(nextFrame).size()) {
            frameOffset -= audio.get(nextFrame++).size();
          }
          finishSent = serverFinished;
          if (serverFinished) deadline = scheduler.now() + 180000;
          ready = true;
          lastProgress = scheduler.now();
          lastQueue = 0;
          state(finished ? "Transcribing… sending audio" : "Recording");
          break;
        case "partial":
          if (ready) {
            String partial = event.getString("text");
            // Don't throw away expensive, still-progressing inference merely
            // because its total runtime exceeds a fixed final-result deadline.
            if (finishSent && !partial.equals(lastPartial)) deadline = scheduler.now() + 180000;
            lastPartial = partial;
            listener.partial(partial);
          }
          break;
        case "final":
          if (!finishSent) {
            recover("Voxtype returned a transcript before the recording ended", false);
            return;
          }
          String result = event.getString("text");
          done = true;
          ++generation;
          audio.clear();
          bytes = 0;
          listener.result(result); // Exactly once for this user dictation, across all attempts.
          if (resumable) ws.send("{\"type\":\"ack\"}");
          ws.close(1000, "done");
          break;
        case "error":
          String code = event.optString("code", "server_error");
          // Inference failures/expired sessions require a new worker; transport
          // losses retain the ID so an existing worker/result can be resumed.
          if (!code.equals("busy")) sessionId = newSessionId();
          boolean retryable =
              code.equals("busy")
                  || code.equals("transcription_failed")
                  || code.equals("model_unavailable")
                  || code.equals("session_expired")
                  || code.equals("server_error");
          recover("Voxtype: " + code, retryable);
          break;
        default:
          recover("Unknown server response", false);
      }
    } catch (Exception e) {
      recover("Invalid server response", false);
    }
  }

  @Override
  public synchronized void onFailure(WebSocket ws, Throwable error, Response response) {
    if (done || paused || ws != socket) return;
    int code = response == null ? 0 : response.code();
    boolean permanent = code == 401 || code == 403 || code == 404;
    recover(
        code == 401 || code == 403 ? "Pairing token rejected" : "Connection lost (" + code + ")",
        !permanent);
  }

  @Override
  public synchronized void onClosed(WebSocket ws, int code, String reason) {
    if (!done && !paused && ws == socket)
      recover("Connection ended before a transcript arrived", true);
  }

  @Override
  public synchronized void onClosing(WebSocket ws, int code, String reason) {
    if (!done && !paused && ws == socket) {
      ws.close(code, reason);
      recover("Connection ended before a transcript arrived", true);
    }
  }
}
