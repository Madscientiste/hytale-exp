package com.madscientiste.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.ConfigException;
import com.madscientiste.rcon.infrastructure.ConfigLoader;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.protocol.RconPacket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the findings of the RCON pentest and of the review that followed. Each drives the
 * server the way the attack did, over a real socket, with the default limits unless the test says
 * otherwise.
 *
 * <p>Every test gets a fresh server, so a ban earned in one test cannot touch another. The server
 * counts all loopback addresses (127.0.0.0/8) as one peer; a test that needs a second, different
 * peer uses {@link TestAddresses#outside()} and is skipped on a machine without a network card.
 */
public class RconSecurityRegressionTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String SERVER_IP = "127.0.0.1";
  private static final int PORT = 25590;
  private static final int SECOND_PORT = 25591;

  private RconServer server;
  private RconServer secondServer;
  private String secondServerIp = SERVER_IP;
  private LogCapture logs;

  @Before
  public void setUp() throws Exception {
    logs = new LogCapture();
    server = RconTestHelpers.createServerWithPassword(PASSWORD, PORT);
    Thread.sleep(100);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
    if (secondServer != null) {
      secondServer.stop();
    }
    logs.close();
  }

  // ---------------------------------------------------------------------------
  // Finding 1: brute force
  // ---------------------------------------------------------------------------

  @Test
  public void finding1_wrongPasswordsBanTheAddress() throws Exception {
    String outside = TestAddresses.outside();
    startSecondServer(
        RconConfig.builder()
            .host(outside)
            .port(SECOND_PORT)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    for (int i = 0; i < 5; i++) {
      RconPacket reply = login(outside, SECOND_PORT, 10 + i, "wrong-" + i);
      assertNotNull("Wrong password " + i + " should still get a reply", reply);
      assertEquals("-1", reply.getBody());
    }

    assertFalse(
        "After 5 wrong passwords the address is banned: even the right password is refused",
        sessionWorks(outside, SECOND_PORT));
    assertTrue(
        "The ban is per address: another one still gets in", sessionWorks(SERVER_IP, SECOND_PORT));
  }

  /**
   * Review finding: any local program can bind a fresh 127.x.y.z for every guess. All of loopback
   * must count as one address, or there is no limit at all from the machine itself.
   */
  @Test
  public void finding1_allLoopbackAddressesShareOneGuessBudget() throws Exception {
    int evaluated = 0;
    for (int i = 0; i < 60; i++) {
      String source = "127.0." + (10 + i / 250) + "." + (1 + i % 250);
      if (login(source, PORT, i, "guess-" + i) != null) {
        evaluated++;
      }
    }

    assertTrue(
        "60 guesses from 60 different loopback addresses: at most 5 may be checked, got "
            + evaluated,
        evaluated <= 5);
    assertFalse(
        "127.0.0.1 is the same peer as the other loopback addresses: banned with them",
        sessionWorks(SERVER_IP, PORT));
  }

  /**
   * Review finding: a guess followed at once by a connection reset made the reply fail, and the
   * failed login never reached the log (a throttled stack trace was written instead).
   */
  @Test
  public void finding1_aGuessFollowedByAResetIsStillLogged() throws Exception {
    logs.assumeAvailable();
    logs.clear();

    for (int i = 0; i < 3; i++) {
      Socket socket = connectFrom("127.0.0.21", PORT);
      socket.setSoLinger(true, 0); // close() sends a reset instead of a normal shutdown
      socket.getOutputStream().write(new RconPacket(1, 3, "guess-" + i).toBytes());
      Thread.sleep(20);
      socket.close();
      Thread.sleep(700);
    }

    assertEquals(
        "Each of the 3 guesses must leave a failed-login line with its address",
        3,
        logs.count(
            m ->
                m.contains("result=failure")
                    && m.contains("invalid_password")
                    && m.contains("remote_ip=127.0.0.21")));
    assertEquals("A peer that hangs up is not a fault", 0, logs.countWithStackTrace());
    assertFalse(logs.anyMessage(m -> m.contains("byte_processing_failure")));
  }

  @Test
  public void finding1_guessingIsCutOffAfterAHandfulOfTries() throws Exception {
    int evaluated = 0;
    for (int i = 0; i < 200; i++) {
      RconPacket reply = login("127.0.0.3", PORT, i, "guess-" + i);
      if (reply != null) {
        evaluated++;
      }
    }

    assertTrue(
        "At most 5 of 200 guesses may be checked against the password, got " + evaluated,
        evaluated <= 5);
  }

  @Test
  public void finding1_failedLoginIsLoggedWithTheRemoteAddress() throws Exception {
    logs.assumeAvailable();
    login("127.0.0.4", PORT, 1, "wrong");

    assertTrue(
        "The failed login line must name the address it came from",
        logs.await(m -> m.contains("result=failure") && m.contains("remote_ip=127.0.0.4"), 2000));
  }

  /** Held before the fixes too, and must stay true. */
  @Test
  public void guessesThenRightPasswordThenCommandInOneWriteRunNothing() throws Exception {
    ByteArrayOutputStream burst = new ByteArrayOutputStream();
    for (int i = 0; i < 50; i++) {
      burst.write(new RconPacket(i, RconPacket.SERVERDATA_AUTH, "guess-" + i).toBytes());
    }
    burst.write(new RconPacket(100, RconPacket.SERVERDATA_AUTH, PASSWORD).toBytes());
    burst.write(new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo pwned").toBytes());

    try (Socket socket = connectFrom("127.0.0.5", PORT)) {
      socket.getOutputStream().write(burst.toByteArray());
      List<RconPacket> replies = readUntilClosed(socket);

      assertEquals("Only the first guess is answered", 1, replies.size());
      assertEquals("-1", replies.get(0).getBody());
    }
    assertFalse("No command may run", logs.anyMessage(m -> m.contains("execute:")));
  }

  // ---------------------------------------------------------------------------
  // Finding 2: log flood without a password
  // ---------------------------------------------------------------------------

  @Test
  public void finding2_manyBadPacketsInOneWriteAreLoggedOnceWithoutStackTrace() throws Exception {
    logs.assumeAvailable();
    logs.clear();
    sendBadPacketBurst("127.0.0.6");
    Thread.sleep(500);

    assertEquals("Junk from the network must not log stack traces", 0, logs.countWithStackTrace());
    assertTrue(
        "292 bad packets in one write may log a few lines, not one each: got "
            + logs.records().size(),
        logs.records().size() <= 6);
  }

  @Test
  public void finding2_connectionFloodCannotFloodTheLog() throws Exception {
    logs.assumeAvailable();
    logs.clear();
    for (int i = 0; i < 300; i++) {
      try (Socket socket = connectFrom("127.0.0.7", PORT)) {
        socket.getOutputStream().write(new RconPacket(i, 2, "x").toBytes());
        readUntilClosed(socket);
      } catch (IOException e) {
        // Refused or reset: fine for this test
      }
    }
    Thread.sleep(500);

    assertEquals("Junk from the network must not log stack traces", 0, logs.countWithStackTrace());
    assertTrue(
        "300 junk connections from one address may log a bounded number of lines, got "
            + logs.records().size(),
        logs.records().size() <= 100);
  }

  // ---------------------------------------------------------------------------
  // Finding 3: memory leak without a password
  // ---------------------------------------------------------------------------

  @Test
  public void finding3_packetsAfterCloseLeaveNoStateBehind() throws Exception {
    for (int i = 0; i < 5; i++) {
      sendBadPacketBurst("127.0.0.8");
    }

    long end = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < end && server.getStats().connectionCount > 0) {
      Thread.sleep(50);
    }
    Thread.sleep(200);

    assertEquals("All connections are closed", 0, server.getStats().connectionCount);
    assertEquals(
        "No per-connection state may outlive its connection",
        0,
        server.getStats().connectionStateCount);
  }

  // ---------------------------------------------------------------------------
  // Finding 4: slot exhaustion
  // ---------------------------------------------------------------------------

  @Test
  public void finding4_trickledBytesDoNotKeepAnUnauthenticatedSocketOpen() throws Exception {
    List<Socket> held = new ArrayList<>();
    try {
      for (int i = 0; i < 4; i++) {
        Socket socket = connectFrom("127.0.0.9", PORT);
        // A valid-looking size header (4092), then nothing but a slow trickle
        socket.getOutputStream().write(new byte[] {(byte) 0xFC, 0x0F, 0, 0});
        held.add(socket);
      }

      // Trickle one byte every 500 ms for 7 s: longer than the 5 s login deadline
      for (int tick = 0; tick < 14; tick++) {
        Thread.sleep(500);
        for (Socket socket : held) {
          try {
            socket.getOutputStream().write(0x41);
          } catch (IOException e) {
            // Already closed by the server
          }
        }
      }

      for (Socket socket : held) {
        assertTrue("The server must have closed the socket by now", isClosedByPeer(socket));
      }
      assertEquals("No slot is held any more", 0, server.getStats().connectionCount);
    } finally {
      for (Socket socket : held) {
        socket.close();
      }
    }
  }

  @Test
  public void finding4_oneAddressCannotTakeEverySlot() throws Exception {
    String outside = TestAddresses.outside();
    startSecondServer(
        RconConfig.builder()
            .host(outside)
            .port(SECOND_PORT)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    List<Socket> held = new ArrayList<>();
    try {
      for (int i = 0; i < 10; i++) {
        try {
          Socket socket = connectFrom(outside, SECOND_PORT);
          socket.getOutputStream().write(new byte[] {(byte) 0xFC, 0x0F, 0, 0});
          held.add(socket);
        } catch (IOException e) {
          // Refused: the cap at work
        }
      }
      Thread.sleep(300);

      assertEquals(
          "One address holds 4 connections at most", 4, secondServer.getStats().connectionCount);
      assertTrue(
          "With 10 sockets opened from one address, another address must still get in",
          sessionWorks(SERVER_IP, SECOND_PORT));
    } finally {
      for (Socket socket : held) {
        socket.close();
      }
    }
  }

  /**
   * Review finding: three outside addresses (4 + 4 + 2 connections) filled every slot, and a health
   * check from 127.0.0.1 with the right password was refused. The last slot is now kept for
   * loopback. Shown here with a small server: 3 slots, all of them open to one outside address.
   */
  @Test
  public void finding4_theLastSlotIsKeptForLoopback() throws Exception {
    String outside = TestAddresses.outside();
    startSecondServer(
        RconConfig.builder()
            .host(outside)
            .port(SECOND_PORT)
            .maxConnections(3)
            .maxConnectionsPerIp(3)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    List<Socket> held = new ArrayList<>();
    try {
      for (int i = 0; i < 3; i++) {
        try {
          Socket socket = connectFrom(outside, SECOND_PORT);
          socket.getOutputStream().write(new byte[] {(byte) 0xFC, 0x0F, 0, 0});
          held.add(socket);
        } catch (IOException e) {
          // Refused
        }
      }
      Thread.sleep(300);

      assertEquals(
          "Outside addresses can take all slots but the last",
          2,
          secondServer.getStats().connectionCount);
      assertTrue(
          "A local client must still get in while outside addresses hold every other slot",
          sessionWorks(SERVER_IP, SECOND_PORT));
    } finally {
      for (Socket socket : held) {
        socket.close();
      }
    }
  }

  /**
   * Review finding: a command that threw an Error (a server API mismatch shows up as
   * NoSuchMethodError) killed the connection's thread without closing the connection, so its slot
   * was lost until restart.
   */
  @Test
  public void anErrorInACommandStillFreesTheConnection() throws Exception {
    java.lang.reflect.Field field = RconServer.class.getDeclaredField("commandDispatcher");
    field.setAccessible(true);
    ((com.madscientiste.rcon.command.CommandDispatcher) field.get(server))
        .registerCommand(
            "boom",
            new com.madscientiste.rcon.command.Command() {
              @Override
              public String execute(String arguments) {
                throw new NoSuchMethodError("simulated server API mismatch");
              }

              @Override
              public boolean requiresAuth() {
                return true;
              }

              @Override
              public String getDescription() {
                return "";
              }
            });

    for (int i = 0; i < 4; i++) {
      try (Socket socket = RconTestHelpers.connectAndAuth(SERVER_IP, PORT, PASSWORD)) {
        RconTestHelpers.sendPacket(socket, new RconPacket(2, 2, "boom"));
        readUntilClosed(socket);
      }
    }

    long end = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < end && server.getStats().connectionCount > 0) {
      Thread.sleep(50);
    }

    assertEquals("Every connection is closed", 0, server.getStats().connectionCount);
    assertEquals("No session state is left", 0, server.getStats().connectionStateCount);
    assertTrue("The next client gets in", sessionWorks(SERVER_IP, PORT));
  }

  /**
   * Review finding: a logged-in client that stops reading made the server's write block for ever,
   * so its slot outlived the idle timeout. A write that does not finish within the idle timeout (2
   * s here) now ends the connection.
   */
  @Test
  public void aClientThatStopsReadingLosesItsSlot() throws Exception {
    startSecondServer(
        RconConfig.builder()
            .port(SECOND_PORT)
            .maxFrameSize(1048576)
            .readTimeoutMs(2000)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    Socket socket = new Socket();
    try {
      socket.setReceiveBufferSize(4096); // a small window: the server's writes block soon
      socket.connect(new InetSocketAddress(SERVER_IP, SECOND_PORT), 3000);
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(new RconPacket(1, 3, PASSWORD).toBytes());
      assertEquals("1", RconTestHelpers.readResponse(socket).getBody());

      // Ask for far more output than the socket buffers hold, and never read any of it
      byte[] command = new RconPacket(2, 2, "echo " + "x".repeat(1_000_000)).toBytes();
      Thread writer =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < 16; i++) {
                    socket.getOutputStream().write(command);
                  }
                } catch (IOException e) {
                  // The server gave up on us: expected
                }
              });
      writer.setDaemon(true);
      writer.start();

      long end = System.currentTimeMillis() + 12_000;
      while (System.currentTimeMillis() < end && secondServer.getStats().connectionCount > 0) {
        Thread.sleep(100);
      }
      assertEquals(
          "The stuck connection must be closed and its slot freed",
          0,
          secondServer.getStats().connectionCount);
    } finally {
      socket.close();
    }
  }

  /**
   * Review finding: nothing limited how many password checks ran at once over all addresses, and
   * each costs real CPU time. Only 2 run at a time now; a login that arrives while both are busy is
   * refused without a check.
   */
  @Test
  public void onlyTwoPasswordChecksRunAtTheSameTime() throws Exception {
    logs.assumeAvailable();
    logs.clear();

    List<Socket> sockets = new ArrayList<>();
    try {
      for (int i = 0; i < 4; i++) {
        sockets.add(connectFrom(SERVER_IP, PORT));
      }
      // Four guesses within the same moment: a check takes far longer than sending them
      for (Socket socket : sockets) {
        socket.getOutputStream().write(new RconPacket(1, 3, "guess").toBytes());
      }
      for (Socket socket : sockets) {
        assertEquals("Every login is refused", -1, RconTestHelpers.readResponse(socket).getId());
      }
    } finally {
      for (Socket socket : sockets) {
        socket.close();
      }
    }

    long checked = logs.count(m -> m.contains("failure_reason=invalid_password"));
    long busy = logs.count(m -> m.contains("failure_reason=server_busy"));
    assertEquals("Each of the 4 logins is logged", 4, checked + busy);
    assertTrue("At most 2 passwords were checked, got " + checked, checked <= 2);
    assertTrue("The others were refused unchecked", busy >= 2);
  }

  /** The same limits on IPv6 loopback, which counts as this machine like 127.0.0.0/8 does. */
  @Test
  public void ipv6LoopbackIsServedAndLimitedLikeTheRestOfLoopback() throws Exception {
    TestAddresses.assumeIpv6Loopback();
    startSecondServer(
        RconConfig.builder()
            .host(TestAddresses.LOOPBACK_V6)
            .port(SECOND_PORT)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    assertTrue(sessionWorks(TestAddresses.LOOPBACK_V6, SECOND_PORT));
    for (int i = 0; i < 5; i++) {
      RconPacket reply = login(TestAddresses.LOOPBACK_V6, SECOND_PORT, i, "wrong-" + i);
      assertNotNull(reply);
      assertEquals(-1, reply.getId());
    }
    assertFalse(
        "5 wrong passwords from ::1 ban it", sessionWorks(TestAddresses.LOOPBACK_V6, SECOND_PORT));
  }

  /**
   * Review finding: with maxFrameSize at its maximum (1 MB) a peer that had not logged in could
   * make the server buffer a megabyte per connection and burn CPU on it. Before login a packet is
   * now limited to the default size, whatever the config says.
   */
  @Test
  public void aLargeMaxFrameSizeDoesNotApplyBeforeLogin() throws Exception {
    startSecondServer(
        RconConfig.builder()
            .port(SECOND_PORT)
            .maxFrameSize(1048576)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    try (Socket socket = connectFrom(SERVER_IP, SECOND_PORT)) {
      // Size header announcing a 100000-byte packet, sent without logging in
      socket.getOutputStream().write(new byte[] {(byte) 0xA0, (byte) 0x86, 0x01, 0});
      Thread.sleep(500);
      assertTrue(
          "A packet larger than the default size is refused at once before login",
          isClosedByPeer(socket));
    }

    // After login the configured size applies
    String text = "x".repeat(50_000);
    try (Socket socket = RconTestHelpers.connectAndAuth(SERVER_IP, SECOND_PORT, PASSWORD)) {
      RconTestHelpers.sendPacket(socket, new RconPacket(9, 2, "echo " + text));
      StringBuilder reply = new StringBuilder();
      while (reply.length() < text.length()) {
        reply.append(RconTestHelpers.readResponse(socket).getBody());
      }
      assertEquals(text, reply.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // Finding 6: no encryption
  // ---------------------------------------------------------------------------

  @Test
  public void finding6_bindingToANonLoopbackAddressWarns() throws Exception {
    logs.assumeAvailable();
    String outside = TestAddresses.outside();
    startSecondServer(
        RconConfig.builder()
            .host(outside)
            .port(SECOND_PORT)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD)));

    assertTrue(
        "Starting on a non-loopback address must warn that RCON is not encrypted",
        logs.await(m -> m.contains(outside) && m.contains("no encryption"), 2000));
  }

  @Test
  public void finding6_bindingToLoopbackDoesNotWarn() throws Exception {
    logs.assumeAvailable();
    Thread.sleep(200);
    assertFalse(logs.anyMessage(m -> m.contains("no encryption")));
  }

  // ---------------------------------------------------------------------------
  // Finding 10: config file mode and hash strength
  // ---------------------------------------------------------------------------

  @Test
  public void finding10_savedConfigIsReadableByItsOwnerOnly() throws Exception {
    Path dir = Files.createTempDirectory("rcon-config");
    Path file = dir.resolve("com.madscientiste.rcon.json");
    Assume.assumeTrue(
        "SKIPPED: this test needs POSIX file permissions",
        file.getFileSystem().supportedFileAttributeViews().contains("posix"));

    RconConfig config =
        RconConfig.builder().passwordHash(AuthenticationService.hashPassword(PASSWORD)).build();
    assertTrue(new ConfigLoader(file).save(config));

    assertEquals(
        "The file holds the password hash",
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  @Test
  public void finding10_newHashesAreNotASingleRoundOfSha256() throws Exception {
    String hash = AuthenticationService.hashPassword(PASSWORD);

    String[] parts = hash.split(":");
    if (parts.length == 2) {
      byte[] salt = Base64.getDecoder().decode(parts[0]);
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update(salt);
      sha256.update(PASSWORD.getBytes(StandardCharsets.UTF_8));
      assertFalse(
          "A new hash must not be one round of salted SHA-256",
          Base64.getEncoder().encodeToString(sha256.digest()).equals(parts[1]));
    }
    assertTrue(AuthenticationService.verifyPassword(PASSWORD, hash));
    assertFalse(AuthenticationService.verifyPassword(PASSWORD + "x", hash));
  }

  /**
   * Existing configs, and the hytale-docker image, hold hashes in the old format: base64(salt) ":"
   * base64(SHA-256(salt + password)). This one was made with openssl, the way that image does it.
   */
  @Test
  public void legacyHashesStillWork() throws Exception {
    String legacy = "MDEyMzQ1Njc4OWFiY2RlZg==:8AdiwaCQPY2bwPYtAg7zMaYrocpiF+opzLdjcWFc8hA=";

    assertTrue(AuthenticationService.verifyPassword("legacy-test-password", legacy));
    assertFalse(AuthenticationService.verifyPassword("legacy-test-passwore", legacy));

    RconConfig config = RconConfig.builder().port(SECOND_PORT).passwordHash(legacy).build();
    secondServer = new RconServer(config);
    secondServer.start();
    Thread.sleep(100);

    RconPacket reply = login(SERVER_IP, SECOND_PORT, 5, "legacy-test-password");
    assertNotNull(reply);
    assertEquals("1", reply.getBody());
  }

  @Test
  public void finding10_aLegacyHashWarnsAtStartup() throws Exception {
    logs.assumeAvailable();
    String legacy = "MDEyMzQ1Njc4OWFiY2RlZg==:8AdiwaCQPY2bwPYtAg7zMaYrocpiF+opzLdjcWFc8hA=";

    secondServer =
        new RconServer(RconConfig.builder().port(SECOND_PORT).passwordHash(legacy).build());
    secondServer.start();

    assertTrue(
        "A config that still holds an old-format hash must say so at startup",
        logs.await(m -> m.contains("old format"), 2000));
    assertEquals(
        "A current-format hash (the first server) must not warn",
        1,
        logs.count(m -> m.contains("old format")));
  }

  /** Review finding: a current-format hash with very few iterations gave no warning. */
  @Test
  public void finding10_aHashWithFewIterationsWarnsAtStartup() throws Exception {
    logs.assumeAvailable();
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    javax.crypto.spec.PBEKeySpec spec =
        new javax.crypto.spec.PBEKeySpec("few-iterations".toCharArray(), salt, 10_000, 256);
    byte[] hash =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec)
            .getEncoded();
    String weak =
        "pbkdf2-sha512:10000:"
            + Base64.getEncoder().encodeToString(salt)
            + ":"
            + Base64.getEncoder().encodeToString(hash);

    startSecondServer(RconConfig.builder().port(SECOND_PORT).passwordHash(weak));

    assertTrue(
        "A hash made with fewer iterations than new hashes get must say so at startup",
        logs.await(m -> m.contains("fewer than") && m.contains("iterations"), 2000));
    RconPacket reply = login(SERVER_IP, SECOND_PORT, 5, "few-iterations");
    assertNotNull(reply);
    assertEquals("It still works", "1", reply.getBody());
  }

  /**
   * Review finding: a config without a password hash accepted any login. Without the owner's
   * explicit opt-in the server now refuses to start.
   */
  @Test
  public void aServerWithoutPasswordHashRefusesToStart() throws Exception {
    secondServer = new RconServer(RconConfig.builder().port(SECOND_PORT).build());
    try {
      secondServer.start();
      fail("A server with no password hash and no opt-in must not start");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("passwordHash"));
    }

    assertFalse(secondServer.isRunning());
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(SERVER_IP, SECOND_PORT), 1000);
      fail("Nothing may be listening");
    } catch (IOException expected) {
      // Connection refused
    }
  }

  // ---------------------------------------------------------------------------
  // Finding 11: log injection
  // ---------------------------------------------------------------------------

  @Test
  public void finding11_commandTextCannotForgeLogLinesAndIsLoggedInFull() throws Exception {
    logs.assumeAvailable();
    String escape = "\u001b";
    String command =
        escape + "[31mevil" + escape + "[0m marker-argument\n[2026/01/01 00:00:00   INFO] forged";

    try (Socket socket = RconTestHelpers.connectAndAuth(SERVER_IP, PORT, PASSWORD)) {
      logs.clear();
      RconTestHelpers.sendPacket(socket, new RconPacket(7, 2, command));
      try {
        RconTestHelpers.readResponse(socket);
      } catch (Exception e) {
        // The reply does not matter here, only the log
      }
    }
    Thread.sleep(300);

    assertTrue(
        "The whole command, arguments included, must be in the log",
        logs.anyMessage(m -> m.contains("marker-argument")));
    assertFalse("No escape code may reach the log", logs.anyMessage(m -> m.contains(escape)));
    assertFalse(
        "No line break may reach the log",
        logs.anyMessage(m -> m.contains("\n") || m.contains("\r")));
  }

  // ---------------------------------------------------------------------------
  // Finding 12: failed login reply id
  // ---------------------------------------------------------------------------

  @Test
  public void finding12_failedLoginReplyHasIdMinusOne() throws Exception {
    RconPacket reply = login("127.0.0.11", PORT, 77, "wrong");

    assertNotNull(reply);
    assertEquals(RconPacket.SERVERDATA_AUTH_RESPONSE, reply.getType());
    assertEquals("A refused login is answered with id -1", -1, reply.getId());
  }

  // ---------------------------------------------------------------------------
  // Finding 13: broken config, maxFrameSize, long replies
  // ---------------------------------------------------------------------------

  @Test
  public void finding13_brokenConfigIsRefusedAndLeftUntouched() throws Exception {
    String[] broken = {
      "{ \"host\": \"127.0.0.1\", \"port\": 25575, ", // cut off
      "this is not json",
      "", // empty file
      "{ \"port\": 99999999 }", // out of range
      "{ \"port\": \"abc\" }", // wrong type
      "{ \"passwrdHash\": \"c2FsdA==:aGFzaA==\" }", // misspelled key
      "{ \"port\": 25575 } trailing", // junk after the object
    };

    for (String content : broken) {
      Path dir = Files.createTempDirectory("rcon-config");
      Path file = dir.resolve("com.madscientiste.rcon.json");
      Files.writeString(file, content);

      try {
        RconConfig loaded = new ConfigLoader(file).load();
        fail("A broken config must not load, but gave " + loaded + " for: " + content);
      } catch (ConfigException expected) {
        // Refused the intended way: any other exception fails the test
        assertTrue(expected.getMessage(), expected.getMessage().contains(file.toString()));
      }
      assertEquals("The broken file must not be changed", content, Files.readString(file));
    }
  }

  @Test
  public void finding13_maxFrameSizeFromTheConfigIsHonouredAndLongRepliesAreSplit()
      throws Exception {
    RconConfig config =
        RconConfig.builder()
            .port(SECOND_PORT)
            .maxFrameSize(8192)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD))
            .build();
    secondServer = new RconServer(config);
    secondServer.start();
    Thread.sleep(100);

    String text = "0123456789".repeat(600); // 6000 bytes: over one 4096-byte packet
    try (Socket socket = RconTestHelpers.connectAndAuth(SERVER_IP, SECOND_PORT, PASSWORD)) {
      RconTestHelpers.sendPacket(socket, new RconPacket(9, 2, "echo " + text));

      StringBuilder reply = new StringBuilder();
      int packets = 0;
      while (reply.length() < text.length()) {
        RconPacket part = RconTestHelpers.readResponse(socket);
        assertEquals("Every part carries the request id", 9, part.getId());
        assertTrue(
            "No packet may be larger than 4096 bytes on the wire", part.toBytes().length <= 4096);
        reply.append(part.getBody());
        packets++;
      }

      assertEquals("The reply arrives complete and in order", text, reply.toString());
      assertEquals(2, packets);

      // The connection is still usable afterwards
      RconTestHelpers.sendPacket(socket, new RconPacket(10, 2, "echo still-here"));
      assertEquals("still-here", RconTestHelpers.readResponse(socket).getBody());
    }
  }

  @Test
  public void finding13_aSmallerMaxFrameSizeIsEnforced() throws Exception {
    RconConfig config =
        RconConfig.builder()
            .port(SECOND_PORT)
            .maxFrameSize(128)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD))
            .build();
    secondServer = new RconServer(config);
    secondServer.start();
    Thread.sleep(100);

    try (Socket socket = RconTestHelpers.connectAndAuth(SERVER_IP, SECOND_PORT, PASSWORD)) {
      RconTestHelpers.sendPacket(socket, new RconPacket(9, 2, "echo ok"));
      assertEquals("ok", RconTestHelpers.readResponse(socket).getBody());

      RconTestHelpers.sendPacket(socket, new RconPacket(10, 2, "echo " + "x".repeat(300)));
      assertTrue(
          "A packet over the configured size must close the connection",
          readUntilClosed(socket).isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Connect to one of the test servers from a chosen source address of this machine. The first
   * server listens on 127.0.0.1; the second on whatever single address its test gave it.
   */
  private Socket connectFrom(String sourceIp, int port) throws IOException {
    return TestAddresses.connect(sourceIp, port == SECOND_PORT ? secondServerIp : SERVER_IP, port);
  }

  /** Start a second server on the one address in its config; tearDown stops it. */
  private void startSecondServer(RconConfig.Builder config) throws Exception {
    RconConfig built = config.build();
    secondServerIp = built.getHost();
    secondServer = new RconServer(built);
    secondServer.start();
    Thread.sleep(100);
  }

  /** Send one login. Returns the reply, or null if the server closed without answering. */
  private RconPacket login(String sourceIp, int port, int id, String password) {
    try (Socket socket = connectFrom(sourceIp, port)) {
      socket.getOutputStream().write(new RconPacket(id, 3, password).toBytes());
      return RconTestHelpers.readResponse(socket);
    } catch (Exception e) {
      return null;
    }
  }

  /** True if the right password logs in and a command is answered. */
  private boolean sessionWorks(String sourceIp, int port) {
    try (Socket socket = connectFrom(sourceIp, port)) {
      socket.getOutputStream().write(new RconPacket(1, 3, PASSWORD).toBytes());
      RconPacket auth = RconTestHelpers.readResponse(socket);
      if (auth.getId() != 1 || !auth.getBody().equals("1")) {
        return false;
      }
      socket.getOutputStream().write(new RconPacket(2, 2, "echo alive").toBytes());
      return RconTestHelpers.readResponse(socket).getBody().equals("alive");
    } catch (Exception e) {
      return false;
    }
  }

  /** 292 empty command packets in one write, with no login: the pentest's flood payload. */
  private void sendBadPacketBurst(String sourceIp) throws IOException {
    ByteArrayOutputStream burst = new ByteArrayOutputStream();
    for (int i = 0; i < 292; i++) {
      burst.write(new RconPacket(i, 2, "").toBytes());
    }
    try (Socket socket = connectFrom(sourceIp, PORT)) {
      socket.getOutputStream().write(burst.toByteArray());
      readUntilClosed(socket);
    }
  }

  /** Read packets until the server closes the connection (or stays silent for 5 s). */
  private static List<RconPacket> readUntilClosed(Socket socket) {
    List<RconPacket> packets = new ArrayList<>();
    try {
      while (true) {
        packets.add(RconTestHelpers.readResponse(socket));
      }
    } catch (Exception e) {
      return packets;
    }
  }

  /** True if the peer has closed the connection; false if it is still open and silent. */
  private static boolean isClosedByPeer(Socket socket) {
    try {
      socket.setSoTimeout(300);
      return socket.getInputStream().read() == -1;
    } catch (SocketTimeoutException e) {
      return false;
    } catch (IOException e) {
      return true;
    }
  }
}
