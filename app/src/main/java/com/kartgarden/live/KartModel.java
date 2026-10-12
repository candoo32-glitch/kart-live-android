package com.kartgarden.live;

import android.graphics.*;
import com.kartgarden.live.protocol.Orientation;
import java.util.*;

/** Swift LiveKart3DView geometry, shaded and depth-sorted in scene coordinates. */
final class KartModel {
  private record Face(double[][] vertices, int color) {}

  private record DrawFace(float[] xy, double depth, int color) {}

  private final List<Face> mesh = new ArrayList<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();

  KartModel() {
    int red = rgb(.86, .055, .07),
        brightRed = rgb(.98, .15, .12),
        white = rgb(.97, .97, .94),
        black = rgb(.065, .075, .085),
        rubber = rgb(.045, .047, .05),
        blue = rgb(.10, .26, .68),
        yellow = rgb(1, .78, .13),
        skin = rgb(.95, .72, .53),
        brown = rgb(.28, .14, .08);
    box(1.12, 0.19, 1.70, 0, -0.12, 0.00, black, 0.09);
    box(1.28, 0.18, 0.30, 0, -0.035, -0.87, white, 0.09);
    box(1.08, 0.23, 0.75, 0, 0.055, -0.55, white, 0.18);
    box(0.70, 0.12, 0.76, 0, 0.185, -0.54, brightRed, 0.11);
    box(0.55, 0.025, 0.08, 0, 0.252, -0.78, white, 0.025);
    // Distinctive wraparound white wheel arches and red side pods.
    for (double x : new double[] {-0.51, 0.51}) {
      box(0.35, 0.26, 1.10, x, 0.065, 0.05, white, 0.12);
      box(0.35, 0.12, 0.77, x, 0.205, 0.11, red, 0.06);
      box(0.33, 0.19, 0.43, x, 0.07, -0.56, white, 0.13);
      box(0.34, 0.20, 0.43, x, 0.08, 0.59, white, 0.13);
    }
    box(0.67, 0.11, 0.67, 0, 0.15, 0.20, black, 0.12);
    box(0.50, 0.40, 0.16, 0, 0.39, 0.50, blue, 0.07);
    box(1.06, 0.18, 0.35, 0, 0.16, 0.77, red, 0.10);
    // Wheels are actual horizontal cylinders, with exposed gold hub faces.
    for (double x : new double[] {-0.73, 0.73}) {
      for (double z : new double[] {-0.58, 0.58}) {
        cylinder(0.28, 0.23, x, -0.235, z, rubber, true);
        double outward = x < 0 ? -0.13 : 0.13;
        cylinder(0.135, 0.025, x + outward, -0.235, z, yellow, true);
        cylinder(0.065, 0.030, x + outward * 1.13, -0.235, z, black, true);
      }
    }
    // Seated Mario: rounded overalls, red sleeves, gloves, face, nose, moustache and cap.
    box(0.46, 0.38, 0.39, 0, 0.42, 0.27, blue, 0.14);
    sphere(0.20, 0, 0.55, 0.23, red);
    for (double x : new double[] {-0.27, 0.27}) {
      sphere(0.135, x, 0.48, 0.12, red);
      sphere(0.105, x * 1.30, 0.40, -0.04, white);
    }
    sphere(0.265, 0, 0.84, 0.21, skin);
    sphere(0.095, 0, 0.80, -0.035, skin);
    for (double x : new double[] {-0.075, 0.075}) {
      sphere(0.075, x, 0.755, -0.005, brown);
      sphere(0.038, x, 0.905, -0.013, white);
    }
    // Round crown and forward brim are much closer to the toy silhouette.
    sphere(0.29, 0, 1.055, 0.21, brightRed);
    box(0.52, 0.075, 0.26, 0, 1.015, -0.025, red, 0.035);
    sphere(0.095, 0, 1.12, -0.048, white);
    // Steering wheel and prominent Kart Live camera mast.  The raised,
    // forward-facing lens must remain visible above the driver silhouette.
    cylinder(0.18, 0.05, 0, 0.44, -0.19, black);
    box(0.17, 0.62, 0.17, 0, 0.84, 0.68, black, 0.045);
    box(0.23, 0.10, 0.22, 0, 1.09, 0.68, red, 0.04);
    box(0.60, 0.40, 0.40, 0, 1.28, 0.64, black, 0.09);
    box(0.50, 0.32, 0.045, 0, 1.28, 0.405, white, 0.04);
    cylinder(0.145, 0.030, 0, 1.28, 0.372, black);
    sphere(0.100, 0, 1.28, 0.348, rgb(0.11, 0.18, 0.25));
    sphere(0.048, -0.025, 1.305, 0.280, rgb(0.29, 0.58, 0.82));
    sphere(0.024, 0.18, 1.31, 0.368, 0xffcccccc);
    // Twin rear exhaust outlets and front amber markers.
    for (double x : new double[] {-0.41, 0.41}) {
      cylinder(0.09, 0.23, x, 0.17, 0.94, 0xff555555);
      sphere(0.065, x, 0.045, -0.94, yellow);
    }
  }

