package com.kartgarden.live.protocol;

import java.util.ArrayList;
import java.util.List;

/** Swift 122 packet-time attitude/calibration port. Never feeds motor controls. */
public final class Orientation {
  public static final int NINTENDO = 0, GYROSCOPE = 1, FUSION = 2;
  public static final String[] SOURCES = {"Nintendo", "Gyroscope", "Fusion"};

  public static double[] identity() {
    return new double[] {0, 0, 0, 1};
  }

  public static boolean finite(double[] v, int count) {
    if (v == null || v.length != count) return false;
    for (double x : v) if (!Double.isFinite(x)) return false;
    return true;
  }

  public static double[] valid(double[] q) {
    return finite(q, 4) && norm(q) >= .5 && norm(q) <= 1.5 ? normalize(q) : null;
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
    if (!Double.isFinite(n) || n < 1e-12) return identity();
    double[] out = q.clone();
    for (int i = 0; i < out.length; i++) out[i] /= n;
    return out;
  }

  public static double norm(double[] v) {
    double n = 0;
    for (double x : v) n += x * x;
    return Math.sqrt(n);
  }

  public static double[] subtract(double[] a, double[] b) {
    double[] out = new double[a.length];
    for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
    return out;
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
    if (!finite(dps, 3) || !Double.isFinite(dt) || dt <= 0) return q;
    double[] omega = {dps[0] * Math.PI / 180, dps[1] * Math.PI / 180, dps[2] * Math.PI / 180};
    double speed = norm(omega);
    if (speed <= 1e-10) return q;
    double half = speed * dt / 2, k = Math.sin(half) / speed;
    return multiply(q, new double[] {omega[0] * k, omega[1] * k, omega[2] * k, Math.cos(half)});
  }

  private static double[] cross(double[] a, double[] b) {
    return new double[] {
      a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    };
  }

  public record Correction(double[] quaternion, double residual, double step) {}

  public static Correction correctGravity(
      double[] q, double[] measured, double[] world, double dt) {
    double m = norm(measured), r = norm(world);
    if (m <= 1e-8 || r <= 1e-8) return new Correction(q, 0, 0);
    double[] unit = new double[3], target = new double[3];
    for (int i = 0; i < 3; i++) {
      unit[i] = measured[i] / m;
      target[i] = world[i] / r;
    }
    double[] u = rotate(q, unit), axis = cross(u, target);
    double sine = norm(axis), dot = 0;
    for (int i = 0; i < 3; i++) dot += u[i] * target[i];
    double cosine = Math.max(-1, Math.min(1, dot)), angle = Math.atan2(sine, cosine);
    if (sine < 1e-10) {
      if (cosine >= 0) return new Correction(q, angle, 0);
      int smallest = 0;
      for (int i = 1; i < 3; i++) if (Math.abs(u[i]) < Math.abs(u[smallest])) smallest = i;
      double[] basis = new double[3];
      basis[smallest] = 1;
      axis = cross(u, basis);
    }
    double length = norm(axis),
        step = angle * (1 - Math.exp(-Math.max(0, dt) / .6)),
        k = Math.sin(step / 2) / length;
    // Correction axis is in world coordinates: LEFT multiply.
    return new Correction(
        multiply(new double[] {axis[0] * k, axis[1] * k, axis[2] * k, Math.cos(step / 2)}, q),
        angle,
        step);
  }

  public static double sampleDT(long clockDelta, int counterDelta, int count) {
    if (count <= 0
        || count > 8
        || counterDelta < count
        || counterDelta >= 32768
        || clockDelta <= 0
        || clockDelta > 200_000) return Double.NaN;
    double dt = clockDelta * 1e-6 / counterDelta;
    return dt >= .004 && dt <= .008 ? dt : Double.NaN;
  }

  public static double[] sceneQuaternion(double[] q) {
    return new double[] {q[0], -q[2], q[1], q[3]};
  }

  public static double[] angles(double[] relative) {
    double[] q = sceneQuaternion(relative),
        right = rotate(q, new double[] {1, 0, 0}),
        up = rotate(q, new double[] {0, 1, 0}),
        forward = rotate(q, new double[] {0, 0, -1});
    return new double[] {
      Math.asin(Math.max(-1, Math.min(1, forward[1]))) * 180 / Math.PI,
      Math.atan2(right[1], up[1]) * 180 / Math.PI
    };
  }

  public record Snapshot(
      String source,
      String status,
      String detail,
      double[] relative,
      double[] linearAcceleration,
      String calibrationPhase,
      String timing,
      String quality,
      int accepted,
      int rejected) {}

  private final InstrumentCalibration calibration = new InstrumentCalibration();
  private final AccelerationQuality quality = new AccelerationQuality();
  private double[] biasSum = new double[3],
      bias,
      adaptiveBias,
      gyro = identity(),
      fusion = identity();
  private double[] gravitySum = new double[3], gravityReference, gyroOrigin, fusionOrigin;
  private double[] liveGyro, liveFusion, liveLinear;
  private int biasCount, gravityCount;
  private Long startedAt, previousClock, lastImu;
  private Integer previousCounter;
  private double residual = Double.NaN;
  private final List<double[]> stationaryGyro = new ArrayList<>(),
      stationaryForce = new ArrayList<>();
  private String timing = "WAIT_BIAS", qualityStatus = "NOT_EVALUATED";
  private int accepted, rejected;
  public String detail = "Calibrate on the level surface";

