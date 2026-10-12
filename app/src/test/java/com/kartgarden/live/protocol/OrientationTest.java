package com.kartgarden.live.protocol;

import static org.junit.Assert.*;

import java.nio.*;
import org.junit.Test;

public class OrientationTest {
  public static Telemetry packet(
      int index, boolean nativePresent, boolean high, int gyroRaw, int accelRaw) {
    byte[] bytes = new byte[145];
    bytes[0] = 2;
    bytes[3] = (byte) ((high ? 0x60 : 0x40) | 5);
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort(1, (short) (index * 6));
    b.putInt(5, index * 36000);
    if (nativePresent) b.putInt(21, 1 << 30);
    for (int i = 0; i < 6; i++) {
      b.putShort(73 + i * 12, (short) accelRaw);
      b.putShort(79 + i * 12, (short) gyroRaw);
    }
    return Telemetry.parse(bytes);
  }

  private Orientation ready(boolean nativePresent, boolean high) {
    Orientation o = new Orientation();
    for (int i = 0; i < 140; i++) {
      long now = i * 36_000_000L;
      if (i == 90) o.calibrate(now, true);
      o.update(packet(i, nativePresent, high, 0, 0), now);
    }
    assertTrue(o.calibrated());
    return o;
  }

  @Test
  public void startupInterruptionRecoversAndMoves() {
    for (boolean requestBeforeBias : new boolean[] {false, true}) {
      Orientation o = new Orientation();
      o.update(packet(0, true, true, 0, 0), 0);
      if (requestBeforeBias) o.calibrate(3_000_000_000L, true);
      for (int i = 1; i < 190; i++) {
        long now = 3_000_000_000L + i * 36_000_000L;
        if (!requestBeforeBias && i == 90) o.calibrate(now, true);
        o.update(packet(i, true, true, i >= 180 ? 8000 : 0, 0), now);
      }
      assertTrue(o.calibrated());
      assertTrue(Orientation.angles(o.output(null, Orientation.FUSION))[0] > 5);
    }
  }

  @Test
  public void identicalValuesAndLowPrecisionReachReadyWithoutNintendo() {
    for (boolean high : new boolean[] {false, true}) {
      Orientation o = ready(false, high);
      assertNotNull(o.output(null, Orientation.FUSION));
      assertNotNull(o.output(null, Orientation.GYROSCOPE));
      assertNull(o.output(packet(140, false, high, 0, 0), Orientation.NINTENDO));
    }
  }

  @Test
  public void unavailableSourceNeverSubstitutesNintendo() {
    Orientation o = new Orientation();
    Telemetry t = packet(0, true, true, 0, 0);
    o.update(t, 0);
    assertNull(o.output(t, Orientation.FUSION));
    assertNull(o.output(t, Orientation.NINTENDO));
    assertEquals("WAIT_SOURCE", o.snapshot(t, Orientation.FUSION, 0).status());
    assertEquals("CAL_REQUIRED", o.snapshot(t, Orientation.NINTENDO, 0).status());
  }

  @Test
  public void recalibrationPreservesBiasAndResetClearsIt() {
    Orientation o = ready(true, true);
    o.calibrate(140 * 36_000_000L, true);
    assertNull(o.output(null, Orientation.FUSION));
    for (int i = 140; i < 170; i++) o.update(packet(i, true, true, 0, 0), i * 36_000_000L);
    assertTrue(o.calibrated());
    o.reset();
    assertFalse(o.calibrated());
    assertNull(o.output(null, Orientation.FUSION));
  }

  @Test
  public void timeoutAndMissingTelemetryAreActionable() {
    Orientation o = new Orientation();
    o.calibrate(0, false);
    assertEquals(InstrumentCalibration.Phase.FAILED, o.calibration().phase());
    o.calibrate(0, true);
    o.tick(11_000_000_000L);
    assertEquals(InstrumentCalibration.Phase.FAILED, o.calibration().phase());
    assertTrue(o.calibration().message().contains("timed out"));
  }

