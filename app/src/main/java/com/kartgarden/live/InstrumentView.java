package com.kartgarden.live;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.kartgarden.live.protocol.Orientation;
import java.util.Locale;

/** Shared quaternion feeds the horizon and projected 3D kart. */
public final class InstrumentView extends View {
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  private double[] q = Orientation.identity();
  private String source = "Fusion", detail = "Waiting for IMU", status = "WAIT_SOURCE";
  private double[] linear;
  private boolean fresh, model;

  public void modelOnly() {
    model = true;
    invalidate();
  }

  public InstrumentView(Context c) {
    super(c);
    setContentDescription("Kart artificial horizon and three dimensional orientation");
  }

  public void update(Orientation.Snapshot snapshot, boolean telemetryFresh) {
    fresh = telemetryFresh && "READY".equals(snapshot.status()) && snapshot.relative() != null;
    q = fresh ? snapshot.relative().clone() : Orientation.identity();
    linear = telemetryFresh ? snapshot.linearAcceleration() : null;
    source = snapshot.source();
    status = telemetryFresh ? snapshot.status() : "WAIT_SOURCE";
    detail = snapshot.detail();
    double[] angles = Orientation.angles(q);
    setContentDescription(
        "Kart instruments · "
            + source
            + " · "
            + status
            + " · "
            + detail
            + (fresh
                ? String.format(Locale.ROOT, " · pitch %.2f roll %.2f", angles[0], angles[1])
                : ""));
    invalidate();
  }

  private void text(Canvas c, String text, float x, float y, float size, int color) {
    p.setColor(color);
    p.setTextSize(size);
    p.setStyle(Paint.Style.FILL);
    c.drawText(text, x, y, p);
  }

  @Override
  protected void onDraw(Canvas c) {
    float w = getWidth(), h = getHeight(), pad = 16 * density(), size = 12 * density();
    p.setColor(0xff19212b);
    c.drawRoundRect(0, 0, w, h, 18, 18, p);
    if (model) {
      drawKart(c, w / 2, h * .47f, Math.min(w * .30f, h * .29f));
      centered(
          c,
          "3D KART · " + (fresh ? source.toUpperCase(Locale.ROOT) : "WAITING"),
          w / 2,
          h - 12 * density(),
          10 * density(),
          0xffccd7e2);
      return;
    }
    double x = q[0], y = q[1], z = q[2], s = q[3];
    // Same native X-right/Y-rear/Z-down basis as Swift: scene (X,-Z,Y).
    double[] angles = Orientation.angles(q);
    double pitch = Math.toRadians(angles[0]), roll = Math.toRadians(angles[1]);
    double yaw = Math.atan2(2 * (s * z + x * y), 1 - 2 * (y * y + z * z));
    float radius = Math.min(w * .36f, h * .20f);
    float cx = w / 2, cy = radius + 10 * density();
    c.save();
    Path circle = new Path();
    circle.addCircle(cx, cy, radius, Path.Direction.CW);
    c.clipPath(circle);
    c.translate(cx, cy);
    c.rotate((float) -Math.toDegrees(roll));
    float dy = (float) Math.toDegrees(pitch) * radius / 45;
    p.setColor(fresh ? 0xff365a70 : 0xff334044);
    c.drawRect(-radius * 3, -radius * 3, radius * 3, dy, p);
    p.setColor(fresh ? 0xff52623b : 0xff363d35);
    c.drawRect(-radius * 3, dy, radius * 3, radius * 3, p);
    p.setColor(0xffe5f3e9);
    p.setStrokeWidth(2);
    c.drawLine(-radius * 2, dy, radius * 2, dy, p);
    for (int deg = -30; deg <= 30; deg += 10)
      if (deg != 0) {
        float yy = dy - deg * radius / 45;
        c.drawLine(-radius * .3f, yy, radius * .3f, yy, p);
      }
    c.restore();
    p.setColor(0xffffc977);
    p.setStrokeWidth(3);
    c.drawLine(cx - radius * .6f, cy, cx - radius * .17f, cy, p);
    c.drawLine(cx + radius * .17f, cy, cx + radius * .6f, cy, p);
    c.drawCircle(cx, cy, 4, p);
    float caption = cy + radius + 15 * density();
    centered(c, "ARTIFICIAL HORIZON", cx, caption, 10 * density(), 0xffccd7e2);
    centered(
        c,
        source.toUpperCase(Locale.ROOT) + " · " + status,
        cx,
        caption + 15 * density(),
        9 * density(),
        fresh ? 0xff6ee7a0 : 0xffffbd66);
    float gy = h - radius - 34 * density();
    drawG(c, cx, gy, radius);
    centered(c, "G-FORCE", cx, gy + radius + 15 * density(), 10 * density(), 0xffccd7e2);
    centered(
        c,
        linear == null
            ? "Waiting for calibrated IMU"
            : String.format(
                Locale.ROOT, "Lateral %+.2f  Forward %+.2f G", linear[0] / 9.81, -linear[1] / 9.81),
        cx,
        h - 6 * density(),
        9 * density(),
        0xff9aa8b7);
  }

