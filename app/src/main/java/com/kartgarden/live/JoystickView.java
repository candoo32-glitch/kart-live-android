package com.kartgarden.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public final class JoystickView extends View {
  public interface Listener {
    void move(float x, float y);
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Listener listener;
  private float x, y;
  private int pointer = -1;

  public JoystickView(Context c) {
    super(c);
    setContentDescription(
        "Driving joystick: drag up to move forward, down to reverse, left or right to steer");
    setFocusable(true);
  }

  public void listener(Listener l) {
    listener = l;
  }

  public boolean held() {
    return pointer >= 0;
  }

  public float steer() {
    return x;
  }

  public float throttle() {
    return y;
  }

  public void neutral() {
    pointer = -1;
    x = y = 0;
    if (listener != null) listener.move(0, 0);
    invalidate();
  }

  @Override
  protected void onDraw(Canvas c) {
    float r = Math.min(getWidth(), getHeight()) * .43f, cx = getWidth() / 2f, cy = getHeight() / 2f;
    paint.setColor(0x99142020);
    c.drawCircle(cx, cy, r, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2);
    paint.setColor(0x9988e2b3);
    c.drawCircle(cx, cy, r, paint);
    c.drawLine(cx - r * .8f, cy, cx + r * .8f, cy, paint);
    c.drawLine(cx, cy - r * .8f, cx, cy + r * .8f, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xcc88e2b3);
    c.drawCircle(cx + x * r * .72f, cy - y * r * .72f, r * .27f, paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    int action = e.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      pointer = e.getPointerId(0);
      getParent().requestDisallowInterceptTouchEvent(true);
    }
    if (action == MotionEvent.ACTION_UP
        || action == MotionEvent.ACTION_CANCEL
        || (action == MotionEvent.ACTION_POINTER_UP
            && e.getPointerId(e.getActionIndex()) == pointer)) {
      neutral();
      performClick();
      return true;
    }
    int index = e.findPointerIndex(pointer);
    if (index < 0) return true;
    float r = Math.min(getWidth(), getHeight()) * .43f;
    x = (e.getX(index) - getWidth() / 2f) / r;
    y = -(e.getY(index) - getHeight() / 2f) / r;
    float n = (float) Math.sqrt(x * x + y * y);
    if (n > 1) {
      x /= n;
      y /= n;
    }
    if (listener != null) listener.move(x, y);
    invalidate();
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
