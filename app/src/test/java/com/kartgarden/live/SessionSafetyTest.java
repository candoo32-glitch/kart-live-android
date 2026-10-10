package com.kartgarden.live;

import static org.junit.Assert.*;

import com.kartgarden.live.protocol.ClientConfig;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Real localhost UDP + fake HTTPS, never a physical kart address or credential. */
public class SessionSafetyTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();
  private KartSession session;
  private KartSession.Connection c;
  private DatagramSocket receiver;
  private FakeApi api;

  private static final class FakeApi implements GatewayTransport {
    final CountDownLatch entered = new CountDownLatch(1),
        allow = new CountDownLatch(1),
        disarmed = new CountDownLatch(1);
    final AtomicInteger arms = new AtomicInteger();
    int port;
    long count = 100;

    public JSONObject request(String path, JSONObject body) throws Exception {
      if (path.equals("/v1/arm")) {
        arms.incrementAndGet();
        entered.countDown();
        if (!allow.await(2, TimeUnit.SECONDS)) throw new TimeoutException("Fake arm timed out");
        return new JSONObject()
            .put("motor_udp", port)
            .put("counter_start", 1)
            .put("counter_count", count);
      }
      if (path.equals("/v1/disarm")) {
        disarmed.countDown();
        return new JSONObject().put("disarmed", true);
      }
      throw new AssertionError("Unexpected request " + path);
    }
  }

  private void setup(String permission) throws Exception {
    receiver = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    receiver.setSoTimeout(1000);
    api = new FakeApi();
    api.port = receiver.getLocalPort();
    session = new KartSession(directory.getRoot());
    c =
        new KartSession.Connection(
            new ClientConfig("test-placeholder", permission, "test", "127.0.0.1", 9443),
            "127.0.0.1",
            "");
    c.api = api;
    c.address = InetAddress.getLoopbackAddress();
    c.connected = true;
    c.lastImu = c.lastStatus = System.nanoTime();
    var field = KartSession.class.getDeclaredField("connection");
    field.setAccessible(true);
    field.set(session, c);
    session.foreground(true);
  }

  private byte[] packet() throws Exception {
    byte[] p = new byte[32];
    DatagramPacket datagram = new DatagramPacket(p, p.length);
    receiver.receive(datagram);
    assertEquals(32, datagram.getLength());
    assertEquals(41020, datagram.getPort());
    return p;
  }

  private void awaitStopped() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (c.armed && System.nanoTime() < deadline) Thread.sleep(5);
    assertFalse(c.armed);
    assertFalse(c.lease.active());
    assertNull(c.motor);
    assertTrue(api.disarmed.await(1, TimeUnit.SECONDS));
  }

  @After
  public void cleanup() {
    if (api != null) api.allow.countDown();
    if (session != null) session.close();
    if (receiver != null) receiver.close();
  }

  @Test
  public void startsNeutralThenStopsWithNeutralPackets() throws Exception {
    setup("control");
    api.allow.countDown();
    session.arm();
    assertTrue(api.entered.await(1, TimeUnit.SECONDS));
    byte[] first = packet();
    assertEquals(0, first[0]);
    assertEquals(0, first[1]);
    session.input(1, -1);
    boolean moving = false;
    for (int i = 0; i < 8; i++) {
      byte[] p = packet();
      if (p[0] == -127 && p[1] == 127) {
        moving = true;
        break;
      }
    }
    assertTrue("Signed forward/reverse and steering", moving);
    session.stop("Test stop");
    int neutral = 0;
    for (int i = 0; i < 6 && neutral < 3; i++) {
      byte[] p = packet();
      if (p[0] == 0 && p[1] == 0) neutral++;
    }
    assertEquals(3, neutral);
    awaitStopped();
  }

  @Test
  public void lateArmReplyCannotSurviveBackgroundCancellation() throws Exception {
    setup("control");
    session.arm();
    assertTrue(api.entered.await(1, TimeUnit.SECONDS));
    session.foreground(false);
    api.allow.countDown();
    assertTrue(api.disarmed.await(1, TimeUnit.SECONDS));
    assertFalse(c.armed);
    assertFalse(c.lease.active());
    assertNull(c.motor);
    receiver.setSoTimeout(150);
    assertThrows(SocketTimeoutException.class, () -> packet());
  }

  @Test
  public void staleTelemetryStopsExistingSender() throws Exception {
    setup("control");
    api.allow.countDown();
    session.arm();
    packet();
    c.lastImu = System.nanoTime() - TimeUnit.SECONDS.toNanos(2);
    awaitStopped();
  }

  @Test
  public void counterExhaustionDoesNotRearm() throws Exception {
    setup("control");
    api.count = 2;
    api.allow.countDown();
    session.arm();
    packet();
    awaitStopped();
    assertEquals(1, api.arms.get());
  }

  @Test
  public void readCredentialCannotRequestLease() throws Exception {
    setup("read");
    session.arm();
    assertEquals("Control credential required", session.armBlock());
    assertEquals(0, api.arms.get());
    assertFalse(c.armPending);
    assertFalse(c.armed);
  }
}
