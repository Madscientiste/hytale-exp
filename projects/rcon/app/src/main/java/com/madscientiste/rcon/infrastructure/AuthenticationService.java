package com.madscientiste.rcon.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Handles password hashing and validation for RCON authentication.
 *
 * <p>Two stored formats are understood:
 *
 * <ul>
 *   <li>Current: {@code pbkdf2-sha512:<iterations>:<base64 salt>:<base64 hash>}. A slow hash, so a
 *       leaked config file does not give the password away quickly.
 *   <li>Legacy: {@code <base64 salt>:<base64 hash>}, one round of salted SHA-256. Still verified so
 *       existing configs keep working, but no longer produced.
 * </ul>
 */
public class AuthenticationService {

  /** Human-readable description of the accepted formats, for error messages. */
  public static final String HASH_FORMAT_DESCRIPTION =
      "pbkdf2-sha512:iterations:base64salt:base64hash, or the legacy base64salt:base64hash";

  private static final String PBKDF2_PREFIX = "pbkdf2-sha512";
  private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA512";
  private static final int PBKDF2_ITERATIONS = 210_000;
  private static final int PBKDF2_MAX_ITERATIONS = 10_000_000;
  private static final int PBKDF2_HASH_BYTES = 32;

  private static final String LEGACY_ALGORITHM = "SHA-256";
  private static final int LEGACY_HASH_BYTES = 32;

  private static final int SALT_LENGTH = 16;
  private static final int PASSWORD_LENGTH = 24;
  private static final String PASSWORD_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";

  /** A stored hash split into its parts. */
  private record ParsedHash(boolean legacy, int iterations, byte[] salt, byte[] hash) {}

  /**
   * Hash a password with a random salt.
   *
   * @param password Plain text password
   * @return Hashed password in format: pbkdf2-sha512:iterations:base64(salt):base64(hash)
   */
  @Nonnull
  public static String hashPassword(@Nonnull String password) {
    byte[] salt = new byte[SALT_LENGTH];
    new SecureRandom().nextBytes(salt);

    try {
      byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS, PBKDF2_HASH_BYTES);
      Base64.Encoder b64 = Base64.getEncoder();
      return PBKDF2_PREFIX
          + ":"
          + PBKDF2_ITERATIONS
          + ":"
          + b64.encodeToString(salt)
          + ":"
          + b64.encodeToString(hash);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(PBKDF2_ALGORITHM + " is not available", e);
    }
  }

  /**
   * Verify a password against a stored hash (current or legacy format).
   *
   * @param password Plain text password to verify
   * @param storedHash Stored hash
   * @return true if password matches, false otherwise
   */
  public static boolean verifyPassword(@Nonnull String password, @Nonnull String storedHash) {
    try {
      ParsedHash parsed = parse(storedHash);
      if (parsed == null) {
        return false;
      }

      byte[] actual =
          parsed.legacy()
              ? legacyHash(password, parsed.salt())
              : pbkdf2(password, parsed.salt(), parsed.iterations(), parsed.hash().length);

      // Constant-time comparison
      return MessageDigest.isEqual(parsed.hash(), actual);
    } catch (Exception e) {
      return false;
    }
  }

  /** True if the string is a well-formed hash in one of the accepted formats. */
  public static boolean isValidHash(@Nullable String storedHash) {
    return storedHash != null && parse(storedHash) != null;
  }

  /** True if the string is a well-formed hash in the legacy (single SHA-256) format. */
  public static boolean isLegacyHash(@Nullable String storedHash) {
    if (storedHash == null) {
      return false;
    }
    ParsedHash parsed = parse(storedHash);
    return parsed != null && parsed.legacy();
  }

  /**
   * Generate a cryptographically secure random password.
   *
   * @return A secure random password string of length PASSWORD_LENGTH
   */
  @Nonnull
  public static String generateSecurePassword() {
    SecureRandom random = new SecureRandom();
    StringBuilder password = new StringBuilder(PASSWORD_LENGTH);

    for (int i = 0; i < PASSWORD_LENGTH; i++) {
      int index = random.nextInt(PASSWORD_CHARS.length());
      password.append(PASSWORD_CHARS.charAt(index));
    }

    return password.toString();
  }

  /** Split a stored hash into its parts. Returns null when it is not well-formed. */
  @Nullable
  private static ParsedHash parse(@Nonnull String storedHash) {
    try {
      String[] parts = storedHash.split(":", -1);
      Base64.Decoder b64 = Base64.getDecoder();

      if (parts.length == 2) {
        byte[] salt = b64.decode(parts[0]);
        byte[] hash = b64.decode(parts[1]);
        if (salt.length == 0 || hash.length != LEGACY_HASH_BYTES) {
          return null;
        }
        return new ParsedHash(true, 1, salt, hash);
      }

      if (parts.length == 4 && PBKDF2_PREFIX.equals(parts[0])) {
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = b64.decode(parts[2]);
        byte[] hash = b64.decode(parts[3]);
        if (iterations < 1
            || iterations > PBKDF2_MAX_ITERATIONS
            || salt.length == 0
            || hash.length < 16
            || hash.length > 64) {
          return null;
        }
        return new ParsedHash(false, iterations, salt, hash);
      }

      return null;
    } catch (IllegalArgumentException e) {
      // Bad base64 or a non-numeric iteration count
      return null;
    }
  }

  private static byte[] pbkdf2(
      @Nonnull String password, @Nonnull byte[] salt, int iterations, int hashBytes)
      throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, hashBytes * 8);
    try {
      return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }

  /** Legacy format: SHA-256 over salt followed by the password. */
  private static byte[] legacyHash(@Nonnull String password, @Nonnull byte[] salt)
      throws GeneralSecurityException {
    MessageDigest digest = MessageDigest.getInstance(LEGACY_ALGORITHM);
    digest.update(salt);
    digest.update(password.getBytes(StandardCharsets.UTF_8));
    return digest.digest();
  }

  /**
   * Command-line utility to generate password hashes. Usage: java AuthenticationService <password>
   */
  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: java AuthenticationService <password>");
      System.err.println("Generates a password hash for use in RCON configuration.");
      System.exit(1);
    }

    String password = args[0];
    String hash = hashPassword(password);
    System.out.println("Password hash: " + hash);
    System.out.println("Add this to your config.json:");
    System.out.println("  \"passwordHash\": \"" + hash + "\"");
    System.out.println("Password hash generated");
  }
}
