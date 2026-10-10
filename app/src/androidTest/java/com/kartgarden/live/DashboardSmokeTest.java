package com.kartgarden.live;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DashboardSmokeTest {
  private Button button(View v, String label) {
    if (v instanceof Button && label.contentEquals(((Button) v).getText())) return (Button) v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        Button found = button(g.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void opensDisarmedAndControlsFit() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(
          a -> {
            View root = a.getWindow().getDecorView();
            for (String label :
                new String[] {
                  "Connect", "Setup", "Arm", "STOP", "Record CSV", "Export", "Details"
                }) {
              Button b = button(root, label);
              assertNotNull(label, b);
              Rect r = new Rect();
              assertTrue(label + " visible", b.getGlobalVisibleRect(r));
              assertTrue(label + " fits viewport", r.width() >= b.getWidth() - 1);
            }
            assertNotNull(button(root, "A · Arm"));
            assertNotNull(button(root, "B · Stop"));
            // No network/arm action occurs in this smoke test.
          });
      screenshot("dashboard.png");
      scenario.onActivity(a -> button(a.getWindow().getDecorView(), "Setup").performClick());
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screenshot("setup.png");
    }
  }

  @Test
  public void fullscreenRetainsStopAndReturns() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(
          a -> {
            View root = a.getWindow().getDecorView();
            button(root, "View").performClick();
            assertEquals(
                View.GONE,
                button(root, "Setup").getParent() instanceof View
                    ? ((View) button(root, "Setup").getParent()).getVisibility()
                    : View.VISIBLE);
            assertNotNull(button(root, "B · Stop"));
            button(root, "B · Stop").performClick();
            button(root, "View").performClick();
            assertEquals(View.VISIBLE, ((View) button(root, "Setup").getParent()).getVisibility());
          });
    }
  }

  @Test
  public void rendersSyntheticH264WithoutGateway() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      final KartSession[] session = {null};
      scenario.onActivity(
          a -> {
            try {
              var field = MainActivity.class.getDeclaredField("session");
              field.setAccessible(true);
              session[0] = (KartSession) field.get(a);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      byte[] video;
      try (var in =
          InstrumentationRegistry.getInstrumentation()
              .getContext()
              .getAssets()
              .open("synthetic-video.h264")) {
        video = GatewayApi.readLimited(in, 2 * 1024 * 1024);
      }
      for (byte[] unit : com.kartgarden.live.protocol.LspAssembler.accessUnits(video)) {
        session[0].decoder.feed(unit);
        android.os.SystemClock.sleep(150);
        session[0].decoder.drain();
      }
      assertEquals("Decoder errors", 0, session[0].decoder.errors);
      assertTrue("At least one H.264 picture rendered", session[0].decoder.rendered > 0);
      assertNull("No kart connection opened", session[0].current());
      // A decoder surface can retain the previous kart's last picture. With no
      // active subscription, it must be hidden by the opaque no-video panel.
      final int[] point = new int[2];
      scenario.onActivity(
          a -> {
            try {
              var field = MainActivity.class.getDeclaredField("texture");
              field.setAccessible(true);
              android.view.TextureView texture = (android.view.TextureView) field.get(a);
              texture.getLocationOnScreen(point);
              point[0] += Math.round(texture.getWidth() * .25f);
              point[1] += Math.round(texture.getHeight() * .25f);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      Bitmap image =
          InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
      assertNotNull(image);
      assertEquals("Disconnected video is masked", 0xff081310, image.getPixel(point[0], point[1]));
      image.recycle();
    }
  }

  private void screenshot(String name) throws Exception {
    Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(image);
    File dir =
        InstrumentationRegistry.getInstrumentation()
            .getTargetContext()
            .getExternalFilesDir("screenshots");
    assertNotNull(dir);
    if (!dir.exists()) assertTrue(dir.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    image.recycle();
    // Runs as emulator shell, outside app storage that Gradle removes.
    String destination = "/data/local/tmp/kart-live-" + name;
    try (android.os.ParcelFileDescriptor fd =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("screencap -p " + destination);
        java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      byte[] buffer = new byte[1024];
      while (in.read(buffer) != -1) {}
    }
    try (android.os.ParcelFileDescriptor fd =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("ls " + destination);
        java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      String result =
          new String(GatewayApi.readLimited(in, 4096), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue("Screenshot exported to emulator shell storage", result.contains(name));
    }
  }
}
