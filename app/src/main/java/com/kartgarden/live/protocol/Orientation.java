package com.kartgarden.live.protocol;

/** Relative instrumentation only. Experimental outputs never feed motor controls. */
public final class Orientation {
  public static double[] identity() {
    return new double[] {0, 0, 0, 1};
  }

  public static double[] multiply(double[] a, double[] b) {
    return normalize(
        new double[] {
          a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
          a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
          a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
          a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        });
  }

  public static double[] inverse(double[] q) {
    return new double[] {-q[0], -q[1], -q[2], q[3]};
  }

  public static double[] normalize(double[] q) {
    double n = norm(q);
    if (!Double.isFinite(n) || n < 1e-9) return identity();
    double[] out = q.clone();
    for (int i = 0; i < out.length; i++) out[i] /= n;
    return out;
  }

  public static double norm(double[] v) {
    double n = 0;
    for (double x : v) n += x * x;
    return Math.sqrt(n);
  }

  public static double[] rotate(double[] q, double[] v) {
    double x = q[0], y = q[1], z = q[2], w = q[3];
    double tx = 2 * (y * v[2] - z * v[1]),
        ty = 2 * (z * v[0] - x * v[2]),
        tz = 2 * (x * v[1] - y * v[0]);
    return new double[] {
      v[0] + w * tx + y * tz - z * ty,
      v[1] + w * ty + z * tx - x * tz,
      v[2] + w * tz + x * ty - y * tx
    };
  }

  public static double[] integrate(double[] q, double[] dps, double dt) {
    double rate = norm(dps) * Math.PI / 180;
    if (rate < 1e-10) return q;
    double k = Math.sin(rate * dt / 2) / rate * Math.PI / 180;
    return multiply(q, new double[] {dps[0] * k, dps[1] * k, dps[2] * k, Math.cos(rate * dt / 2)});
  }

  private double[] reference,
      bias,
      gravityReference,
      sum = new double[3],
      gravitySum = new double[3];
  private double[] gyro = identity(), fusion = identity(), previousGravity;
  private Boolean previousPrecision;
  private long previousTime;
  private int calibrationCount;
  private boolean calibrating;
  private boolean calibrationGravityUsable = true;
  public String detail = "Nintendo · not calibrated";

  public void reset() {
    reference = null;
    bias = null;
    gravityReference = null;
    gyro = identity();
    fusion = identity();
    previousTime = 0;
    previousGravity = null;
    previousPrecision = null;
    calibrating = false;
    calibrationCount = 0;
    calibrationGravityUsable = true;
    detail = "Nintendo · not calibrated";
  }

  public void calibrate() {
    reset();
    calibrating = true;
    sum = new double[3];
    gravitySum = new double[3];
    detail = "Hold still · collecting 50 samples";
  }

  public boolean calibrated() {
    return reference != null;
  }

  public double[] output(Telemetry t, int mode) {
    if (mode == 1 && bias != null) return gyro.clone();
    if (mode == 2 && bias != null) return fusion.clone();
    return t == null || t.quaternion == null
        ? identity()
        : reference == null ? t.quaternion.clone() : multiply(inverse(reference), t.quaternion);
  }

  public void update(Telemetry t, long now) {
    if (t.type != 2 || !t.valid) return;
    double dt = previousTime == 0 ? 0 : (now - previousTime) / 1e9;
    previousTime = now;
    if (dt <= 0 || dt > .1)
      dt = 0; // Arrival-based fallback; do not integrate across packet stalls.
    double step = dt / t.samples.size();
    for (Telemetry.Sample s : t.samples) {
      if (calibrating) {
        if (norm(s.gyro()) > 3 || t.quaternion == null) {
          calibrationCount = 0;
          calibrationGravityUsable = true;
          sum = new double[3];
          gravitySum = new double[3];
          detail = "Calibration paused · keep kart still";
          continue;
        }
        double[] gravity = s.accel().clone();
        gravity[2] -= 9.81;
        if ((t.flags & 0x20) == 0 || norm(gravity) < 4 || norm(gravity) > 18)
          calibrationGravityUsable = false;
        for (int raw : s.accelRaw()) if (Math.abs(raw) >= 32000) calibrationGravityUsable = false;
        for (int i = 0; i < 3; i++) {
          sum[i] += s.gyro()[i];
          gravitySum[i] += s.accel()[i] - (i == 2 ? 9.81 : 0);
        }
        calibrationCount++;
        if (calibrationCount >= 50) {
          reference = t.quaternion.clone();
          bias = new double[3];
          for (int i = 0; i < 3; i++) bias[i] = sum[i] / calibrationCount;
          // Gravity reference is only accepted in the high-precision mode.
          if (calibrationGravityUsable && (t.flags & 0x20) != 0) {
            double[] g = gravitySum.clone();
            for (int i = 0; i < 3; i++) g[i] /= calibrationCount;
            if (norm(g) >= 4 && norm(g) <= 18) gravityReference = g;
          }
          calibrating = false;
          gyro = identity();
          fusion = identity();
          detail = "Calibrated · experimental axes unvalidated";
        }
        continue;
      }
      if (bias == null || step == 0) continue;
      double[] rate = s.gyro().clone();
      for (int i = 0; i < 3; i++) rate[i] -= bias[i];
      gyro = integrate(gyro, rate, step);
      fusion = integrate(fusion, rate, step);
      double[] g = s.accel().clone();
      g[2] -= 9.81;
      double n = norm(g);
      boolean high = (t.flags & 0x20) != 0, clipping = false;
      for (int raw : s.accelRaw()) if (Math.abs(raw) >= 32000) clipping = true;
      boolean jump = false;
      if (previousGravity != null) {
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) d[i] = g[i] - previousGravity[i];
        jump = norm(d) > 6;
      }
      boolean transition = previousPrecision != null && previousPrecision != high;
      previousPrecision = high;
      if (!high || clipping || n < 4 || n > 18 || transition || jump) {
        previousGravity = null;
        detail = !high ? "Fusion: LOW_PRECISION_UNVALIDATED" : "Fusion: rejected acceleration";
        continue;
      }
      previousGravity = g.clone();
      if (gravityReference == null) {
        detail = "Fusion: no validated gravity reference · gyro only";
        continue;
      }
      if (norm(rate) > 12 || Math.abs(n / norm(gravityReference) - 1) > .15) {
        detail = "Fusion: dynamic acceleration rejected";
        continue;
      }
      double[] predicted = rotate(inverse(fusion), normalize(gravityReference)),
          observed = normalize(g);
      double[] cross = {
        predicted[1] * observed[2] - predicted[2] * observed[1],
        predicted[2] * observed[0] - predicted[0] * observed[2],
        predicted[0] * observed[1] - predicted[1] * observed[0]
      };
      double c = norm(cross);
      double correction =
          Math.min(.5 * Math.PI / 180, Math.asin(Math.min(1, c)) * Math.min(.08, step * 1.5));
      if (c > 1e-8) {
        double k = -Math.sin(correction / 2) / c;
        fusion =
            multiply(
                fusion,
                new double[] {cross[0] * k, cross[1] * k, cross[2] * k, Math.cos(correction / 2)});
      }
      detail = "Fusion experimental · gravity sign/heading unvalidated";
    }
  }
}
