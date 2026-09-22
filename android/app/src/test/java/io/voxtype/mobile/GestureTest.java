package io.voxtype.mobile;

import static io.voxtype.mobile.Gesture.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class GestureTest {
  @Test
  public void tapStartsAndSecondTapFinishesOnRelease() {
    Gesture g = new Gesture(8);
    assertEquals(START, g.down(0, 100, 100, false, false));
    assertEquals(NONE, g.up(100));
    assertEquals(NONE, g.down(1000, 100, 100, true, false));
    assertEquals(FINISH, g.up(1100));
  }

  @Test
  public void stationaryHoldFinishesOnReleaseEvenWhileConnecting() {
    Gesture g = new Gesture(8);
    assertEquals(START, g.down(0, 0, 0, false, false));
    assertEquals(NONE, g.move(3, 2));
    assertFalse(g.dragging());
    assertEquals(FINISH, g.up(300));
  }

  @Test
  public void movementPastSlopCancelsNewRecordingExactlyOnce() {
    Gesture g = new Gesture(8);
    g.down(0, 100, 100, false, false);
    assertEquals(NONE, g.move(108, 100));
    assertEquals(CANCEL, g.move(109, 100));
    assertTrue(g.dragging());
    assertEquals(NONE, g.move(150, 200));
    assertEquals(NONE, g.cancel());
    assertEquals(NONE, g.up(5000));
  }

  @Test
  public void diagonalMotionCountsAndReturningToOriginStaysADrag() {
    Gesture g = new Gesture(8);
    g.down(0, 100, 100, false, false);
    assertEquals(CANCEL, g.move(106, 106));
    assertEquals(NONE, g.move(100, 100));
    assertTrue(g.dragging());
    assertEquals(NONE, g.up(500));
  }

  @Test
  public void dragDuringExistingRecordingNeitherFinishesNorCancelsIt() {
    Gesture g = new Gesture(8);
    assertEquals(NONE, g.down(0, 10, 20, true, false));
    assertEquals(NONE, g.move(100, 200));
    assertTrue(g.dragging());
    assertEquals(90, g.deltaX(100), 0);
    assertEquals(180, g.deltaY(200), 0);
    assertEquals(NONE, g.up(500));
  }

  @Test
  public void waitingAllowsDragButNeverStartsOrFinishesRecording() {
    Gesture g = new Gesture(8);
    assertEquals(NONE, g.down(0, 0, 0, true, true));
    assertEquals(NONE, g.move(20, 20));
    assertTrue(g.dragging());
    assertEquals(NONE, g.up(500));
  }

  @Test
  public void cancellationDoesNotLeakIntoNextTouch() {
    Gesture g = new Gesture(8);
    g.down(0, 0, 0, false, false);
    assertEquals(CANCEL, g.cancel());
    assertEquals(NONE, g.up(500));
    assertEquals(START, g.down(1000, 0, 0, false, false));
    assertFalse(g.dragging());
    assertEquals(FINISH, g.up(1500));
  }
}
