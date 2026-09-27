package io.voxtype.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class ScreenLockGateTest {
  @Test
  public void completedDictationWaitsForUnlockAndIsDeliveredOnce() {
    ScreenLockGate gate = new ScreenLockGate();
    gate.screenOff();
    assertTrue(gate.blocked(true, false));
    assertNull(gate.received("complete transcript", true, false));
    assertNull(gate.takePending(true, true)); // Screen woke to the keyguard.
    assertTrue(gate.userPresent());
    assertFalse(gate.userPresent());
    assertNull(gate.takePending(true, true)); // Unlock is still in progress.
    assertEquals("complete transcript", gate.takePending(false, true));
    assertNull(gate.takePending(false, true));
  }

  @Test
  public void explicitCancellationDiscardsPendingText() {
    ScreenLockGate gate = new ScreenLockGate();
    gate.screenOff();
    assertNull(gate.received("private text", true, false));
    gate.discard();
    gate.userPresent();
    assertNull(gate.takePending(false, true));
  }

  @Test
  public void awakeDictationDeliversImmediately() {
    ScreenLockGate gate = new ScreenLockGate();
    assertEquals("now", gate.received("now", false, true));
    assertNull(gate.takePending(false, true));
  }
}