  private static int rgb(double r, double g, double b) {
    return Color.rgb((int) (255 * r), (int) (255 * g), (int) (255 * b));
  }

  private void face(double[] a, double[] b, double[] c, int color) {
    mesh.add(new Face(new double[][] {a, b, c}, color));
  }

  private double[] rounded(double[] p, double[] half, double radius) {
    double[] core = new double[3], delta = new double[3];
    double length = 0;
    for (int i = 0; i < 3; i++) {
      double limit = Math.max(0, half[i] - radius);
      core[i] = Math.max(-limit, Math.min(limit, p[i]));
      delta[i] = p[i] - core[i];
      length += delta[i] * delta[i];
    }
    length = Math.sqrt(length);
    for (int i = 0; i < 3; i++) core[i] += length > 0 ? delta[i] * radius / length : 0;
    return core;
  }

  private void box(
      double w, double h, double l, double x, double y, double z, int color, double chamfer) {
    double[] half = {w / 2, h / 2, l / 2}, origin = {x, y, z};
    double radius = Math.min(chamfer, Math.min(w, Math.min(h, l)) / 2);
    int n = 4;
    for (int axis = 0; axis < 3; axis++)
      for (int sign : new int[] {-1, 1}) {
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        double[][][] grid = new double[n + 1][n + 1][];
        for (int i = 0; i <= n; i++)
          for (int j = 0; j <= n; j++) {
            double[] p = new double[3];
            p[axis] = sign * half[axis];
            p[u] = (2.0 * i / n - 1) * half[u];
            p[v] = (2.0 * j / n - 1) * half[v];
            p = rounded(p, half, radius);
            for (int k = 0; k < 3; k++) p[k] += origin[k];
            grid[i][j] = p;
          }
        for (int i = 0; i < n; i++)
          for (int j = 0; j < n; j++) {
            face(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], color);
            face(grid[i][j], grid[i + 1][j + 1], grid[i][j + 1], color);
          }
      }
  }

  private void sphere(double radius, double x, double y, double z, int color) {
    int n = 12, m = 8;
    double[][][] grid = new double[m + 1][n + 1][];
    for (int i = 0; i <= m; i++)
      for (int j = 0; j <= n; j++) {
        double a = Math.PI * i / m, b = 2 * Math.PI * j / n;
        grid[i][j] =
            new double[] {
              x + radius * Math.sin(a) * Math.cos(b),
              y + radius * Math.cos(a),
              z + radius * Math.sin(a) * Math.sin(b)
            };
      }
    for (int i = 0; i < m; i++)
      for (int j = 0; j < n; j++) {
        face(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], color);
        face(grid[i][j], grid[i + 1][j + 1], grid[i][j + 1], color);
      }
  }

  private void cylinder(double r, double h, double x, double y, double z, int color) {
    cylinder(r, h, x, y, z, color, false);
  }

  private void cylinder(
      double r, double h, double x, double y, double z, int color, boolean wheel) {
    int n = 16;
    double[][] a = new double[n][], b = new double[n][];
    double[] ca = {x, y, z}, cb = {x, y, z};
    int axis = wheel ? 0 : 1;
    ca[axis] -= h / 2;
    cb[axis] += h / 2;
    for (int i = 0; i < n; i++) {
      double t = i * 2 * Math.PI / n, rx = r * Math.cos(t), ry = r * Math.sin(t);
      a[i] =
          wheel
              ? new double[] {x - h / 2, y + rx, z + ry}
              : new double[] {x + rx, y - h / 2, z + ry};
      b[i] =
          wheel
              ? new double[] {x + h / 2, y + rx, z + ry}
              : new double[] {x + rx, y + h / 2, z + ry};
    }
    for (int i = 0; i < n; i++) {
      int j = (i + 1) % n;
      face(a[i], a[j], b[j], color);
      face(a[i], b[j], b[i], color);
      face(ca, a[j], a[i], color);
      face(cb, b[i], b[j], color);
    }
  }

  void draw(Canvas canvas, double[] nativeQ, float width, float height, boolean ready) {
    double[] q = Orientation.sceneQuaternion(nativeQ);
    double yaw = Math.atan2(2.98, 4.44),
        tilt = -Math.atan2(1.76, Math.hypot(2.98, 4.44)),
        scale = Math.min(width * .37, height * .36);
    List<DrawFace> drawing = new ArrayList<>(mesh.size());
    for (Face face : mesh) {
      float[] xy = new float[6];
      double depth = 0;
      double[][] rotated = new double[3][];
      for (int i = 0; i < 3; i++) {
        double[] p = Orientation.rotate(q, face.vertices[i]);
        rotated[i] = p;
        double vx = Math.cos(yaw) * p[0] + Math.sin(yaw) * p[2];
        double vz = -Math.sin(yaw) * p[0] + Math.cos(yaw) * p[2];
        double vy = Math.cos(tilt) * (p[1] - .49) - Math.sin(tilt) * vz;
        double d = Math.sin(tilt) * (p[1] - .49) + Math.cos(tilt) * vz;
        double perspective = 6 / (6 + d);
        xy[i * 2] = (float) (width * .5 + vx * scale * perspective);
        xy[i * 2 + 1] = (float) (height * .55 - vy * scale * perspective);
        depth += d;
      }
      double[] a = Orientation.subtract(rotated[1], rotated[0]),
          b = Orientation.subtract(rotated[2], rotated[0]);
      double[] normal = {
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
      };
      double length = Orientation.norm(normal);
      double light =
          .65
              + (length > 1e-10
                  ? .35 * Math.abs((normal[0] * -.3 + normal[1] * .8 - normal[2] * .5) / length)
                  : 0);
      int color = face.color;
      if (!ready) light *= .65;
      drawing.add(
          new DrawFace(
              xy,
              depth / 3,
              Color.rgb(
                  (int) (Color.red(color) * light),
                  (int) (Color.green(color) * light),
                  (int) (Color.blue(color) * light))));
    }
    drawing.sort((a, b) -> Double.compare(b.depth, a.depth));
    paint.setStyle(Paint.Style.FILL_AND_STROKE);
    paint.setStrokeWidth(.5f);
    for (DrawFace face : drawing) {
      path.reset();
      path.moveTo(face.xy[0], face.xy[1]);
      path.lineTo(face.xy[2], face.xy[3]);
      path.lineTo(face.xy[4], face.xy[5]);
      path.close();
      paint.setColor(face.color);
      canvas.drawPath(path, paint);
    }
  }
}
