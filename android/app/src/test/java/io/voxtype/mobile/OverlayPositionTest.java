package io.voxtype.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class OverlayPositionTest {
  private OverlayPosition.Placement place(OverlayPosition p, int visibleBottom, boolean expanded) {
    return p.place(12, 36, 388, 788, visibleBottom, 48, 146, 6, expanded);
  }

  private int bx(OverlayPosition.Placement p) {
    return p.x + p.buttonX;
  }

  private int by(OverlayPosition.Placement p) {
    return p.y + p.buttonY;
  }

  @Test
  public void expandingAtEitherEdgeKeepsButtonFixedAndControlsVisible() {
    for (float x : new float[] {0, 1}) {
      OverlayPosition position = new OverlayPosition(x, .3f);
      var idle = place(position, 788, false);
      var active = place(position, 788, true);
      assertEquals(bx(idle), bx(active));
      assertEquals(by(idle), by(active));
      assertTrue(active.x >= 12);
      assertTrue(active.x + active.width <= 388);
      assertEquals(x == 1, active.panelOnLeft);
    }
  }

  @Test
  public void narrowDisplayStacksControlsWithoutMovingButton() {
    OverlayPosition p = new OverlayPosition(.5f, .4f);
    var idle = p.place(12, 36, 308, 788, 788, 48, 146, 6, false);
    var active = p.place(12, 36, 308, 788, 788, 48, 146, 6, true);
    assertEquals(bx(idle), bx(active));
    assertEquals(by(idle), by(active));
    assertTrue(active.height > 48);
    assertTrue(active.y >= 36);
    assertTrue(active.x + active.width <= 308);
  }

  @Test
  public void keyboardClampIsTemporaryAndDoesNotOverwritePreference() {
    OverlayPosition p = new OverlayPosition(.8f, .9f);
    int originalY = by(place(p, 788, false));
    assertEquals(450 - 48, by(place(p, 450, false)));
    assertEquals(originalY, by(place(p, 788, false)));
    assertEquals(.9f, p.y(), 0);
  }

  @Test
  public void draggingClampsToSafeScreenAndKeyboardBounds() {
    OverlayPosition p = new OverlayPosition(1, .2f);
    p.moveTo(-100, 1000, 12, 36, 388, 788, 450, 48);
    var result = place(p, 450, true);
    assertEquals(12, bx(result));
    assertEquals(402, by(result));
    assertTrue(result.y + result.height <= 450);
  }

  @Test
  public void restoredPositionSurvivesServiceRestartAndRotation() {
    OverlayPosition p = new OverlayPosition(1, .2f);
    p.moveTo(200, 350, 12, 36, 388, 788, 788, 48);
    OverlayPosition restored = new OverlayPosition(p.x(), p.y());
    assertEquals(200, bx(place(restored, 788, false)));
    assertEquals(350, by(place(restored, 788, false)));
    var rotated = restored.place(12, 36, 788, 388, 388, 48, 146, 6, true);
    assertTrue(rotated.x >= 12 && rotated.x + rotated.width <= 788);
    assertTrue(rotated.y >= 36 && rotated.y + rotated.height <= 388);
  }

  @Test
  public void expandingAndCollapsingDuringDragDoesNotChangePosition() {
    OverlayPosition p = new OverlayPosition(.5f, .3f);
    for (int x = 12; x <= 340; x += 4) {
      p.moveTo(x, 300, 12, 36, 388, 788, 788, 48);
      assertEquals(x, bx(place(p, 788, false)));
      assertEquals(x, bx(place(p, 788, true)));
      assertEquals(300, by(place(p, 788, true)));
    }
  }
}
