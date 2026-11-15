package com.madscientiste.rcon.infrastructure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
            .build();

    assertTrue(loader.save(saved));
    RconConfig loaded = loader.load();

    assertEquals(saved.toString(), loaded.toString());
    assertEquals(hash, loaded.getPasswordHash());
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
    Assume.assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(file, "{ \"port\": 25575 }");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    assertTrue(loader.restrictPermissions());
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertFalse("Already private: nothing to do", loader.restrictPermissions());
  }

  @Test
  public void savingOverAWorldReadableFileLeavesItPrivate() throws Exception {
    Assume.assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(file, "{ \"port\": 25575 }");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    assertTrue(loader.save(RconConfig.builder().build()));

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
  }
}
