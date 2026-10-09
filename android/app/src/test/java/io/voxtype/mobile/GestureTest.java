package io.voxtype.mobile;

import static io.voxtype.mobile.Gesture.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class GestureTest {
  private static final long HOLD_DELAY = 500;

  @Test
  public void tapCapturesOnDownRevealsOnReleaseAndSecondTapFinishes() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    assertEquals(START, g.down(0, 100, 100, false, false));
    assertTrue(g.provisional());
    assertEquals(REVEAL, g.up(100));
    assertFalse(g.provisional());
    assertEquals(NONE, g.down(1000, 100, 100, true, false));
    assertEquals(FINISH, g.up(1100));
  }

  @Test
  public void stationaryHoldFinishesOnReleaseEvenWhileConnecting() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    assertEquals(START, g.down(0, 0, 0, false, false));
    assertEquals(NONE, g.move(3, 2));
    assertFalse(g.dragging());
    assertEquals(NONE, g.hold(HOLD_DELAY - 1));
    assertTrue(g.provisional());
    assertEquals(REVEAL, g.hold(HOLD_DELAY));
    assertFalse(g.provisional());
    assertEquals(NONE, g.hold(HOLD_DELAY + 1));
    assertEquals(FINISH, g.up(2000));
  }

  @Test
  public void movementPastSlopCancelsCaptureAndNeverRevealsControls() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    g.down(0, 100, 100, false, false);
    assertEquals(NONE, g.move(108, 100));
    assertEquals(CANCEL, g.move(109, 100));
    assertTrue(g.dragging());
    assertFalse(g.provisional());
    assertEquals(NONE, g.hold(5000));
    assertEquals(NONE, g.move(150, 200));
    assertEquals(NONE, g.cancel());
    assertEquals(NONE, g.up(5000));
  }

  @Test
  public void diagonalMotionCountsAndReturningToOriginStaysADrag() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    g.down(0, 100, 100, false, false);
    assertEquals(CANCEL, g.move(106, 106));
    assertEquals(NONE, g.move(100, 100));
    assertTrue(g.dragging());
    assertEquals(NONE, g.hold(HOLD_DELAY));
    assertEquals(NONE, g.up(600));
  }

  @Test
  public void draggingAfterConfirmedHoldCancelsExactlyOnce() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    g.down(0, 100, 100, false, false);
    assertEquals(REVEAL, g.hold(HOLD_DELAY));
    assertEquals(CANCEL, g.move(109, 100));
    assertEquals(NONE, g.move(150, 200));
    assertEquals(NONE, g.hold(2000));
    assertEquals(NONE, g.cancel());
    assertEquals(NONE, g.up(5000));
  }

  @Test
  public void dragDuringExistingRecordingNeitherFinishesNorCancelsIt() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    assertEquals(NONE, g.down(0, 10, 20, true, false));
    assertEquals(NONE, g.hold(HOLD_DELAY));
    assertEquals(NONE, g.move(100, 200));
    assertTrue(g.dragging());
    assertEquals(90, g.deltaX(100), 0);
    assertEquals(180, g.deltaY(200), 0);
    assertEquals(NONE, g.up(500));
  }

  @Test
  public void waitingAllowsDragButNeverStartsOrFinishesRecording() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    assertEquals(NONE, g.down(0, 0, 0, true, true));
    assertEquals(NONE, g.hold(HOLD_DELAY));
    assertEquals(NONE, g.move(20, 20));
    assertTrue(g.dragging());
    assertEquals(NONE, g.up(500));
  }

  @Test
  public void cancellationDoesNotLeakIntoNextTouch() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    g.down(0, 0, 0, false, false);
    assertEquals(REVEAL, g.hold(HOLD_DELAY));
    assertEquals(CANCEL, g.cancel());
    assertEquals(NONE, g.hold(600));
    assertEquals(NONE, g.up(600));
    assertEquals(START, g.down(1000, 0, 0, false, false));
    assertFalse(g.dragging());
    assertEquals(REVEAL, g.hold(1500));
    assertEquals(FINISH, g.up(2000));
  }

  @Test
  public void provisionalCaptureCancelsOnExplicitCancellationButSurvivesAbandonment() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    g.down(0, 0, 0, false, false);
    assertEquals(CANCEL, g.cancel());
    assertEquals(NONE, g.hold(1000));
    assertEquals(NONE, g.up(1000));
    g.down(2000, 0, 0, false, false);
    assertEquals(REVEAL, g.abandon());
    assertFalse(g.provisional());
    assertEquals(NONE, g.hold(3000));
    assertEquals(NONE, g.up(3000));
  }

  @Test
  public void shortHoldFinishesCapturedAudioBeforeControlsAreRevealed() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    assertEquals(START, g.down(0, 0, 0, false, false));
    assertTrue(g.provisional());
    assertEquals(FINISH, g.up(300));
    assertFalse(g.provisional());
    assertEquals(NONE, g.hold(1000));
  }

  @Test
  public void delayedPresentationCallbackCannotPreventHoldFinishing() {
    Gesture g = new Gesture(8, HOLD_DELAY);
    assertEquals(START, g.down(0, 0, 0, false, false));
    assertEquals(FINISH, g.up(HOLD_DELAY + 500));
    assertEquals(NONE, g.hold(1000));
  }
}