  public InstrumentCalibration calibration() {
    return calibration;
  }

  public void reset() {
    calibration.reset();
    quality.reset();
    biasSum = new double[3];
    bias = adaptiveBias = null;
    biasCount = 0;
    gyro = identity();
    fusion = identity();
    gravitySum = new double[3];
    gravityCount = 0;
    gravityReference = gyroOrigin = fusionOrigin = liveGyro = liveFusion = liveLinear = null;
    startedAt = previousClock = lastImu = null;
    previousCounter = null;
    residual = Double.NaN;
    stationaryGyro.clear();
    stationaryForce.clear();
    timing = "WAIT_BIAS";
    qualityStatus = "NOT_EVALUATED";
    accepted = rejected = 0;
    detail = calibration.message();
  }

  public void calibrate() {
    calibrate(System.nanoTime(), true);
  }

  public void calibrate(long now, boolean telemetryAvailable) {
    if (bias == null) {
      biasSum = new double[3];
      biasCount = 0;
      startedAt = now;
    }
    gyroOrigin = fusionOrigin = liveGyro = liveFusion = liveLinear = null;
    calibration.request(now);
    if (!telemetryAvailable)
      calibration.reject("Cannot calibrate · waiting for live IMU telemetry");
    detail = calibration.message();
  }

  public boolean calibrated() {
    return calibration.phase() == InstrumentCalibration.Phase.READY;
  }

  public void tick(long now) {
    calibration.tick(now);
    if (calibration.phase() == InstrumentCalibration.Phase.FAILED) {
      liveGyro = liveFusion = liveLinear = null;
      detail = calibration.message();
    }
    if (lastImu == null || now - lastImu >= 500_000_000L) liveGyro = liveFusion = liveLinear = null;
  }

  public double[] output(Telemetry t, int mode) {
    if (mode == GYROSCOPE) return copy(liveGyro);
    if (mode == FUSION) return copy(liveFusion);
    double[] q = t == null ? null : valid(t.quaternion), ref = calibration.nintendoReference();
    return q == null || ref == null ? null : multiply(inverse(ref), q);
  }

  public Snapshot snapshot(Telemetry t, int mode, long now) {
    tick(now);
    double[] relative = output(t, mode);
    boolean fresh = lastImu != null && now - lastImu < 500_000_000L;
    if (!fresh) relative = null;
    String status =
        relative != null
            ? "READY"
            : mode == NINTENDO && fresh && t != null && valid(t.quaternion) != null
                ? "CAL_REQUIRED"
                : "WAIT_SOURCE";
    String message = calibration.message();
    if (calibrated() && !status.equals("READY"))
      message =
          !fresh
              ? "Waiting for live IMU telemetry"
              : mode == NINTENDO
                  ? "Nintendo quaternion unavailable"
                  : "Waiting for valid IMU timing · " + timing;
    return new Snapshot(
        SOURCES[mode],
        status,
        message,
        relative,
        liveFusion == null ? null : copy(liveLinear),
        calibration.phase().name(),
        timing,
        qualityStatus,
        accepted,
        rejected);
  }

  private static double[] copy(double[] v) {
    return v == null ? null : v.clone();
  }

  private double[] activeBias() {
    return adaptiveBias == null ? bias : adaptiveBias;
  }

