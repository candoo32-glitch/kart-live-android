package com.kartgarden.live.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Port of Swift Iteration 117's observed Nintendo LSP extraction rules. */
public final class LspAssembler {
  public record Frame(byte[] annexB, boolean damaged) {}

  private ByteArrayOutputStream current;
  private int expected, lastSequence = -1;
  private boolean damaged;
  public long packets, frames, gaps, abandoned, orphaned, lossGeneration;

  public void reset() {
    current = null;
    expected = 0;
    lastSequence = -1;
    lossGeneration++;
  }

  public Frame feed(byte[] original) {
    if (original.length < 28) return null;
    int seq = (u(original[3]) << 8) | u(original[4]);
    boolean gap = false;
    if (lastSequence >= 0) {
      int delta = (seq - lastSequence) & 65535;
      if (delta == 0 || delta >= 0x8000) return null;
      if (delta != 1) {
        gap = true;
        gaps++;
        lossGeneration++;
      }
    }
    lastSequence = seq;
    packets++;
    byte[] p = Arrays.copyOf(original, original.length - 8);
    int start = -1, length = 0;
    for (int fram : new int[] {13, 43}) {
      if (fram + 8 > p.length
          || p[fram] != 'F'
          || p[fram + 1] != 'R'
          || p[fram + 2] != 'A'
          || p[fram + 3] != 'M') continue;
      if (fram == 43 && !l2OK(p)) continue;
      long size = 0;
      for (int i = 0; i < 4; i++) size = (size << 8) | u(p[fram + 4 + i]);
      if (size <= 38 || size > 2 * 1024 * 1024) continue;
      int base = fram - 1, raw = base + u(p[base]) + 4;
      if (raw >= p.length || annexStart(p, raw) != raw) raw = annexStart(p, fram + 8);
      if (raw >= 0) {
        start = raw;
        length = (int) size - 38;
        break;
      }
    }
    int offset;
    if (start >= 0) {
      if (current != null) {
        abandoned++;
        lossGeneration++;
      }
      current = new ByteArrayOutputStream(length);
      expected = length;
      damaged = false;
      offset = start;
    } else {
      if (current == null) {
        orphaned++;
        return null;
      }
      damaged |= gap;
      int kind = u(p[11]);
      int skip =
          kind == 0xb6 || kind == 0xba || kind == 0xbe
              ? 5
              : kind == 0x85 || u(p[12]) == 0x72 ? 1 : 0;
      offset = 12 + skip;
    }
    int count = Math.min(expected - current.size(), p.length - offset);
    if (count > 0) current.write(p, offset, count);
    if (current.size() != expected) return null;
    Frame result = new Frame(current.toByteArray(), damaged);
    current = null;
    expected = 0;
    frames++;
    if (damaged) lossGeneration++;
    return result;
  }

  private static int u(byte b) {
    return b & 255;
  }

  private static boolean l2OK(byte[] p) {
    if (p.length < 22 || p[8] != 'L' || p[9] != '2') return false;
    long v = 0;
    for (int i = 12; i < 20; i++) v = (v << 8) | u(p[i]);
    long tail = v & 0xffffffffL;
    long folded =
        ((v >>> 48) & 0x7fff) ^ ((v >>> 32) & 0xffffffffL) ^ (tail >>> 16) ^ tail ^ 0xaaaaaaaaL;
    return ((u(p[10]) << 8) | u(p[11])) == (folded & 65535);
  }

  public static int marker(byte[] p, int i) {
    if (i + 4 <= p.length && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1) return 4;
    if (i + 3 <= p.length && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) return 3;
    return 0;
  }

  private static int annexStart(byte[] p, int from) {
    for (int i = Math.max(0, from); i < p.length; i++) {
      int m = marker(p, i);
      if (m > 0 && i + m < p.length) {
        int type = p[i + m] & 31;
        if (type == 1 || type == 5 || type == 7 || type == 8 || type == 9) return i;
      }
    }
    return -1;
  }

  /** NAL bytes including header, excluding Annex-B marker. */
  public static List<byte[]> nals(byte[] p) {
    List<byte[]> out = new ArrayList<>();
    int start = -1;
    for (int i = 0; i + 3 <= p.length; ) {
      int m = marker(p, i);
      if (m == 0) {
        i++;
        continue;
      }
      if (start >= 0 && i > start) out.add(Arrays.copyOfRange(p, start, i));
      start = i + m;
      i += m;
    }
    if (start >= 0 && start < p.length) out.add(Arrays.copyOfRange(p, start, p.length));
    return out;
  }

  public static List<byte[]> accessUnits(byte[] p) {
    List<Integer> aud = new ArrayList<>();
    int firstType = -1;
    for (int i = 0; i + 3 <= p.length; ) {
      int m = marker(p, i);
      if (m == 0) {
        i++;
        continue;
      }
      if (i + m < p.length) {
        int t = p[i + m] & 31;
        if (firstType < 0) firstType = t;
        if (t == 9) aud.add(i);
      }
      i += m;
    }
    List<byte[]> out = new ArrayList<>();
    if (firstType != 9 || aud.size() < 2) {
      out.add(p);
      return out;
    }
    for (int i = 0; i < aud.size(); i++)
      out.add(Arrays.copyOfRange(p, aud.get(i), i + 1 < aud.size() ? aud.get(i + 1) : p.length));
    return out;
  }
}
