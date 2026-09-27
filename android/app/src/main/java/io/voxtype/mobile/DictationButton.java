package io.voxtype.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

final class DictationButton extends View {
  interface Controller {
    boolean active();

    boolean waiting();

    void act(Gesture.Action action);

    void toggle();

    void beginTouch();

    void drag(float deltaX, float deltaY);

    void endTouch(boolean moved);
  }

  private final Controller controller;
  private final Gesture gesture;
  private int pointerId = -1;
  private boolean touchClick;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Drawable microphone;

  DictationButton(Context context, Controller controller) {
    super(context);
    this.controller = controller;
    gesture = new Gesture(ViewConfiguration.get(context).getScaledTouchSlop());
    microphone = context.getApplicationInfo().loadIcon(context.getPackageManager()).mutate();
    microphone.setTint(0xFF202025);
    microphone.setBounds(12, 12, 36, 36);
    setClickable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return "android.widget.Button";
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float unit = getWidth() / 48f;
    canvas.save();
    canvas.scale(unit, unit);
    paint.setColor(controller.active() ? 0xFF70439A : isPressed() ? 0xEED0CDD7 : 0xE6B9B5C1);
    paint.setStyle(Paint.Style.FILL);
    float radius = controller.active() ? 24 : 14;
    canvas.drawRoundRect(0, 0, 48, 48, radius, radius, paint);
    paint.setColor(controller.active() ? 0xFFE1D5EA : 0xFF202025);
    if (controller.waiting()) {
      for (int i = 0; i < 3; i++) canvas.drawCircle(17 + i * 7, 24, 1.6f, paint);
    } else if (controller.active()) {
      paint.setStrokeWidth(2.3f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      canvas.drawLine(17.5f, 24, 22, 28.5f, paint);
      canvas.drawLine(22, 28.5f, 31, 19.5f, paint);
    } else {
      microphone.draw(canvas);
    }
    canvas.restore();
  }

  @Override
  protected void drawableStateChanged() {
    super.drawableStateChanged();
    invalidate();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        pointerId = event.getPointerId(0);
        controller.beginTouch();
        setPressed(true);
        controller.act(
            gesture.down(
                event.getEventTime(),
                event.getRawX(),
                event.getRawY(),
                controller.active(),
                controller.waiting()));
        return true;
      case MotionEvent.ACTION_MOVE:
        move(event);
        return true;
      case MotionEvent.ACTION_UP:
        if (pointerId == -1) return true;
        move(event);
        boolean moved = gesture.dragging();
        pointerId = -1;
        setPressed(false);
        controller.act(gesture.up(event.getEventTime()));
        controller.endTouch(moved);
        if (!moved) {
          // Emit the click accessibility event without starting another session.
          touchClick = true;
          performClick();
          touchClick = false;
        }
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        cancelTouch();
        return true;
      case MotionEvent.ACTION_CANCEL:
        // Android cancels the pointer when the display locks. The recording
        // belongs to the dictation session, not the lifetime of this pointer.
        abandonTouch();
        return true;
      default:
        return true;
    }
  }

  private void move(MotionEvent event) {
    int index = event.findPointerIndex(pointerId);
    if (index < 0) return;
    float x = event.getRawX(index), y = event.getRawY(index);
    controller.act(gesture.move(x, y));
    if (gesture.dragging()) {
      setPressed(false);
      controller.drag(gesture.deltaX(x), gesture.deltaY(y));
    }
  }

  void cancelTouch() {
    if (pointerId == -1) return;
    pointerId = -1;
    setPressed(false);
    controller.act(gesture.cancel());
    controller.endTouch(gesture.dragging());
  }

  void abandonTouch() {
    if (pointerId == -1) return;
    boolean moved = gesture.dragging();
    pointerId = -1;
    setPressed(false);
    gesture.abandon();
    controller.endTouch(moved);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    if (!touchClick) controller.toggle();
    return true;
  }
}