  private void centered(Canvas c, String value, float x, float y, float size, int color) {
    p.setTextAlign(Paint.Align.CENTER);
    text(c, value, x, y, size, color);
    p.setTextAlign(Paint.Align.LEFT);
  }

  private void drawG(Canvas c, float cx, float cy, float radius) {
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(density());
    p.setColor(linear == null ? 0xff657588 : 0xff55d5ed);
    c.drawCircle(cx, cy, radius, p);
    c.drawCircle(cx, cy, radius / 2, p);
    c.drawLine(cx - radius, cy, cx + radius, cy, p);
    c.drawLine(cx, cy - radius, cx, cy + radius, p);
    p.setStyle(Paint.Style.FILL);
    if (linear != null) {
      p.setColor(0xffffc977);
      float lateral = (float) Math.max(-1.25, Math.min(1.25, linear[0] / 9.81));
      float forward = (float) Math.max(-1.25, Math.min(1.25, -linear[1] / 9.81));
      c.drawCircle(cx + lateral * radius / 1.25f, cy - forward * radius / 1.25f, 3 * density(), p);
    }
  }

  private void drawKart(Canvas c, float cx, float cy, float scale) {
    p.setColor(0xff203440);
    p.setStrokeWidth(density());
    for (int i = -3; i <= 3; i++) {
      c.drawLine(cx - scale * 2, cy + i * scale * .22f, cx + scale * 2, cy + i * scale * .22f, p);
    }
    box(c, cx, cy, scale, 0, 0, 0, .62, 1.08, .12, 0xffc92732);
    box(c, cx, cy, scale, 0, -.56, -.18, .38, .48, .16, 0xffee3741);
    box(c, cx, cy, scale, -.52, .05, -.16, .17, .72, .14, 0xffe8eef0);
    box(c, cx, cy, scale, .52, .05, -.16, .17, .72, .14, 0xffe8eef0);
    box(c, cx, cy, scale, 0, .25, -.42, .25, .26, .24, 0xff3160ba);
    // Seated driver and the distinctive raised camera mast.
    ball(c, cx, cy, scale, 0, .22, -.78, .19, 0xffe5ae87);
    ball(c, cx, cy, scale, 0, .24, -.92, .20, 0xffea303c);
    box(c, cx, cy, scale, 0, .53, -.59, .035, .035, .55, 0xffe7edf0);
    box(c, cx, cy, scale, 0, .53, -1.12, .18, .12, .12, 0xffe7edf0);
    ball(c, cx, cy, scale, 0, .39, -1.12, .075, 0xff25394e);
    for (double x : new double[] {-.69, .69})
      for (double y : new double[] {-.70, .70}) {
        ball(c, cx, cy, scale, x, y, 0, .23, 0xff0c1118);
        ball(c, cx, cy, scale, x, y, 0, .105, 0xffa8b4bf);
      }
  }

  private float[] point(double[] v, float cx, float cy, float scale) {
    double[] r = Orientation.rotate(q, v);
    return new float[] {
      cx + (float) (r[0] + .25 * r[1]) * scale, cy + (float) (.45 * r[1] + r[2]) * scale
    };
  }

  private void ball(
      Canvas c,
      float cx,
      float cy,
      float scale,
      double x,
      double y,
      double z,
      double r,
      int color) {
    float[] center = point(new double[] {x, y, z}, cx, cy, scale);
    p.setStyle(Paint.Style.FILL);
    p.setColor(color);
    c.drawCircle(center[0], center[1], (float) r * scale, p);
  }

  private void box(
      Canvas c,
      float cx,
      float cy,
      float scale,
      double x,
      double y,
      double z,
      double hx,
      double hy,
      double hz,
      int color) {
    double[][] vertices = new double[8][3];
    for (int i = 0; i < 8; i++)
      vertices[i] =
          new double[] {
            x + ((i & 1) == 0 ? -hx : hx),
            y + ((i & 2) == 0 ? -hy : hy),
            z + ((i & 4) == 0 ? -hz : hz)
          };
    int[][] faces = {
      {0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}
    };
    java.util.Arrays.sort(faces, (a, b) -> Double.compare(depth(vertices, a), depth(vertices, b)));
    for (int[] face : faces) {
      Path shape = new Path();
      for (int i = 0; i < 4; i++) {
        float[] v = point(vertices[face[i]], cx, cy, scale);
        if (i == 0) shape.moveTo(v[0], v[1]);
        else shape.lineTo(v[0], v[1]);
      }
      shape.close();
      float shade = face[0] >= 4 ? 1f : .76f;
      p.setColor(
          Color.rgb(
              (int) (Color.red(color) * shade),
              (int) (Color.green(color) * shade),
              (int) (Color.blue(color) * shade)));
      p.setStyle(Paint.Style.FILL);
      c.drawPath(shape, p);
    }
  }

  private double depth(double[][] vertices, int[] face) {
    double sum = 0;
    for (int i : face) {
      double[] r = Orientation.rotate(q, vertices[i]);
      sum += r[1] - .45 * r[2];
    }
    return sum;
  }

  private float density() {
    return getResources().getDisplayMetrics().density;
  }
}
