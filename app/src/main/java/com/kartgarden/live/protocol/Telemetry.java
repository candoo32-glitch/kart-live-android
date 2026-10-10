package com.kartgarden.live.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class Telemetry {
  public record Sample(int[] accelRaw, int[] gyroRaw, double[] accel, double[] gyro) {}

  public int type, flags, battery = -1, sampleCounter;
  public long deviceClock;
  public boolean valid, cable;
  public double[] quaternion;
  public double accelScale, gyroScale;
  public final List<Sample> samples = new ArrayList<>();

  public static Telemetry parse(byte[] p) {
    Telemetry t = new Telemetry();
    if (p.length == 0) return t;
    t.type = p[0] & 255;
    if (t.type == 1) {
      if (p.length < 5 || (p[4] & 255) > 4) return t;
      t.valid = true;
      t.battery = p[4] & 255;
      t.cable = (p[1] & 1) != 0;
      t.flags = p[1] & 255;
    } else if (t.type == 2) {
      if (p.length < 85) return t;
      t.flags = p[3] & 255;
      int n = (t.flags & 7) + 1;
      if (p.length < 73 + 12 * n) return t;
      ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
      t.sampleCounter = b.getShort(1) & 65535;
      t.deviceClock = b.getInt(5) & 0xffffffffL;
      t.accelScale = ((t.flags & 0x20) != 0 ? 1 : 15.99) * 9.81 / 4096;
      t.gyroScale = (t.flags & 0x40) != 0 ? 0.004375 : 0.1;
      double[] q = new double[4];
      double norm = 0;
      for (int i = 0; i < 4; i++) {
        q[i] = b.getInt(9 + 4 * i) / 1073741824.0;
        norm += q[i] * q[i];
      }
      norm = Math.sqrt(norm);
      if (norm > .5 && norm < 1.5) {
        for (int i = 0; i < 4; i++) q[i] /= norm;
        t.quaternion = q;
      }
      for (int i = 0; i < n; i++) {
        int off = 73 + 12 * i;
        int[] a = new int[3], g = new int[3];
        double[] accel = new double[3], gyro = new double[3];
        for (int axis = 0; axis < 3; axis++) {
          a[axis] = b.getShort(off + 2 * axis);
          g[axis] = b.getShort(off + 6 + 2 * axis);
          accel[axis] = a[axis] * t.accelScale;
          gyro[axis] = g[axis] * t.gyroScale;
        }
        t.samples.add(new Sample(a, g, accel, gyro));
      }
      t.valid = true;
    } else if (t.type == 3) t.valid = true;
    return t;
  }

  public Sample latest() {
    return samples.isEmpty() ? null : samples.get(samples.size() - 1);
  }
}