  public void update(Telemetry t, long now) {
    if (t.type != 2 || !t.valid || t.latest() == null) return;
    lastImu = now;
    if (startedAt == null) startedAt = now;
    double elapsed = (now - startedAt) / 1e9;
    double[] lastGyro = t.latest().gyro();
    if (finite(lastGyro, 3)) {
      if (bias == null && (elapsed < 2 || biasCount < 10)) {
        for (int i = 0; i < 3; i++) biasSum[i] += lastGyro[i];
        biasCount++;
      } else if (bias == null && biasCount >= 10) {
        bias = new double[3];
        for (int i = 0; i < 3; i++) bias[i] = biasSum[i] / biasCount;
      }
    }
    accepted = rejected = 0;
    qualityStatus = "NOT_EVALUATED";
    double dt =
        previousClock == null || previousCounter == null
            ? Double.NaN
            : sampleDT(
                (t.deviceClock - previousClock) & 0xffffffffL,
                (t.sampleCounter - previousCounter) & 65535,
                t.samples.size());
    int counterDelta = previousCounter == null ? 0 : (t.sampleCounter - previousCounter) & 65535;
    if (bias != null && Double.isFinite(dt)) {
      for (Telemetry.Sample s : t.samples) {
        double[] rate = subtract(s.gyro(), activeBias());
        gyro = integrate(gyro, rate, dt);
        fusion = integrate(fusion, rate, dt);
        qualityStatus = quality.inspect(s, (t.flags & 0x20) != 0);
        double[] force = s.specificForce();
        if (qualityStatus.equals("ACCEPTED")) {
          accepted++;
          updateAdaptiveBias(s.gyro(), force);
          correctTilt(force, s.gyro(), dt);
        } else {
          rejected++;
          stationaryGyro.clear();
          stationaryForce.clear();
        }
        boolean clipping = false;
        for (int raw : s.accelRaw()) if (Math.abs(raw) >= 32000) clipping = true;
        if (gravityReference != null && !clipping) {
          double[] world = gravityReference.clone();
          double factor = 9.81 / norm(world);
          for (int i = 0; i < 3; i++) world[i] *= factor;
          liveLinear = subtract(force, rotate(inverse(fusion), world));
        } else liveLinear = null;
      }
      timing =
          counterDelta == t.samples.size() ? "DEVICE_CLOCK_SUBSAMPLES" : "DEVICE_CLOCK_SAMPLE_GAP";
    } else {
      timing = bias == null ? "WAIT_BIAS" : "CLOCK_COUNTER_REJECTED";
      liveLinear = null;
      stationaryGyro.clear();
      stationaryForce.clear();
    }
    previousClock = t.deviceClock;
    previousCounter = t.sampleCounter;
    boolean timingValid =
        timing.equals("DEVICE_CLOCK_SUBSAMPLES") || timing.equals("DEVICE_CLOCK_SAMPLE_GAP");
    calibration.observe(
        t,
        activeBias(),
        timingValid,
        accepted > 0 && qualityStatus.equals("ACCEPTED"),
        gravityReference != null && residual < 1,
        now);
    tick(now);
    if (calibrated()
        && gyroOrigin == null
        && bias != null
        && gravityReference != null
        && accepted > 0
        && timingValid) {
      gyroOrigin = gyro.clone();
      fusionOrigin = fusion.clone();
    }
    liveGyro = liveFusion = null;
    if (calibrated() && gyroOrigin != null && timingValid) {
      liveGyro = multiply(inverse(gyroOrigin), gyro);
      liveFusion = multiply(inverse(fusionOrigin), fusion);
    }
    detail = calibration.message();
  }

  private void correctTilt(double[] force, double[] rawGyro, double dt) {
    if (!finite(force, 3) || !finite(rawGyro, 3)) return;
    double speed = norm(subtract(rawGyro, activeBias()));
    if (gravityReference == null) {
      if (speed < 3) {
        double[] world = rotate(fusion, force);
        for (int i = 0; i < 3; i++) gravitySum[i] += world[i];
        gravityCount++;
        if (gravityCount >= 10) {
          gravityReference = gravitySum.clone();
          for (int i = 0; i < 3; i++) gravityReference[i] /= gravityCount;
        }
      } else {
        gravitySum = new double[3];
        gravityCount = 0;
      }
      return;
    }
    if (speed >= 12) return;
    Correction correction = correctGravity(fusion, force, gravityReference, dt);
    fusion = correction.quaternion();
    residual = correction.residual() * 180 / Math.PI;
  }

  private void updateAdaptiveBias(double[] rawGyro, double[] force) {
    if (bias == null || !finite(rawGyro, 3) || !finite(force, 3)) return;
    if (adaptiveBias == null) adaptiveBias = bias.clone();
    stationaryGyro.add(rawGyro.clone());
    stationaryForce.add(force.clone());
    if (stationaryGyro.size() > 48) {
      stationaryGyro.remove(0);
      stationaryForce.remove(0);
    }
    int n = stationaryGyro.size();
    if (n < 36) return;
    double[] means = new double[3], forceMean = new double[3];
    double magnitudeMean = 0;
    for (int k = 0; k < n; k++) {
      for (int i = 0; i < 3; i++) {
        means[i] += stationaryGyro.get(k)[i];
        forceMean[i] += stationaryForce.get(k)[i];
      }
      magnitudeMean += norm(stationaryForce.get(k));
    }
    for (int i = 0; i < 3; i++) {
      means[i] /= n;
      forceMean[i] /= n;
    }
    magnitudeMean /= n;
    double[] variance = new double[3];
    double magnitudeVariance = 0, directionVariance = 0;
    for (int k = 0; k < n; k++) {
      for (int i = 0; i < 3; i++) {
        variance[i] += Math.pow(stationaryGyro.get(k)[i] - means[i], 2);
        directionVariance += Math.pow(stationaryForce.get(k)[i] - forceMean[i], 2);
      }
      magnitudeVariance += Math.pow(norm(stationaryForce.get(k)) - magnitudeMean, 2);
    }
    double maxStd = 0;
    for (double v : variance) maxStd = Math.max(maxStd, Math.sqrt(v / n));
    if (maxStd >= .40
        || Math.sqrt(magnitudeVariance / n) >= .45
        || Math.sqrt(directionVariance / n) >= .45
        || norm(subtract(means, adaptiveBias)) >= 1) return;
    // The window validates stillness; Swift's EWMA uses the current raw sample.
    for (int i = 0; i < 3; i++) adaptiveBias[i] = .998 * adaptiveBias[i] + .002 * rawGyro[i];
  }
}
