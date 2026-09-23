package io.voxtype.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import java.util.Arrays;

/** A compact scrolling history of measured audio levels, with smooth visual interpolation. */
final class VoiceActivityView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float[] levels = new float[11], drawn = new float[11];
  private boolean waiting, retry;
  private String phase = "Transcribing…";
  private long lastDraw;

  VoiceActivityView(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setContentDescription("Microphone activity");
  }

  void level(float level) {
    if (waiting) return;
    System.arraycopy(levels, 1, levels, 0, levels.length - 1);
    levels[levels.length - 1] = level;
    invalidate();
  }

  void waiting(boolean value) {
    if (waiting != value) {
      waiting = value;
      setContentDescription(value ? "Transcribing" : "Microphone activity");
      invalidate();
    }
  }

  void retryAvailable(boolean value) {
    if (retry != value) {
      retry = value;
      if (value) setContentDescription("Audio retained. Tap the check to retry");
      invalidate();
    }
  }

  void reset() {
    Arrays.fill(levels, 0);
    Arrays.fill(drawn, 0);
    lastDraw = 0;
    waiting = false;
    retry = false;
    phase = "Transcribing…";
    setContentDescription("Microphone activity");
    invalidate();
  }

  void status(String status) {
    String value = status.toLowerCase(java.util.Locale.ROOT);
    String next =
        value.contains("reconnect")
            ? "Reconnecting…"
            : value.contains("sending") ? "Sending…" : "Transcribing…";
    if (!next.equals(phase)) {
      phase = next;
      invalidate();
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    canvas.save();
    canvas.scale(density, density);
    float width = getWidth() / density, height = getHeight() / density;
    paint.setColor(0xE6B9B5C1);
    paint.setStyle(Paint.Style.FILL);
    canvas.drawRoundRect(0, 0, width, height, height / 2, height / 2, paint);
    paint.setColor(0xFF202025);
    if (waiting || retry) {
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(10);
      canvas.drawText(
          retry ? "Tap to retry" : phase,
          width / 2,
          height / 2 - (paint.ascent() + paint.descent()) / 2,
          paint);
    } else {
      long now = SystemClock.uptimeMillis();
      float blend = lastDraw == 0 ? 1 : Math.min(1, (now - lastDraw) / 70f);
      lastDraw = now;
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeWidth(2.3f);
      boolean moving = false;
      for (int i = 0; i < levels.length; i++) {
        drawn[i] += (levels[i] - drawn[i]) * blend;
        moving |= Math.abs(levels[i] - drawn[i]) > .005f;
        float x = width / 2 + (i - 5) * 5;
        float bar = drawn[i] * 22;
        canvas.drawLine(x, height / 2 - bar / 2, x, height / 2 + bar / 2, paint);
        if (bar < .1f) canvas.drawCircle(x, height / 2, 1.15f, paint);
      }
      if (moving) postInvalidateOnAnimation();
    }
    canvas.restore();
  }
}
