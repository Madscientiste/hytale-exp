package com.madscientiste.rcon.infrastructure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class ConfigLoaderTest {

  private Path file;
  private ConfigLoader loader;

  @Before
  public void setUp() throws Exception {
    file = Files.createTempDirectory("rcon-config").resolve("com.madscientiste.rcon.json");
    loader = new ConfigLoader(file);
  }

  private void assertRefused(String content, String expectedInMessage) throws Exception {
    Files.writeString(file, content);
    try {
      loader.load();
      fail("Should have been refused: " + content);
    } catch (ConfigException e) {
      assertTrue(
          "Message should mention '" + expectedInMessage + "' but was: " + e.getMessage(),
          e.getMessage().contains(expectedInMessage));
    }
    assertEquals("A refused file is never changed", content, Files.readString(file));
  }

  @Test
  public void missingFileGivesDefaults() throws Exception {
    RconConfig config = loader.load();

    assertEquals(RconConstants.DEFAULT_HOST, config.getHost());
    assertEquals(RconConstants.DEFAULT_PORT, config.getPort());
    assertEquals(5000, config.getAuthTimeoutMs());
    assertEquals(4, config.getMaxConnectionsPerIp());
    assertEquals(5, config.getAuthMaxFailures());
    assertEquals(600_000, config.getAuthFailureWindowMs());
    assertEquals(300_000, config.getAuthBanMs());
    assertFalse(config.requiresPassword());
    assertFalse("Loading must not create the file", Files.exists(file));
  }

  @Test
  public void everySettingSurvivesSaveAndLoad() throws Exception {
    String hash = AuthenticationService.hashPassword("pw");
    RconConfig saved =
        RconConfig.builder()
            .host("192.0.2.10")
            .port(12345)
            .maxConnections(7)
            .maxConnectionsPerIp(2)
            .maxFrameSize(2048)
            .readTimeoutMs(1111)
            .connectionTimeoutMs(2222)
            .authTimeoutMs(3333)
            .authMaxFailures(9)
            .authFailureWindowMs(4444)
            .authBanMs(5555)
            .passwordHash(hash)
            .allowNoPassword(true)
            .noPasswordAllowFrom(List.of("10.0.0.0/8", "::1", "192.0.2.7"))
            .build();

    assertTrue(loader.save(saved));
    RconConfig loaded = loader.load();

    assertEquals(saved.toString(), loaded.toString());
    assertEquals(hash, loaded.getPasswordHash());
    assertTrue(loaded.isAllowNoPassword());
    assertEquals("[10.0.0.0/8, ::1, 192.0.2.7]", loaded.getNoPasswordAllowFrom().toString());
  }

  @Test
  public void noPasswordModeIsOffByDefaultAndLimitedToThisMachine() throws Exception {
    RconConfig defaults = loader.load();
    assertFalse(defaults.isAllowNoPassword());
    assertFalse(defaults.isNoPasswordMode());
    assertEquals("[127.0.0.0/8, ::1]", defaults.getNoPasswordAllowFrom().toString());

    Files.writeString(file, "{ \"allowNoPassword\": true }");
    RconConfig optedIn = loader.load();
    assertTrue(optedIn.isNoPasswordMode());
    assertTrue(optedIn.mayLogInWithoutPassword(InetAddress.getByName("127.9.9.9")));
    assertTrue(optedIn.mayLogInWithoutPassword(InetAddress.getByName("::1")));
    assertFalse(optedIn.mayLogInWithoutPassword(InetAddress.getByName("192.0.2.7")));

    Files.writeString(
        file,
        "{ \"allowNoPassword\": true, \"noPasswordAllowFrom\": [\"192.0.2.0/24\", \"2001:db8::/32\"] }");
    RconConfig widened = loader.load();
    assertTrue(widened.mayLogInWithoutPassword(InetAddress.getByName("192.0.2.7")));
    assertTrue(widened.mayLogInWithoutPassword(InetAddress.getByName("2001:db8::5")));
    assertFalse(
        "The list replaces the default: loopback is no longer on it",
        widened.mayLogInWithoutPassword(InetAddress.getByName("127.0.0.1")));
  }

  @Test
  public void badNoPasswordSettingsAreRefused() throws Exception {
    assertRefused("{ \"allowNoPassword\": \"true\" }", "\"allowNoPassword\" must be true or false");
    assertRefused("{ \"allowNoPassword\": 1 }", "\"allowNoPassword\" must be true or false");
    assertRefused("{ \"noPasswordAllowFrom\": \"127.0.0.1\" }", "must be a list of strings");
    assertRefused("{ \"noPasswordAllowFrom\": [] }", "noPasswordAllowFrom must list");
    assertRefused("{ \"noPasswordAllowFrom\": [127] }", "may only hold strings");
    assertRefused("{ \"noPasswordAllowFrom\": [\"::1\",] }", "invalid JSON");
    assertRefused("{ \"noPasswordAllowFrom\": [\"::1\" }", "invalid JSON");
    assertRefused("{ \"noPasswordAllowFrom\": [[\"::1\"]] }", "may only hold strings");
    assertRefused("{ \"noPasswordAllowFrom\": [\"localhost\"] }", "not an IP address or CIDR");
    assertRefused("{ \"noPasswordAllowFrom\": [\"10.0.0.0/40\"] }", "after '/'");
  }

  /** Review finding: both of these were accepted. */
  @Test
  public void sloppyNumbersAndEscapesAreRefused() throws Exception {
    assertRefused("{ \"port\": 025575 }", "must not start with 0");
    assertRefused("{ \"port\": -025575 }", "must not start with 0");
    assertRefused("{ \"host\": \"\\u+041\" }", "bad \\u escape");
    assertRefused("{ \"host\": \"\\u-041\" }", "bad \\u escape");
    assertRefused("{ \"host\": \"\\u00g1\" }", "bad \\u escape");
    assertRefused("{ \"host\": \"\\u00\" }", "\\u escape");

    Files.writeString(file, "{ \"host\": \"\\u0031\\u0030.0.0.1\", \"port\": 0 }");
    try {
      loader.load();
      fail();
    } catch (ConfigException e) {
      assertTrue("A lone 0 is a number (and out of range here)", e.getMessage().contains("port"));
    }
  }

  /**
   * Review finding: a config in a folder that cannot be read counted as "no config yet", so a new
   * password was made. It must count as a config that cannot be used.
   */
  @Test
  public void aConfigInAnUnreadableFolderIsNotTakenForAMissingOne() throws Exception {
    Assume.assumeTrue(
        "SKIPPED: this test needs POSIX file permissions",
        file.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(file, "{ \"port\": 25575 }");
    Path folder = file.getParent();
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("---------"));
    try {
      Assume.assumeFalse(
          "SKIPPED: running as a user that ignores file permissions (root)",
          Files.isReadable(file));
      try {
        RconConfig loaded = loader.load();
        fail("Must not fall back to defaults, but gave " + loaded);
      } catch (ConfigException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("cannot read"));
      }
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  public void aConfigWrittenByAnOlderVersionStillLoads() throws Exception {
    // The keys the plugin wrote before the limits existed, as hytale-docker also writes them
    Files.writeString(
        file,
        "{\"host\":\"0.0.0.0\",\"port\":25575,\"maxConnections\":10,\"maxFrameSize\":4096,"
            + "\"readTimeoutMs\":30000,\"connectionTimeoutMs\":5000,"
            + "\"passwordHash\":\"MDEyMzQ1Njc4OWFiY2RlZg==:8AdiwaCQPY2bwPYtAg7zMaYrocpiF+opzLdjcWFc8hA=\"}");

    RconConfig config = loader.load();

    assertEquals("0.0.0.0", config.getHost());
    assertTrue(config.requiresPassword());
    assertEquals("New settings take their defaults", 5, config.getAuthMaxFailures());
  }

  @Test
  public void nullPasswordHashMeansNoPasswordYet() throws Exception {
    Files.writeString(file, "{ \"port\": 25575, \"passwordHash\": null }");
    assertNull(loader.load().getPasswordHash());
  }

  @Test
  public void brokenJsonIsRefused() throws Exception {
    assertRefused("", "invalid JSON");
    assertRefused("{", "invalid JSON");
    assertRefused("{ \"port\": 25575", "invalid JSON");
    assertRefused("{ \"port\": 25575, }", "invalid JSON");
    assertRefused("{ \"port\" 25575 }", "invalid JSON");
    assertRefused("{ \"port\": 25575 } {}", "after the closing");
    assertRefused("[1, 2]", "invalid JSON");
    assertRefused("{ \"port\": 25575, \"port\": 25576 }", "duplicate key");
    assertRefused("{ \"limits\": { \"port\": 1 } }", "nested");
    assertRefused("{ \"port\": 25575.5 }", "whole numbers");
  }

  @Test
  public void unknownKeysAreRefused() throws Exception {
    assertRefused("{ \"passwrdHash\": \"a:b\" }", "unknown key \"passwrdHash\"");
  }

  @Test
  public void wrongTypesAndRangesAreRefused() throws Exception {
    assertRefused("{ \"port\": \"25575\" }", "\"port\" must be a whole number");
    assertRefused("{ \"port\": true }", "\"port\" must be a whole number");
    assertRefused("{ \"port\": 99999999999 }", "\"port\" must be a whole number");
    assertRefused("{ \"host\": 127 }", "\"host\" must be a string");
    assertRefused("{ \"port\": 0 }", "port must be between");
    assertRefused("{ \"maxFrameSize\": 10 }", "maxFrameSize must be between");
    assertRefused("{ \"authMaxFailures\": 0 }", "authMaxFailures must be between");
    assertRefused("{ \"passwordHash\": \"\" }", "passwordHash is not a valid hash");
    assertRefused("{ \"passwordHash\": \"not-a-hash\" }", "passwordHash is not a valid hash");
  }

  @Test
  public void errorsNeverQuoteThePasswordHash() throws Exception {
    Files.writeString(file, "{ \"passwordHash\": \"c2VjcmV0c2FsdA==:c2VjcmV0\" }");
    try {
      loader.load();
      fail();
    } catch (ConfigException e) {
      assertFalse(e.getMessage().contains("c2VjcmV0"));
    }
  }

  @Test
  public void stringEscapesAreRead() throws Exception {
    Files.writeString(file, "{ \"host\": \"local\\u0068ost\" }");
    assertEquals("localhost", loader.load().getHost());
  }

  @Test
  public void aWorldReadableFileIsTightened() throws Exception {
    Assume.assumeTrue(
        "SKIPPED: this test needs POSIX file permissions",
        file.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(file, "{ \"port\": 25575 }");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    assertTrue(loader.restrictPermissions());
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertFalse("Already private: nothing to do", loader.restrictPermissions());
  }

  @Test
  public void savingOverAWorldReadableFileLeavesItPrivate() throws Exception {
    Assume.assumeTrue(
        "SKIPPED: this test needs POSIX file permissions",
        file.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(file, "{ \"port\": 25575 }");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    assertTrue(loader.save(RconConfig.builder().build()));

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
  }
}
