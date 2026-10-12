package com.kartgarden.live;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kartgarden.live.protocol.*;
import java.net.*;
import java.nio.*;
import java.util.Set;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real localhost UDP -> session -> production fusion -> Activity -> rendered pixels. No
 * gateway/motors.
 */
@RunWith(AndroidJUnit4.class)
public class InstrumentPipelineTest {
  private Button button(View v, String label) {
    if (v instanceof Button b && label.contentEquals(b.getText())) return b;
    if (v instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = button(group.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static byte[] packet(int index, double angle, double delta) {
    byte[] p = new byte[145];
    p[0] = 2;
    p[3] = 0x65;
    ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort(1, (short) (index * 6));
    b.putInt(5, index * 36000);
    // Nintendo deliberately missing: experimental readiness must be independent.
    double rad = Math.toRadians(angle);
    for (int i = 0; i < 6; i++) {
      b.putShort(75 + i * 12, (short) Math.round(-Math.sin(rad) * 65536));
      b.putShort(77 + i * 12, (short) Math.round((1 - Math.cos(rad)) * 65536));
      b.putShort(79 + i * 12, (short) Math.round(delta / .036 / .004375));
    }
    return p;
  }

  private Bitmap image(ActivityScenario<MainActivity> scenario) {
    AtomicReference<Bitmap> result = new AtomicReference<>();
    scenario.onActivity(
        a -> {
          try {
            var f = MainActivity.class.getDeclaredField("instrument");
            f.setAccessible(true);
            InstrumentView v = (InstrumentView) f.get(a);
            assertTrue(v.getWidth() > 0 && v.getHeight() > 0);
            Bitmap bitmap =
                Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bitmap));
            result.set(bitmap);
            assertTrue(v.getContentDescription().toString().contains("Fusion · READY"));
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
    return result.get();
  }

  private void awaitPitch(KartSession.Connection c, double expected) throws Exception {
    long end = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < end) {
      synchronized (c.orientation) {
        double[] q = c.orientation.output(c.latest, Orientation.FUSION);
        if (q != null && Math.abs(Orientation.angles(q)[0] - expected) < 3) return;
      }
      Thread.sleep(30);
    }
    throw new AssertionError("Instrument target did not reach pitch " + expected);
  }

  private void screenshot(String name) throws Exception {
    try (var fd =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("screencap -p /data/local/tmp/kart-live-" + name + ".png");
        var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      byte[] bytes = new byte[1024];
      while (in.read(bytes) != -1) {}
    }
  }

  @Test
  public void calibrationAndMotionReachVisibleInstrumentsWithoutNintendo() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        DatagramSocket sender =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      AtomicReference<KartSession> session = new AtomicReference<>();
      scenario.onActivity(
          a -> {
            try {
              var f = MainActivity.class.getDeclaredField("session");
              f.setAccessible(true);
              session.set((KartSession) f.get(a));
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      KartSession.Connection c =
          new KartSession.Connection(
              new ClientConfig("test-placeholder", "read", "synthetic", "127.0.0.1", 9443),
              "127.0.0.1",
              "");
      c.connected = true;
      c.name = "Synthetic attitude test";
      c.lastStatus = System.nanoTime();
      c.gatewayAddresses = Set.of(InetAddress.getLoopbackAddress());
      c.telemetrySource = sender.getLocalPort();
      c.telemetry = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      c.telemetry.setSoTimeout(100);
      var connection = KartSession.class.getDeclaredField("connection");
      connection.setAccessible(true);
      connection.set(session.get(), c);
      var receive =
          KartSession.class.getDeclaredMethod(
              "receive", KartSession.Connection.class, DatagramSocket.class, boolean.class);
      receive.setAccessible(true);
      receive.invoke(session.get(), c, c.telemetry, false);
      AtomicBoolean running = new AtomicBoolean(true);
      AtomicReference<Double> target = new AtomicReference<>(0.0);
      AtomicReference<Throwable> error = new AtomicReference<>();
      Thread feed =
          new Thread(
              () -> {
                int index = 0;
                double angle = 0;
                try {
                  while (running.get()) {
                    double delta = Math.max(-1.08, Math.min(1.08, target.get() - angle));
                    angle += delta;
                    byte[] data = packet(index++, angle, delta);
                    sender.send(
                        new DatagramPacket(
                            data,
                            data.length,
                            InetAddress.getLoopbackAddress(),
                            c.telemetry.getLocalPort()));
                    Thread.sleep(36);
                  }
                } catch (Exception e) {
                  if (running.get()) error.set(e);
                }
              });
      feed.start();
      try {
        Thread.sleep(2300);
        scenario.onActivity(
            a -> button(a.getWindow().getDecorView(), "Calibrate all").performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        AccessibilityNodeInfo root =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        assertNotNull(root);
        boolean clicked = false;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Calibrate all"))
          if (node.isClickable() && "android.widget.Button".contentEquals(node.getClassName()))
            clicked |= node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        assertTrue("Calibration confirmation", clicked);
        awaitPitch(c, 0);
        Thread.sleep(200);
        Bitmap level = image(scenario);
        target.set(20.0);
        awaitPitch(c, 20);
        Thread.sleep(250);
        Bitmap held = image(scenario);
        int changed = 0;
        float density =
            InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getResources()
                .getDisplayMetrics()
                .density;
        for (int y = (int) (32 * density); y < held.getHeight() - (int) (60 * density); y++)
          for (int x = 0; x < held.getWidth(); x++)
            if (level.getPixel(x, y) != held.getPixel(x, y)) changed++;
        assertTrue("Instrument graphics visibly move, not only labels", changed > 50);
        screenshot("instrument-held");
        level.recycle();
        held.recycle();
        target.set(0.0);
        awaitPitch(c, 0);
        Thread.sleep(250);
        screenshot("instrument-return");
        assertFalse(c.armed);
        assertNull(c.motor);
        assertNull(error.get());
        synchronized (c.orientation) {
          assertNull(c.orientation.output(c.latest, Orientation.NINTENDO));
          assertTrue(c.orientation.calibrated());
        }
      } finally {
        running.set(false);
        feed.join(1000);
        session.get().disconnect();
      }
    }
  }
}
