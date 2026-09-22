package io.voxtype.mobile;

/** Saved button position uses fractions of the display's safe area, independent of the IME. */
final class OverlayPosition {
  private float x, y;

  OverlayPosition(float x, float y) {
    this.x = fraction(x, 1);
    this.y = fraction(y, .26f);
  }

  float x() {
    return x;
  }

  float y() {
    return y;
  }

  void moveTo(
      float left,
      float top,
      int safeLeft,
      int safeTop,
      int safeRight,
      int safeBottom,
      int visibleBottom,
      int size) {
    int maxX = Math.max(safeLeft, safeRight - size);
    int maxY = Math.max(safeTop, safeBottom - size);
    x = maxX == safeLeft ? 0 : (clamp(left, safeLeft, maxX) - safeLeft) / (maxX - safeLeft);
    float actualY = clamp(top, safeTop, Math.max(safeTop, visibleBottom - size));
    y = maxY == safeTop ? 0 : (actualY - safeTop) / (maxY - safeTop);
  }

  Placement place(
      int left,
      int top,
      int right,
      int bottom,
      int visibleBottom,
      int size,
      int panelWidth,
      int gap,
      boolean expanded) {
    int bx = Math.round(left + x * Math.max(0, right - left - size));
    int by =
        Math.round(
            clamp(
                top + y * Math.max(0, bottom - top - size),
                top,
                Math.max(top, visibleBottom - size)));
    if (!expanded) return new Placement(bx, by, size, size, 0, 0, 0, 0, true);
    int px, py = by;
    boolean panelOnLeft = true;
    if (bx - left >= panelWidth + gap) {
      px = bx - panelWidth - gap;
    } else if (right - bx - size >= panelWidth + gap) {
      px = bx + size + gap;
      panelOnLeft = false;
    } else {
      // Near the middle of a narrow display, keep the button under the finger
      // and put the activity/cancel panel above (or below) instead of jumping it.
      px =
          Math.round(
              clamp(bx + (size - panelWidth) / 2f, left, Math.max(left, right - panelWidth)));
      py = by - top >= size + gap ? by - size - gap : by + size + gap;
      py = Math.round(clamp(py, top, Math.max(top, visibleBottom - size)));
    }
    int wx = Math.min(bx, px), wy = Math.min(by, py);
    return new Placement(
        wx,
        wy,
        Math.max(bx + size, px + panelWidth) - wx,
        Math.max(by, py) + size - wy,
        bx - wx,
        by - wy,
        px - wx,
        py - wy,
        panelOnLeft);
  }

  static final class Placement {
    final int x, y, width, height, buttonX, buttonY, panelX, panelY;
    final boolean panelOnLeft;

    Placement(
        int x,
        int y,
        int width,
        int height,
        int buttonX,
        int buttonY,
        int panelX,
        int panelY,
        boolean panelOnLeft) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
      this.buttonX = buttonX;
      this.buttonY = buttonY;
      this.panelX = panelX;
      this.panelY = panelY;
      this.panelOnLeft = panelOnLeft;
    }
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }

  private static float fraction(float value, float fallback) {
    return Float.isFinite(value) ? clamp(value, 0, 1) : fallback;
  }
}
