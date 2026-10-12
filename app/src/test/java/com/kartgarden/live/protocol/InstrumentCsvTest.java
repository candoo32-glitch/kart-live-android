package com.kartgarden.live.protocol;

import static org.junit.Assert.*;

import com.kartgarden.live.CsvRecorder;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class InstrumentCsvTest {
  @Test
  public void selectedSourceAndMissingValuesAreExportedWithoutResettingCalibration()
      throws Exception {
    Path dir = Files.createTempDirectory("kart-csv-test");
    try {
      Orientation orientation = new Orientation();
      for (int i = 0; i < 140; i++) {
        long now = i * 36_000_000L;
        if (i == 90) orientation.calibrate(now, true);
        orientation.update(OrientationTest.packet(i, false, true, 0, 0), now);
      }
      double[] before = orientation.output(null, Orientation.FUSION);
      CsvRecorder recorder = new CsvRecorder(dir.toFile());
      recorder.start();
      Telemetry t = OrientationTest.packet(140, false, true, 0, 0);
      recorder.append(
          t, new byte[] {2}, orientation.snapshot(t, Orientation.FUSION, 139 * 36_000_000L));
      recorder.append(
          t, new byte[] {2}, orientation.snapshot(t, Orientation.NINTENDO, 139 * 36_000_000L));
      recorder.stop();
      assertArrayEquals(before, orientation.output(null, Orientation.FUSION), 0);
      List<String> lines = Files.readAllLines(recorder.export().toPath());
      List<String> keys = Arrays.asList(lines.get(0).split(",", -1));
      assertEquals(46, keys.size());
      String[] fusion = lines.get(1).split(",", -1), nativeRow = lines.get(7).split(",", -1);
      assertEquals(46, fusion.length);
      assertEquals(46, nativeRow.length);
      assertEquals("Fusion", fusion[keys.indexOf("instrument_source")]);
      assertEquals("READY", fusion[keys.indexOf("instrument_status")]);
      assertFalse(fusion[keys.indexOf("instrument_pitch_deg")].isEmpty());
      assertEquals("Nintendo", nativeRow[keys.indexOf("instrument_source")]);
      assertEquals("WAIT_SOURCE", nativeRow[keys.indexOf("instrument_status")]);
      assertEquals("", nativeRow[keys.indexOf("instrument_pitch_deg")]);
      assertEquals("122", fusion[keys.indexOf("swift_fusion_iteration")]);
    } finally {
      try (var paths = Files.walk(dir)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }
}
