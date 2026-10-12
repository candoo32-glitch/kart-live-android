import com.kartgarden.live.protocol.*;
import java.io.*;
import java.util.*;

/** Feeds native packets to the production Android estimator. Input: seconds<TAB>hex. */
public final class ReplayInstruments {
  public static void main(String[] args) throws Exception {
    Orientation orientation = new Orientation();
    Double first = null;
    boolean requested = false;
    String previousPhase = "";
    int packets = 0;
    System.out.println(
        "elapsed,packet,phase,status,pitch_deg,roll_deg,linear_g,q_x,q_y,q_z,q_w,timing");
    try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
      for (String line; (line = in.readLine()) != null; ) {
        String[] fields = line.split("\\t", -1);
        double seconds = Double.parseDouble(fields[0]);
        if (first == null) first = seconds;
        double elapsed = seconds - first;
        long now = Math.round(seconds * 1e9);
        if (!requested && elapsed >= 3) {
          orientation.calibrate(now, true);
          requested = true;
        }
        byte[] bytes = HexFormat.of().parseHex(fields[1]);
        Telemetry t = Telemetry.parse(bytes);
        orientation.update(t, now);
        packets++;
        Orientation.Snapshot snapshot = orientation.snapshot(t, Orientation.FUSION, now);
        String phase = snapshot.calibrationPhase();
        if (packets % 5 == 0 || !phase.equals(previousPhase)) {
          double[] q = snapshot.relative(), angles = q == null ? null : Orientation.angles(q);
          System.out.printf(
              Locale.ROOT, "%.9f,%d,%s,%s", elapsed, packets, phase, snapshot.status());
          double[] linear = snapshot.linearAcceleration();
          for (Double v :
              new Double[] {
                angles == null ? null : angles[0],
                angles == null ? null : angles[1],
                linear == null ? null : Orientation.norm(linear) / 9.81
              }) System.out.print(v == null ? "," : "," + v);
          for (int i = 0; i < 4; i++) System.out.print(q == null ? "," : "," + q[i]);
          System.out.println("," + snapshot.timing());
        }
        previousPhase = phase;
      }
    }
  }
}