  @Test
  public void clippedAccelerationStillPredictsButNeverFeedsG() {
    Orientation o = ready(true, true);
    o.update(packet(140, true, true, 20000, 32767), 140 * 36_000_000L);
    Orientation.Snapshot s = o.snapshot(null, Orientation.FUSION, 140 * 36_000_000L);
    assertEquals("READY", s.status());
    assertNull(s.linearAcceleration());
    assertTrue(Orientation.angles(s.relative())[0] > 1);
    assertEquals("RAW_CLIPPING", s.quality());
  }

  @Test
  public void clocksGapsWrapAndStaleData() {
    assertEquals(.006, Orientation.sampleDT(36000, 6, 6), 1e-12);
    assertEquals(.006, Orientation.sampleDT(72000, 12, 6), 1e-12);
    assertTrue(Double.isNaN(Orientation.sampleDT(36000, 0, 6)));
    assertTrue(Double.isNaN(Orientation.sampleDT(36000, 65530, 6)));
    assertTrue(Double.isNaN(Orientation.sampleDT(300000, 50, 6)));
    Orientation o = ready(true, true);
    o.update(packet(141, true, true, 0, 0), 141 * 36_000_000L);
    assertEquals("DEVICE_CLOCK_SAMPLE_GAP", o.snapshot(null, 2, 141 * 36_000_000L).timing());
    o.update(packet(141, true, true, 0, 0), 142 * 36_000_000L);
    assertNull(o.output(null, 2));
    o.update(packet(142, true, true, 0, 0), 143 * 36_000_000L);
    assertNotNull(o.output(null, 2));
    assertEquals("WAIT_SOURCE", o.snapshot(null, 2, 143 * 36_000_000L + 600_000_000L).status());
    assertTrue(o.calibrated());
    Orientation wrapped = ready(true, true);
    // A reset establishes a new clock baseline; subsequent unsigned clock/counter wraps work.
    wrapped.reset();
    for (int i = 0; i < 140; i++) {
      Telemetry t = packet(i, true, true, 0, 0);
      t.sampleCounter = (65520 + i * 6) & 65535;
      t.deviceClock = (0xffff0000L + i * 36000L) & 0xffffffffL;
      if (i == 90) wrapped.calibrate(i * 36_000_000L, true);
      wrapped.update(t, i * 36_000_000L);
    }
    assertTrue(wrapped.calibrated());
  }

  @Test
  public void forceQualityAndAntiparallelCorrection() {
    AccelerationQuality gate = new AccelerationQuality();
    assertEquals("ACCEPTED", gate.inspect(packet(0, true, true, 0, 0).latest(), true));
    assertEquals("MODE_TRANSITION", gate.inspect(packet(1, true, false, 0, 0).latest(), false));
    assertEquals("ACCEPTED", gate.inspect(packet(2, true, false, 0, 0).latest(), false));
    double[] q = Orientation.integrate(Orientation.identity(), new double[] {180, 0, 0}, 1);
    for (int i = 0; i < 1000; i++)
      q =
          Orientation.correctGravity(
                  q, new double[] {0, 0, -9.81}, new double[] {0, 0, -9.81}, .006)
              .quaternion();
    assertTrue(
        Orientation.norm(
                Orientation.subtract(
                    Orientation.rotate(q, new double[] {0, 0, -9.81}), new double[] {0, 0, -9.81}))
            < .01);
    double[] yaw = Orientation.integrate(Orientation.identity(), new double[] {0, 0, 90}, 1);
    assertArrayEquals(
        yaw,
        Orientation.correctGravity(
                yaw, new double[] {0, 0, -9.81}, new double[] {0, 0, -9.81}, .006)
            .quaternion(),
        1e-10);
    assertNull(Orientation.valid(new double[] {Double.NaN, 0, 0, 1}));
    double[] pitch = Orientation.integrate(Orientation.identity(), new double[] {20, 0, 0}, 1);
    assertEquals(20, Orientation.angles(pitch)[0], 1e-9);
    assertEquals(0, Orientation.angles(pitch)[1], 1e-9);
  }
}
