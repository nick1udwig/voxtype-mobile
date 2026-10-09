package io.voxtype.mobile;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class DictationButtonTest {
  static final class Controller implements DictationButton.Controller {
    boolean active, waiting, moved;
    int toggles, ended;
    float dx, dy;
    final List<Gesture.Action> actions = new ArrayList<>();

    public boolean active() {
      return active;
    }

    public boolean waiting() {
      return waiting;
    }

    public void act(Gesture.Action action) {
      if (action == Gesture.Action.NONE) return;
      actions.add(action);
      if (action == Gesture.Action.START) active = true;
      if (action == Gesture.Action.CANCEL) active = waiting = false;
      if (action == Gesture.Action.FINISH) waiting = true;
    }

    public void toggle() {
      toggles++;
    }

    public void beginTouch() {}

    public void drag(float x, float y) {
      dx = x;
      dy = y;
    }

    public void endTouch(boolean dragged) {
      ended++;
      moved = dragged;
    }
  }

  private final Controller controller = new Controller();
  private final DictationButton button =
      new DictationButton(RuntimeEnvironment.getApplication(), controller);
  private final float slop =
      ViewConfiguration.get(RuntimeEnvironment.getApplication()).getScaledTouchSlop();
  private final long holdDelay = ViewConfiguration.getLongPressTimeout();
  private long downAt;

  private void touch(int action, float x, float y) {
    long at = SystemClock.uptimeMillis();
    if (action == MotionEvent.ACTION_DOWN) downAt = at;
    MotionEvent event = MotionEvent.obtain(downAt, at, action, x, y, 0);
    button.onTouchEvent(event);
    event.recycle();
  }

  private void advance(long millis) {
    shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
  }

  @Test
  public void dragDiscardsImmediateCaptureWithoutRevealingControlsOrClicking() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    assertEquals(List.of(Gesture.Action.START), controller.actions);
    assertTrue(controller.active);
    assertFalse(button.recordingVisible());
    advance(holdDelay - 1);
    touch(MotionEvent.ACTION_MOVE, 100 + slop + 10, 100);
    advance(1000);
    touch(MotionEvent.ACTION_UP, 200, 300);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.CANCEL), controller.actions);
    assertFalse(controller.active);
    assertFalse(button.recordingVisible());
    assertEquals(100, controller.dx, 0);
    assertEquals(200, controller.dy, 0);
    assertTrue(controller.moved);
    assertEquals(0, controller.toggles);
  }

  @Test
  public void movementUsesRawCoordinatesWhenWindowMovesUnderFinger() {
    controller.active = true;
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    MotionEvent event =
        MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_MOVE, 140 + slop, 160, 0);
    event.offsetLocation(-200, -300);
    button.onTouchEvent(event);
    event.recycle();
    assertEquals(40 + slop, controller.dx, .001);
    assertEquals(60, controller.dy, .001);
    advance(holdDelay + 100);
    touch(MotionEvent.ACTION_UP, 140 + slop, 160);
    assertTrue(controller.active);
    assertTrue(controller.actions.isEmpty());
  }

  @Test
  public void stationaryHoldCapturesImmediatelyRevealsAfterDelayAndFinishesOnRelease() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    assertEquals(List.of(Gesture.Action.START), controller.actions);
    assertTrue(controller.active);
    assertFalse(button.recordingVisible());
    advance(holdDelay - 1);
    touch(MotionEvent.ACTION_MOVE, 100 + slop / 2, 100);
    assertEquals(List.of(Gesture.Action.START), controller.actions);
    assertFalse(button.recordingVisible());
    advance(1);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.REVEAL), controller.actions);
    assertTrue(button.recordingVisible());
    advance(1000);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(
        List.of(Gesture.Action.START, Gesture.Action.REVEAL, Gesture.Action.FINISH),
        controller.actions);
    assertFalse(controller.moved);
    assertEquals(0, controller.toggles);
  }

  @Test
  public void tapCapturesOnDownRevealsOnReleaseAndSecondTapFinishesOnRelease() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(100);
    assertEquals(List.of(Gesture.Action.START), controller.actions);
    assertFalse(button.recordingVisible());
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertTrue(button.recordingVisible());
    advance(holdDelay);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.REVEAL), controller.actions);
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.REVEAL), controller.actions);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(
        List.of(Gesture.Action.START, Gesture.Action.REVEAL, Gesture.Action.FINISH),
        controller.actions);
    assertEquals(0, controller.toggles);
  }

  @Test
  public void shortHoldFinishesWithoutWaitingForControlExpansion() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(300);
    assertTrue(controller.active);
    assertFalse(button.recordingVisible());
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.FINISH), controller.actions);
    assertTrue(controller.waiting);
    assertTrue(button.recordingVisible());
    advance(holdDelay);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.FINISH), controller.actions);
  }

  @Test
  public void dragAfterConfirmedHoldCancelsRecording() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(holdDelay);
    touch(MotionEvent.ACTION_MOVE, 100 + slop + 10, 100);
    touch(MotionEvent.ACTION_UP, 200, 300);
    assertEquals(
        List.of(Gesture.Action.START, Gesture.Action.REVEAL, Gesture.Action.CANCEL),
        controller.actions);
    assertTrue(controller.moved);
  }

  @Test
  public void batchedMovementOutAndBackStillCancelsPendingHold() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(100);
    MotionEvent event =
        MotionEvent.obtain(
            downAt, SystemClock.uptimeMillis() - 1, MotionEvent.ACTION_MOVE, 100 + slop + 10, 100, 0);
    event.addBatch(SystemClock.uptimeMillis(), 100, 100, 1, 1, 0);
    event.offsetLocation(-200, -300);
    button.onTouchEvent(event);
    event.recycle();
    advance(holdDelay);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertTrue(controller.moved);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.CANCEL), controller.actions);
    assertFalse(button.recordingVisible());
  }

  @Test
  public void waitingCanBeDraggedWithoutTriggeringAnotherAction() {
    controller.active = controller.waiting = true;
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(200);
    touch(MotionEvent.ACTION_UP, 100 + slop + 10, 200);
    assertTrue(controller.moved);
    assertTrue(controller.actions.isEmpty());
  }

  @Test
  public void secondFingerCancelsGestureAndCannotCauseAccidentalFinish() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(holdDelay);
    touch(MotionEvent.ACTION_POINTER_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(
        List.of(Gesture.Action.START, Gesture.Action.REVEAL, Gesture.Action.CANCEL),
        controller.actions);
    assertEquals(1, controller.ended);
  }

  @Test
  public void secondFingerBeforeRevealDiscardsCaptureWithoutOpeningControls() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_POINTER_DOWN, 100, 100);
    advance(holdDelay);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.CANCEL), controller.actions);
    assertFalse(button.recordingVisible());
    assertEquals(1, controller.ended);
  }

  @Test
  public void screenLockBeforeRevealPreservesCaptureAndMakesControlsAvailableOnReturn() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_CANCEL, 100, 100);
    advance(holdDelay);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.REVEAL), controller.actions);
    assertTrue(controller.active);
    assertTrue(button.recordingVisible());
    assertEquals(1, controller.ended);
  }

  @Test
  public void screenLockPointerCancellationPreservesRecording() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(holdDelay);
    touch(MotionEvent.ACTION_CANCEL, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.REVEAL), controller.actions);
    assertTrue(controller.active);
    assertEquals(1, controller.ended);
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    advance(100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(
        List.of(Gesture.Action.START, Gesture.Action.REVEAL, Gesture.Action.FINISH),
        controller.actions);
  }

  @Test
  public void detachmentPreservesCaptureWithoutLeavingRecordingControlsDeferred() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    button.onDetachedFromWindow();
    advance(holdDelay);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.REVEAL), controller.actions);
    assertTrue(controller.active);
    assertTrue(button.recordingVisible());
    assertEquals(1, controller.ended);
  }

  @Test
  public void accessibilityClickStillToggles() {
    button.performClick();
    assertEquals(1, controller.toggles);
  }
}
