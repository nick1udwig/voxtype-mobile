package io.voxtype.mobile;

import static org.junit.Assert.*;

import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
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

  private void touch(int action, long at, float x, float y) {
    MotionEvent event = MotionEvent.obtain(0, at, action, x, y, 0);
    button.onTouchEvent(event);
    event.recycle();
  }

  @Test
  public void dragCancelsProvisionalCaptureAndDoesNotClickOnRelease() {
    touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
    touch(MotionEvent.ACTION_MOVE, 80, 100 + slop + 10, 100);
    touch(MotionEvent.ACTION_UP, 800, 200, 300);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.CANCEL), controller.actions);
    assertEquals(100, controller.dx, 0);
    assertEquals(200, controller.dy, 0);
    assertTrue(controller.moved);
    assertEquals(0, controller.toggles);
  }

  @Test
  public void movementUsesRawCoordinatesWhenWindowMovesUnderFinger() {
    controller.active = true;
    touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
    MotionEvent event = MotionEvent.obtain(0, 100, MotionEvent.ACTION_MOVE, 140 + slop, 160, 0);
    event.offsetLocation(-200, -300);
    button.onTouchEvent(event);
    event.recycle();
    assertEquals(40 + slop, controller.dx, .001);
    assertEquals(60, controller.dy, .001);
    touch(MotionEvent.ACTION_UP, 500, 140 + slop, 160);
    assertTrue(controller.active);
    assertTrue(controller.actions.isEmpty());
  }

  @Test
  public void stationaryHoldAndTapToFinishStillWork() {
    touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
    touch(MotionEvent.ACTION_MOVE, 100, 100 + slop / 2, 100);
    touch(MotionEvent.ACTION_UP, 500, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.FINISH), controller.actions);
    assertFalse(controller.moved);
    assertEquals(0, controller.toggles);
  }

  @Test
  public void waitingCanBeDraggedWithoutTriggeringAnotherAction() {
    controller.active = controller.waiting = true;
    touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
    touch(MotionEvent.ACTION_UP, 200, 100 + slop + 10, 200);
    assertTrue(controller.moved);
    assertTrue(controller.actions.isEmpty());
  }

  @Test
  public void secondFingerCancelsGestureAndCannotCauseAccidentalFinish() {
    touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
    touch(MotionEvent.ACTION_POINTER_DOWN, 20, 100, 100);
    touch(MotionEvent.ACTION_UP, 1000, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.CANCEL), controller.actions);
    assertEquals(1, controller.ended);
  }

  @Test
  public void screenLockPointerCancellationPreservesRecording() {
    touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
    touch(MotionEvent.ACTION_CANCEL, 100, 100, 100);
    touch(MotionEvent.ACTION_UP, 1000, 100, 100);
    assertEquals(List.of(Gesture.Action.START), controller.actions);
    assertTrue(controller.active);
    assertEquals(1, controller.ended);
    touch(MotionEvent.ACTION_DOWN, 1200, 100, 100);
    touch(MotionEvent.ACTION_UP, 1300, 100, 100);
    assertEquals(List.of(Gesture.Action.START, Gesture.Action.FINISH), controller.actions);
  }

  @Test
  public void accessibilityClickStillToggles() {
    button.performClick();
    assertEquals(1, controller.toggles);
  }
}
