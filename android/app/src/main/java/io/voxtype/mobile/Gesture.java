package io.voxtype.mobile;

/** Screen-space motion distinguishes repositioning from tap/hold dictation. */
final class Gesture {
  enum Action {
    NONE,
    START,
    REVEAL,
    FINISH,
    CANCEL
  }

  private final float slopSquared;
  private final long holdDelay;
  private boolean tracking, started, confirmed, finishOnTap, dragging;
  private long downAt;
  private float downX, downY;

  Gesture(float touchSlop, long holdDelay) {
    slopSquared = touchSlop * touchSlop;
    this.holdDelay = holdDelay;
  }

  Action down(long at, float x, float y, boolean active, boolean waiting) {
    tracking = true;
    dragging = false;
    downAt = at;
    downX = x;
    downY = y;
    started = !active && !waiting;
    confirmed = false;
    finishOnTap = active && !waiting;
    // Capture on down to preserve the beginning of speech. Presentation waits
    // for a stationary hold or tap release; dragging discards this capture.
    return started ? Action.START : Action.NONE;
  }

  Action hold(long at) {
    if (!provisional() || at - downAt < holdDelay) return Action.NONE;
    confirmed = true;
    return Action.REVEAL;
  }

  boolean provisional() {
    return tracking && started && !confirmed;
  }

  Action move(float x, float y) {
    float dx = x - downX, dy = y - downY;
    if (!tracking || dragging || dx * dx + dy * dy <= slopSquared) return Action.NONE;
    dragging = true;
    boolean cancelRecording = started;
    started = confirmed = false;
    return cancelRecording ? Action.CANCEL : Action.NONE;
  }

  boolean dragging() {
    return dragging;
  }

  float deltaX(float x) {
    return x - downX;
  }

  float deltaY(float y) {
    return y - downY;
  }

  Action up(long at) {
    Action action = Action.NONE;
    if (tracking && !dragging) {
      // Keep the original 300 ms hold-to-finish boundary. Delaying the controls
      // must not make a short dictation wait for the presentation callback.
      if (finishOnTap || (started && (confirmed || at - downAt >= 300))) action = Action.FINISH;
      else if (started) action = Action.REVEAL;
    }
    tracking = started = confirmed = finishOnTap = false;
    return action;
  }

  Action cancel() {
    boolean cancel = tracking && started;
    tracking = started = confirmed = finishOnTap = false;
    return cancel ? Action.CANCEL : Action.NONE;
  }

  Action abandon() {
    // Screen lock/rotation ends the pointer, not its recording. Make the
    // controls available on return instead of leaving the session hidden.
    boolean reveal = provisional();
    tracking = started = confirmed = finishOnTap = dragging = false;
    return reveal ? Action.REVEAL : Action.NONE;
  }
}
