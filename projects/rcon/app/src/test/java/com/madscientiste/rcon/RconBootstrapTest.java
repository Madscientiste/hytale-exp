package com.madscientiste.rcon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.madscientiste.rcon.application.LogCapture;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.logging.RconLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * What the plugin does with its config file at setup. {@code RconPlugin.setup()} only calls {@link
 * RconBootstrap#prepare}; the plugin class itself needs a running Hytale server to be built.
 */
public class RconBootstrapTest {

  private final RconLogger logger = RconLogger.createPluginLogger(null);
  private Path dir;
  private Path file;
  private LogCapture logs;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("rcon-bootstrap").resolve("configs");
    file = dir.resolve("com.madscientiste.rcon.json");
    logs = new LogCapture();
  }

  @After
  public void tearDown() throws Exception {
    logs.close();
    if (Files.exists(dir.getParent())) {
      // Give the folder back its permissions so the temp files can be cleaned up
      try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      } catch (Exception e) {
        // Not there or not POSIX
      }
    }
  }

  private long filesIn(Path folder) throws Exception {
    try (Stream<Path> files = Files.list(folder)) {
      return files.count();
    }
  }

  @Test
  public void aBrokenConfigKeepsRconOffAndIsLeftUntouched() throws Exception {
    Files.createDirectories(dir);
    String broken = "{\n  \"port\": 25575,\n  \"passwrdHash\": \"typo-in-the-key\"\n}\n";
    Files.writeString(file, broken);

    RconServer server = RconBootstrap.prepare(file, logger);

    assertNull("No server: RCON stays off", server);
    assertEquals("The file is not changed", broken, Files.readString(file));
    assertEquals("Nothing else is written next to it", 1, filesIn(dir));
    if (logs.isAvailable()) {
      assertTrue(
          "The log says RCON is off and why",
          logs.await(
              m ->
                  m.contains("RCON is DISABLED")
                      && m.contains("unknown key")
                      && m.contains("passwrdHash"),
              2000));
      assertFalse("No password is generated", logs.anyMessage(m -> m.contains("auto-generated")));
    }
  }

  /** Review finding: an unreadable folder looked like "no config yet" and got a new password. */
  @Test
  public void anUnreadableConfigFolderKeepsRconOffAndWritesNothing() throws Exception {
    Files.createDirectories(dir);
    Assume.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(file, "{ \"port\": 25575 }");
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"));
    Assume.assumeFalse("Running as a user that ignores permissions", Files.isReadable(file));

    RconServer server = RconBootstrap.prepare(file, logger);

    assertNull("No server: RCON stays off", server);
    if (logs.isAvailable()) {
      assertTrue(logs.await(m -> m.contains("RCON is DISABLED"), 2000));
      assertFalse("No password is generated", logs.anyMessage(m -> m.contains("auto-generated")));
    }

    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    assertEquals("{ \"port\": 25575 }", Files.readString(file));
    assertEquals(1, filesIn(dir));
  }

  @Test
  public void firstStartGeneratesAPasswordAndSavesItsHashPrivately() throws Exception {
    RconServer server = RconBootstrap.prepare(file, logger);

    assertNotNull(server);
    assertTrue(server.getConfig().requiresPassword());
    assertFalse(AuthenticationService.isLegacyHash(server.getConfig().getPasswordHash()));
    assertTrue(Files.readString(file).contains(server.getConfig().getPasswordHash()));
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }

  @Test
  public void aConfigWithoutHashGetsAPasswordUnlessTheOwnerOptedOut() throws Exception {
    Files.createDirectories(dir);

    Files.writeString(file, "{ \"port\": 25575 }");
    RconServer withPassword = RconBootstrap.prepare(file, logger);
    assertNotNull(withPassword);
    assertTrue(withPassword.getConfig().requiresPassword());

    String optedIn = "{ \"port\": 25575, \"allowNoPassword\": true }";
    Files.writeString(file, optedIn);
    RconServer open = RconBootstrap.prepare(file, logger);
    assertNotNull(open);
    assertFalse("No password is made behind the owner's back", open.getConfig().requiresPassword());
    assertTrue(open.getConfig().isNoPasswordMode());
    assertEquals("The file is left as the owner wrote it", optedIn, Files.readString(file));
  }
}
