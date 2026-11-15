package com.madscientiste.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.command.CommandDispatcher;
import com.madscientiste.rcon.infrastructure.AuthFailureTracker;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.logging.LogThrottle;
import com.madscientiste.rcon.protocol.RconPacket;
import com.madscientiste.rcon.protocol.RconProtocol;
import com.madscientiste.rcon.transport.RconTransport;
import java.net.Socket;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The no-password mode: only on when the owner sets {@code allowNoPassword}, and then only for the
 * addresses in {@code noPasswordAllowFrom} (this machine by default).
 *
 * <p>A server without a password is never put on an address other machines can reach, with one
 * exception: the test that outside peers are refused, where only this machine is allowed in.
 */
public class RconNoPasswordModeTest {

  private static final int PORT = 25593;

  private RconServer server;
  private RconApplication bareApplication;
  private String serverIp = TestAddresses.LOOPBACK;
  private LogCapture logs;

  @Before
  public void setUp() {
    logs = new LogCapture();
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
    if (bareApplication != null) {
      bareApplication.stop();
    }
    logs.close();
  }

  /** Start the server on the one address in its config (127.0.0.1 unless the test set another). */
  private void start(RconConfig.Builder config) throws Exception {
    RconConfig built = config.port(PORT).build();
    serverIp = built.getHost();
    server = new RconServer(built);
    server.start();
    Thread.sleep(100);
  }

  /** Send one login. Returns the reply, or null if the server closed without answering. */
  private RconPacket login(String sourceIp, String password) {
    try (Socket socket = TestAddresses.connect(sourceIp, serverIp, PORT)) {
      socket.getOutputStream().write(new RconPacket(7, 3, password).toBytes());
      return RconTestHelpers.readResponse(socket);
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean accepted(RconPacket reply) {
    return reply != null && reply.getId() == 7 && reply.getBody().equals("1");
  }

  @Test
  public void optedInLetsThisMachineInWithoutAPassword() throws Exception {
    start(RconConfig.builder().allowNoPassword(true));

    assertTrue(accepted(login("127.0.0.1", "")));
    assertTrue("Any loopback address is this machine", accepted(login("127.0.0.77", "anything")));

    try (Socket socket = TestAddresses.connectFrom("127.0.0.1", PORT)) {
      socket.getOutputStream().write(new RconPacket(7, 3, "").toBytes());
      RconTestHelpers.readResponse(socket);
      socket.getOutputStream().write(new RconPacket(8, 2, "echo open").toBytes());
      assertEquals("open", RconTestHelpers.readResponse(socket).getBody());
    }
  }

  @Test
  public void theModeIsAnnouncedLoudlyAtEveryStart() throws Exception {
    logs.assumeAvailable();
    start(RconConfig.builder().allowNoPassword(true));

    assertTrue(
        "The warning must name the addresses that get in",
        logs.await(
            m -> m.contains("WITHOUT A PASSWORD") && m.contains("127.0.0.0/8") && m.contains("::1"),
            2000));
  }

  /** The server listens on the outside address here, but only this machine may log in. */
  @Test
  public void otherAddressesAreRefusedAndCountedTowardsABan() throws Exception {
    String outside = TestAddresses.outside();
    start(RconConfig.builder().host(outside).allowNoPassword(true));

    for (int i = 0; i < 5; i++) {
      RconPacket reply = login(outside, "");
      assertNotNull("Refusal " + i + " is answered", reply);
      assertEquals("A refused login is answered with id -1", -1, reply.getId());
    }
    assertNull("After 5 refused logins the address is banned", login(outside, ""));
    assertTrue("This machine still gets in", accepted(login("127.0.0.1", "")));

    if (logs.isAvailable()) {
      assertEquals(
          "Each refusal is logged with its reason",
          5,
          logs.count(m -> m.contains("address_not_allowed") && m.contains(outside)));
      assertTrue(logs.anyMessage(m -> m.contains("address_banned")));
    }
  }

  /** The list is checked per address, shown on loopback only: nothing here leaves the machine. */
  @Test
  public void theOwnersListDecidesWhoGetsIn() throws Exception {
    start(
        RconConfig.builder()
            .allowNoPassword(true)
            .noPasswordAllowFrom(List.of("127.0.0.5", "127.0.9.0/24")));

    assertTrue(accepted(login("127.0.0.5", "")));
    assertTrue(accepted(login("127.0.9.200", "")));
    assertFalse("127.0.0.1 is not on the list, so it is refused", accepted(login("127.0.0.1", "")));
  }

  @Test
  public void aPasswordHashWinsOverTheOptIn() throws Exception {
    logs.assumeAvailable();
    start(
        RconConfig.builder()
            .allowNoPassword(true)
            .passwordHash(AuthenticationService.hashPassword("real-password")));

    assertFalse(accepted(login("127.0.0.1", "")));
    assertTrue(accepted(login("127.0.0.1", "real-password")));
    assertFalse(logs.anyMessage(m -> m.contains("WITHOUT A PASSWORD")));
  }

  /**
   * Review finding: the login code let an allowed address in whenever the hash was missing, and
   * relied on RconServer.start() to have checked the opt-in. Here the layers are wired by hand,
   * without that check: a missing hash without opt-in must still log nobody in.
   */
  @Test
  public void aMissingHashWithoutOptInNeverLogsAnyoneIn() throws Exception {
    RconConfig config = RconConfig.builder().port(PORT).build(); // no hash, no opt-in
    AuthFailureTracker failures = new AuthFailureTracker(5, 600_000, 300_000);
    LogThrottle throttle = new LogThrottle(20, 10_000, 100);
    RconTransport transport = new RconTransport(config, null, failures, throttle);
    bareApplication =
        new RconApplication(
            transport, new RconProtocol(), new CommandDispatcher(), config, failures, throttle);
    transport.setCallbacks(bareApplication);
    bareApplication.start();
    Thread.sleep(100);

    RconPacket reply = login("127.0.0.1", "");
    assertNotNull(reply);
    assertEquals("Refused: id -1", -1, reply.getId());
    assertFalse(accepted(login("127.0.0.1", "anything")));
  }
}
