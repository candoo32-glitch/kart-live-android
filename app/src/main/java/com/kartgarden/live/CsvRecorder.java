package com.kartgarden.live;

import com.kartgarden.live.protocol.Orientation;
import com.kartgarden.live.protocol.Telemetry;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.util.Locale;

/** Bounded, opt-in private capture. Includes each IMU subsample and original packet bytes. */
public final class CsvRecorder {
  private final File file;
  private BufferedWriter writer;
  private long rows, bytes;
  public volatile String state = "CSV recording off";

  public CsvRecorder(File directory) {
    file = new File(directory, "KartLive-Diagnostics.csv");
  }

  public synchronized void start() throws Exception {
    stop();
    writer = new BufferedWriter(new FileWriter(file, false));
    rows = bytes = 0;
    writer.write(
        "utc,packet_type,sample_index,sample_counter,device_clock,flags,accel_raw_x,accel_raw_y,accel_raw_z,gyro_raw_x,gyro_raw_y,gyro_raw_z,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,q_x,q_y,q_z,q_w,battery_bars,cable,candidate_force_x,candidate_force_y,candidate_force_z,candidate_force_norm,calibration_phase,instrument_source,instrument_status,instrument_relative_x,instrument_relative_y,instrument_relative_z,instrument_relative_w,instrument_pitch_deg,instrument_roll_deg,linear_x,linear_y,linear_z,sample_status,accel_quality_last,accel_quality_accepted,accel_quality_rejected,swift_fusion_iteration,raw_packet_hex\n");
    state = "Recording · 0 rows";
  }

  public synchronized boolean active() {
    return writer != null;
  }

  public synchronized void append(Telemetry t, byte[] raw, Orientation.Snapshot instrument) {
    if (writer == null) return;
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : raw) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
      int count = Math.max(1, t.samples.size());
      for (int index = 0; index < count; index++) {
        StringBuilder row =
            new StringBuilder(
                Instant.now()
                    + ","
                    + t.type
                    + ","
                    + index
                    + ","
                    + t.sampleCounter
                    + ","
                    + t.deviceClock
                    + ","
                    + t.flags);
        if (t.samples.isEmpty()) {
          for (int j = 0; j < 12; j++) row.append(',');
        } else {
          Telemetry.Sample s = t.samples.get(index);
          for (int v : s.accelRaw()) row.append(',').append(v);
          for (int v : s.gyroRaw()) row.append(',').append(v);
          for (double v : s.accel()) row.append(',').append(v);
          for (double v : s.gyro()) row.append(',').append(v);
        }
        for (int j = 0; j < 4; j++) {
          row.append(',');
          if (t.quaternion != null) row.append(t.quaternion[j]);
        }
        row.append(',').append(t.battery < 0 ? "" : t.battery).append(',').append(t.cable);
        double[] force = t.samples.isEmpty() ? null : t.samples.get(index).specificForce();
        appendVector(row, force, 3);
        row.append(',').append(force == null ? "" : Orientation.norm(force));
        row.append(',')
            .append(instrument.calibrationPhase())
            .append(',')
            .append(instrument.source())
            .append(',')
            .append(instrument.status());
        appendVector(row, instrument.relative(), 4);
        double[] angles =
            instrument.relative() == null ? null : Orientation.angles(instrument.relative());
        appendVector(row, angles, 2);
        appendVector(row, instrument.linearAcceleration(), 3);
        row.append(',')
            .append(instrument.timing())
            .append(',')
            .append(instrument.quality())
            .append(',')
            .append(instrument.accepted())
            .append(',')
            .append(instrument.rejected())
            .append(",122,")
            .append(hex)
            .append('\n');
        bytes += row.length();
        if (bytes > 20 * 1024 * 1024) {
          stop();
          state = "Recording stopped · 20 MB limit";
          return;
        }
        writer.write(row.toString());
        rows++;
      }
      if (rows % 100 == 0) writer.flush();
      state = "Recording · " + rows + " rows";
    } catch (Exception e) {
      stop();
      state = "CSV write failed";
    }
  }

  private static void appendVector(StringBuilder row, double[] vector, int count) {
    for (int i = 0; i < count; i++) {
      row.append(',');
      if (vector != null && Double.isFinite(vector[i])) row.append(vector[i]);
    }
  }

  public synchronized File export() throws Exception {
    if (writer != null) writer.flush();
    if (!file.exists()) throw new IllegalStateException("Record telemetry before exporting");
    return file;
  }

  public synchronized void stop() {
    if (writer != null)
      try {
        writer.close();
      } catch (Exception ignored) {
      }
    writer = null;
    state = "Recording stopped · " + rows + " rows";
  }
}
