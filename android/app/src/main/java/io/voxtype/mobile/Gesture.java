package io.voxtype.mobile;

/** Screen-space motion distinguishes repositioning from tap/hold dictation. */
final class Gesture {
  enum Action {
    NONE,
    START,
    FINISH,
    CANCEL
  }

  private final float slopSquared;
  private boolean tracking, started, finishOnTap, dragging;
  private long downAt;
  private float downX, downY;

  Gesture(float touchSlop) {
    slopSquared = touchSlop * touchSlop;
  }

  Action down(long at, float x, float y, boolean active, boolean waiting) {
    tracking = true;
    dragging = false;
    downAt = at;
    downX = x;
    downY = y;
    started = !active && !waiting;
    finishOnTap = active && !waiting;
    // Capture immediately to preserve the beginning of speech. Drag discards it.
    // An existing session can only finish on UP, once dragging has been ruled out.
    return started ? Action.START : Action.NONE;
  }

  Action move(float x, float y) {
    float dx = x - downX, dy = y - downY;
    if (!tracking || dragging || dx * dx + dy * dy <= slopSquared) return Action.NONE;
    dragging = true;
    boolean cancelProvisionalRecording = started;
    started = false;
    return cancelProvisionalRecording ? Action.CANCEL : Action.NONE;
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
    boolean finish = tracking && !dragging && (finishOnTap || (started && at - downAt >= 300));
    tracking = started = finishOnTap = false;
    return finish ? Action.FINISH : Action.NONE;
  }

  Action cancel() {
    boolean cancel = tracking && started;
    tracking = started = finishOnTap = false;
    return cancel ? Action.CANCEL : Action.NONE;
  }
}
