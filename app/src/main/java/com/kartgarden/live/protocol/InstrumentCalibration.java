package com.kartgarden.live.protocol;

import java.util.ArrayList;
import java.util.List;

/** One packet-time calibration owner, matching Swift 122. */
public final class InstrumentCalibration {
  public enum Phase {
    REQUIRED,
    SAMPLING,
    READY,
    FAILED
  }

  private Phase phase = Phase.REQUIRED;
  private String message = "Calibrate on the level surface";
  private double[] nintendoReference, levelForce;
  private long requestedAt;
  private final List<double[]> forces = new ArrayList<>();

  public Phase phase() {
    return phase;
  }

  public String message() {
    return message;
  }

  public boolean pending() {
    return phase == Phase.SAMPLING;
  }

  public double[] nintendoReference() {
    return copy(nintendoReference);
  }

  public double[] levelForce() {
    return copy(levelForce);
  }

  private static double[] copy(double[] value) {
    return value == null ? null : value.clone();
  }

  public void reset() {
    phase = Phase.REQUIRED;
    message = "Calibrate on the level surface";
    nintendoReference = levelForce = null;
    forces.clear();
  }

  public void request(long now) {
    reset();
    phase = Phase.SAMPLING;
    requestedAt = now;
    message = "Hold kart level and still · acquiring bias and reference";
  }

  public void reject(String reason) {
    phase = Phase.FAILED;
    message = reason;
    forces.clear();
  }

  public void tick(long now) {
    if (pending() && now - requestedAt >= 10_000_000_000L)
      reject("Calibration timed out · " + message + "; retry");
  }

  private void waitFor(String reason) {
    forces.clear();
    message = reason;
  }

  public void observe(
      Telemetry t,
      double[] bias,
      boolean timingValid,
      boolean qualityAccepted,
      boolean gravityReady,
      long now) {
    tick(now);
    if (!pending()) return;
    if (bias == null) {
      waitFor("Waiting for gyro bias · keep kart still");
      return;
    }
    if (!timingValid) {
      waitFor("Waiting for valid IMU timing");
      return;
    }
    Telemetry.Sample sample = t.latest();
    double[] force = sample == null ? null : sample.specificForce();
    if (!qualityAccepted || !Orientation.finite(force, 3)) {
      waitFor("Waiting for reliable acceleration");
      return;
    }
    if (!Orientation.finite(sample.gyro(), 3)
        || Orientation.norm(Orientation.subtract(sample.gyro(), bias)) >= 3) {
      waitFor("Kart rotating · hold level and still");
      return;
    }
    if (!gravityReady) {
      waitFor("Acquiring gravity reference · hold still");
      return;
    }
    forces.add(force);
    if (forces.size() > 16) forces.remove(0);
    double[] mean = new double[3];
    for (double[] v : forces) for (int i = 0; i < 3; i++) mean[i] += v[i];
    for (int i = 0; i < 3; i++) mean[i] /= forces.size();
    double spread = 0;
    for (double[] v : forces) for (int i = 0; i < 3; i++) spread += Math.pow(v[i] - mean[i], 2);
    if (Math.sqrt(spread / forces.size()) >= .15) {
      waitFor("Kart moving · hold level and still");
      return;
    }
    message = "Calibrating all instruments · " + forces.size() + "/16 packets";
    if (forces.size() != 16) return;
    nintendoReference = Orientation.valid(t.quaternion);
    levelForce = mean;
    phase = Phase.READY;
    message =
        nintendoReference == null
            ? "Fusion and Gyroscope ready · Nintendo quaternion unavailable"
            : "All instrument references ready";
  }
}
