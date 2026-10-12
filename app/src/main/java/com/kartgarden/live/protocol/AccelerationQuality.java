package com.kartgarden.live.protocol;

/** Swift 122 candidate-force gate. Rejection never gates valid gyro prediction. */
public final class AccelerationQuality {
  private Boolean previousMode, previousAcceptedMode;
  private double[] previousAcceptedVector;

  public void reset() {
    previousMode = previousAcceptedMode = null;
    previousAcceptedVector = null;
  }

  public String inspect(Telemetry.Sample sample, boolean high) {
    Boolean previous = previousMode;
    previousMode = high;
    double[] force = sample.specificForce();
    if (!Orientation.finite(force, 3) || sample.accelRaw().length != 3) return "NONFINITE";
    for (int raw : sample.accelRaw()) if (Math.abs(raw) >= 32000) return "RAW_CLIPPING";
    if (Math.abs(Orientation.norm(force) - 9.81) > .15 * 9.81) {
      previousAcceptedVector = null;
      return "GRAVITY_MAGNITUDE";
    }
    if (previous != null && previous != high) {
      previousAcceptedVector = null;
      previousAcceptedMode = null;
      return "MODE_TRANSITION";
    }
    if (previousAcceptedMode != null
        && previousAcceptedMode == high
        && previousAcceptedVector != null
        && Orientation.norm(Orientation.subtract(force, previousAcceptedVector)) > 1) {
      previousAcceptedVector = null;
      previousAcceptedMode = null;
      return "VECTOR_DISCONTINUITY";
    }
    previousAcceptedMode = high;
    previousAcceptedVector = force;
    return "ACCEPTED";
  }
}
