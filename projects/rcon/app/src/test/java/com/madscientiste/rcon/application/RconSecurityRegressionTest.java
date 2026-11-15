package com.madscientiste.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
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
 * One test per finding of the RCON pentest. Each drives the server the way the attack did, over a
 * real socket, with the default limits. The file uses only API that existed before the fixes, so it
 * can be run against the old code to show that each test fails there.
 *
 * <p>Attacks come from their own loopback address (127.0.0.x), so a ban earned in one test cannot
 * touch another, and so "another address still gets in" can be checked.
 */
public class RconSecurityRegressionTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String SERVER_IP = "127.0.0.1";
  private static final int PORT = 25590;
  private static final int SECOND_PORT = 25591;

  private RconServer server;
  private RconServer secondServer;
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
    for (int i = 0; i < 5; i++) {
      RconPacket reply = login("127.0.0.2", PORT, 10 + i, "wrong-" + i);
      assertNotNull("Wrong password " + i + " should still get a reply", reply);
      assertEquals("-1", reply.getBody());
    }

    assertFalse(
        "After 5 wrong passwords the address is banned: even the right password is refused",
        sessionWorks("127.0.0.2", PORT));
    assertTrue("The ban is per address: another one still gets in", sessionWorks(SERVER_IP, PORT));
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
    List<Socket> held = new ArrayList<>();
    try {
      for (int i = 0; i < 10; i++) {
        try {
          Socket socket = connectFrom("127.0.0.10", PORT);
          socket.getOutputStream().write(new byte[] {(byte) 0xFC, 0x0F, 0, 0});
          held.add(socket);
        } catch (IOException e) {
          // Refused: the cap at work
        }
      }
      Thread.sleep(300);

      assertTrue(
          "With 10 sockets opened from one address, another address must still get in",
          sessionWorks(SERVER_IP, PORT));
    } finally {
      for (Socket socket : held) {
        socket.close();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Finding 6: no encryption
  // ---------------------------------------------------------------------------

  @Test
  public void finding6_bindingToANonLoopbackAddressWarns() throws Exception {
    logs.assumeAvailable();
    RconConfig config =
        RconConfig.builder()
            .host("0.0.0.0")
            .port(SECOND_PORT)
            .passwordHash(AuthenticationService.hashPassword(PASSWORD))
            .build();
    secondServer = new RconServer(config);
    secondServer.start();

    assertTrue(
        "Starting on a non-loopback address must warn that RCON is not encrypted",
        logs.await(m -> m.contains("0.0.0.0") && m.contains("no encryption"), 2000));
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
    Assume.assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));

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
      } catch (AssertionError e) {
        throw e;
      } catch (Throwable expected) {
        // Refused, as it should be
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

  /** Connect to the server from a chosen loopback source address. */
  private static Socket connectFrom(String sourceIp, int port) throws IOException {
    Socket socket = new Socket();
    socket.bind(new InetSocketAddress(sourceIp, 0));
    socket.connect(new InetSocketAddress(SERVER_IP, port), 3000);
    socket.setSoTimeout(5000);
    socket.setTcpNoDelay(true);
    return socket;
  }

  /** Send one login. Returns the reply, or null if the server closed without answering. */
  private static RconPacket login(String sourceIp, int port, int id, String password) {
    try (Socket socket = connectFrom(sourceIp, port)) {
      socket.getOutputStream().write(new RconPacket(id, 3, password).toBytes());
      return RconTestHelpers.readResponse(socket);
    } catch (Exception e) {
      return null;
    }
  }

  /** True if the right password logs in and a command is answered. */
  private static boolean sessionWorks(String sourceIp, int port) {
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
  private static void sendBadPacketBurst(String sourceIp) throws IOException {
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
