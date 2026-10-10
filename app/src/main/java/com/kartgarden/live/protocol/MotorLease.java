package com.kartgarden.live.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Pure protocol/counter gate. All methods are used under the session motor lock. */
public final class MotorLease {
  private long next, end;
  private boolean active;

  public void arm(long start, long count) {
    if (start < 0 || start > 0xffffffffL || count <= 0 || count > 0x100000000L - start)
      throw new IllegalArgumentException("Invalid motor counter reservation");
    next = start;
    end = start + count;
    active = true;
  }

  public void clear() {
    active = false;
  }

  public boolean active() {
    return active && next < end;
  }

  public byte[] packet(float steer, float throttle) {
    if (!active() || !Float.isFinite(steer) || !Float.isFinite(throttle)) {
      clear();
      return null;
    }
    byte[] p = new byte[32];
    p[0] = (byte) Math.round(Math.max(-1, Math.min(1, throttle)) * 127);
    p[1] = (byte) Math.round(Math.max(-1, Math.min(1, steer)) * 127);
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(4, (int) next++);
    return p;
  }

  public static boolean safe(
      boolean foreground,
      boolean freshSession,
      long imuAgeMs,
      float steer,
      float throttle,
      long inputAgeMs) {
    return foreground
        && freshSession
        && imuAgeMs >= 0
        && imuAgeMs < 1000
        && Float.isFinite(steer)
        && Float.isFinite(throttle)
        && ((Math.abs(steer) < .05 && Math.abs(throttle) < .05) || inputAgeMs <= 500);
  }
}
