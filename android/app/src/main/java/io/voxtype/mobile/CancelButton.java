package io.voxtype.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

final class CancelButton extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  CancelButton(Context context) {
    super(context);
    setContentDescription("Cancel dictation");
    setClickable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return "android.widget.Button";
  }

  @Override
  protected void drawableStateChanged() {
    super.drawableStateChanged();
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.scale(getWidth() / 48f, getHeight() / 48f);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(isPressed() ? 0xEED0CDD7 : 0xE6B9B5C1);
    canvas.drawCircle(24, 24, 24, paint);
    paint.setColor(0xFF202025);
    paint.setStrokeWidth(2.3f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    canvas.drawLine(18.5f, 18.5f, 29.5f, 29.5f, paint);
    canvas.drawLine(29.5f, 18.5f, 18.5f, 29.5f, paint);
    canvas.restore();
  }
}
