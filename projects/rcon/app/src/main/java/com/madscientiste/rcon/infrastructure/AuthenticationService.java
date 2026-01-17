package com.madscientiste.rcon.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.annotation.Nonnull;

/**
 * Handles password hashing and validation for RCON authentication. Uses SHA-256 with salt for
 * password storage (simple but sufficient for internal use). For production, consider
 * bcrypt/Argon2, but SHA-256 is acceptable for internal tools.
 */
public class AuthenticationService {

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int SALT_LENGTH = 16;
  private static final int PASSWORD_LENGTH = 24;
  private static final String PASSWORD_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";

  /**
   * Hash a password with a random salt.
   *
   * @param password Plain text password
   * @return Hashed password in format: base64(salt):base64(hash)
   */
  @Nonnull
  public static String hashPassword(@Nonnull String password) {
    try {
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[SALT_LENGTH];
      random.nextBytes(salt);

      byte[] hash = hashWithSalt(password, salt);

      String saltBase64 = Base64.getEncoder().encodeToString(salt);
      String hashBase64 = Base64.getEncoder().encodeToString(hash);

      return saltBase64 + ":" + hashBase64;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Verify a password against a stored hash.
   *
   * @param password Plain text password to verify
   * @param storedHash Stored hash in format: base64(salt):base64(hash)
   * @return true if password matches, false otherwise
   */
  public static boolean verifyPassword(@Nonnull String password, @Nonnull String storedHash) {
    try {
      String[] parts = storedHash.split(":", 2);
      if (parts.length != 2) {
        return false;
      }

      byte[] salt = Base64.getDecoder().decode(parts[0]);
      byte[] expectedHash = Base64.getDecoder().decode(parts[1]);

      byte[] actualHash = hashWithSalt(password, salt);

      return MessageDigest.isEqual(expectedHash, actualHash);
    } catch (Exception e) {
      return false;
    }
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

  /** Hash password with salt using SHA-256. */
  private static byte[] hashWithSalt(@Nonnull String password, @Nonnull byte[] salt)
      throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
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
  }
}
