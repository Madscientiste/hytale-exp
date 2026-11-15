package com.madscientiste.rcon.infrastructure;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AuthenticationServiceTest {

  /** Made with openssl: base64(salt) ":" base64(SHA-256(salt + "legacy-test-password")). */
  private static final String LEGACY =
      "MDEyMzQ1Njc4OWFiY2RlZg==:8AdiwaCQPY2bwPYtAg7zMaYrocpiF+opzLdjcWFc8hA=";

  @Test
  public void newHashUsesThePbkdf2FormatAndVerifies() {
    String hash = AuthenticationService.hashPassword("s3cret pass");

    assertTrue(hash, hash.matches("pbkdf2-sha512:\\d+:[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+"));
    assertTrue(AuthenticationService.isValidHash(hash));
    assertFalse(AuthenticationService.isLegacyHash(hash));
    assertTrue(AuthenticationService.verifyPassword("s3cret pass", hash));
    assertFalse(AuthenticationService.verifyPassword("s3cret pasS", hash));
    assertFalse(AuthenticationService.verifyPassword("", hash));
  }

  @Test
  public void everyHashGetsItsOwnSalt() {
    assertNotEquals(
        AuthenticationService.hashPassword("same"), AuthenticationService.hashPassword("same"));
  }

  @Test
  public void legacyHashIsRecognisedAndVerifies() {
    assertTrue(AuthenticationService.isValidHash(LEGACY));
    assertTrue(AuthenticationService.isLegacyHash(LEGACY));
    assertTrue(AuthenticationService.verifyPassword("legacy-test-password", LEGACY));
    assertFalse(AuthenticationService.verifyPassword("Legacy-test-password", LEGACY));
  }

  @Test
  public void emptyAndUnicodePasswordsRoundTrip() {
    String empty = AuthenticationService.hashPassword("");
    assertTrue(AuthenticationService.verifyPassword("", empty));
    assertFalse(AuthenticationService.verifyPassword(" ", empty));

    String unicode = AuthenticationService.hashPassword("pässwörd-密码");
    assertTrue(AuthenticationService.verifyPassword("pässwörd-密码", unicode));
    assertFalse(AuthenticationService.verifyPassword("password-密码", unicode));
  }

  /**
   * Too few iterations is not a slow hash; too many makes one login take longer than the login
   * deadline (10 million took 6.9 s). Both are refused. In between, anything below what new hashes
   * get is accepted but flagged as weak.
   */
  @Test
  public void iterationCountMustBeInASaneRange() {
    String tail = ":c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    assertFalse(AuthenticationService.isValidHash("pbkdf2-sha512:1" + tail));
    assertFalse(AuthenticationService.isValidHash("pbkdf2-sha512:9999" + tail));
    assertTrue(AuthenticationService.isValidHash("pbkdf2-sha512:10000" + tail));
    assertTrue(AuthenticationService.isValidHash("pbkdf2-sha512:1000000" + tail));
    assertFalse(AuthenticationService.isValidHash("pbkdf2-sha512:1000001" + tail));
    assertFalse(AuthenticationService.isValidHash("pbkdf2-sha512:10000000" + tail));

    assertTrue(AuthenticationService.hasWeakIterations("pbkdf2-sha512:10000" + tail));
    assertTrue(AuthenticationService.hasWeakIterations("pbkdf2-sha512:209999" + tail));
    assertFalse(AuthenticationService.hasWeakIterations("pbkdf2-sha512:210000" + tail));
    assertFalse(
        "A new hash is never weak",
        AuthenticationService.hasWeakIterations(AuthenticationService.hashPassword("x")));
    assertFalse(
        "The old format has its own warning", AuthenticationService.hasWeakIterations(LEGACY));
    assertFalse(AuthenticationService.hasWeakIterations(null));
    assertFalse(AuthenticationService.hasWeakIterations("pbkdf2-sha512:1" + tail));
  }

  @Test
  public void malformedHashesAreInvalidAndNeverVerify() {
    String[] malformed = {
      "",
      ":",
      "nocolon",
      "not base64!:also not!",
      "c2FsdA==:dG9vc2hvcnQ=", // legacy digest of the wrong length
      "pbkdf2-sha512:0:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // zero iterations
      "pbkdf2-sha512:abc:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "pbkdf2-sha512:999999999:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "pbkdf2-sha512:210000::AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // empty salt
      "pbkdf2-sha512:210000:c2FsdA==:", // empty hash
      "md5:210000:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // unknown scheme
      "PBKDF2-SHA512:210000:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "pbkdf2-sha512:+210000:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // sign
      "pbkdf2-sha512: 210000:c2FsdA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "a:b:c",
    };

    for (String hash : malformed) {
      assertFalse("should be invalid: " + hash, AuthenticationService.isValidHash(hash));
      assertFalse("should not verify: " + hash, AuthenticationService.verifyPassword("", hash));
      assertFalse("should not verify: " + hash, AuthenticationService.verifyPassword("x", hash));
    }
    assertFalse(AuthenticationService.isValidHash(null));
    assertFalse(AuthenticationService.isLegacyHash(null));
  }
}
