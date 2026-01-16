package com.madscientiste.rcon.protocol;

import static org.junit.Assert.*;

import com.madscientiste.rcon.infrastructure.RconLogger;
import java.util.List;
import org.junit.Test;

/**
 * Tests for RconProtocol parsing and validation. Critical for security - tests edge cases and
 * malformed input.
 */
public class RconProtocolTest {

  private RconProtocol protocol;
  private RconLogger logger;

  public void setUp() {
    logger = new RconLogger();
    protocol = new RconProtocol(logger);
  }

  @Test
  public void testValidPacketParsing() {
    setUp();
    RconPacket original = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
    byte[] data = original.toBytes();

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", data);

    assertTrue("Should parse successfully", result.isSuccess());
    assertTrue("Should be ProtocolSuccess", result instanceof RconProtocol.ProtocolSuccess);

    List<RconPacket> packets = ((RconProtocol.ProtocolSuccess) result).getPackets();
    assertEquals("Should have one packet", 1, packets.size());
    assertEquals(original, packets.get(0));
  }

  @Test
  public void testMultiplePacketsInOneBuffer() {
    setUp();
    RconPacket packet1 = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "first");
    RconPacket packet2 = new RconPacket(124, RconPacket.SERVERDATA_RESPONSE_VALUE, "second");

    byte[] data1 = packet1.toBytes();
    byte[] data2 = packet2.toBytes();

    byte[] combined = new byte[data1.length + data2.length];
    System.arraycopy(data1, 0, combined, 0, data1.length);
    System.arraycopy(data2, 0, combined, data1.length, data2.length);

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", combined);

    assertTrue("Should parse successfully", result.isSuccess());
    List<RconPacket> packets = ((RconProtocol.ProtocolSuccess) result).getPackets();
    assertEquals("Should have two packets", 2, packets.size());
    assertEquals(packet1, packets.get(0));
    assertEquals(packet2, packets.get(1));
  }

  @Test
  public void testOversizedFrame() {
    setUp();
    // Create data larger than MAX_FRAME_SIZE
    byte[] oversized = new byte[RconProtocol.MAX_FRAME_SIZE + 100];

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", oversized);

    assertFalse("Should fail for oversized frame", result.isSuccess());
    assertTrue("Should be ProtocolError", result instanceof RconProtocol.ProtocolError);
    assertTrue("Error message should mention size", result.getErrorMessage().contains("too large"));
  }

  @Test
  public void testUndersizedFrame() {
    setUp();
    byte[] tooSmall = {1, 2, 3}; // Less than HEADER_SIZE

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", tooSmall);

    assertFalse("Should fail for undersized frame", result.isSuccess());
    assertTrue("Should be ProtocolError", result instanceof RconProtocol.ProtocolError);
  }

  @Test
  public void testInvalidPacketSize() {
    setUp();
    // Create packet with invalid size field
    RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
    byte[] data = packet.toBytes();

    // Corrupt size field to invalid value
    data[0] = (byte) 0xFF; // Huge size
    data[1] = (byte) 0xFF;
    data[2] = (byte) 0xFF;
    data[3] = (byte) 0xFF;

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", data);

    assertFalse("Should fail for invalid packet size", result.isSuccess());
    assertTrue("Should be ProtocolError", result instanceof RconProtocol.ProtocolError);
  }

  @Test
  public void testIncompletePacket() {
    setUp();
    // Create valid packet but truncate it
    RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
    byte[] data = packet.toBytes();

    // Truncate last few bytes
    byte[] incomplete = new byte[data.length - 5];
    System.arraycopy(data, 0, incomplete, 0, incomplete.length);

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", incomplete);

    assertFalse("Should fail for incomplete packet", result.isSuccess());
    assertTrue("Should be ProtocolError", result instanceof RconProtocol.ProtocolError);
    assertTrue(
        "Error message should mention incomplete", result.getErrorMessage().contains("Incomplete"));
  }

  @Test
  public void testMalformedPacket() {
    setUp();
    // Create completely malformed data
    byte[] malformed = new byte[20];
    for (int i = 0; i < malformed.length; i++) {
      malformed[i] = (byte) (i % 256);
    }

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", malformed);

    // Should not crash - either success with valid packets or error
    assertTrue(
        "Should be either success or error",
        result.isSuccess() || result instanceof RconProtocol.ProtocolError);
  }

  @Test
  public void testEmptyPayload() {
    setUp();
    RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "");
    byte[] data = packet.toBytes();

    RconProtocol.ProtocolResult result = protocol.parseBytes("test-conn", data);

    assertTrue("Should handle empty payload", result.isSuccess());
    List<RconPacket> packets = ((RconProtocol.ProtocolSuccess) result).getPackets();
    assertEquals("Should have one packet", 1, packets.size());
    assertEquals("", packets.get(0).getBody());
  }

  @Test
  public void testPacketFormatting() {
    setUp();
    RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_RESPONSE_VALUE, "response");

    byte[] formatted = protocol.formatPacket(packet);

    assertNotNull("Should return formatted bytes", formatted);
    assertTrue("Should have data", formatted.length > 0);

    // Should be able to parse it back
    RconProtocol.ProtocolResult parseResult = protocol.parseBytes("test-conn", formatted);
    assertTrue("Should be parsable", parseResult.isSuccess());
  }

  @Test(expected = RuntimeException.class)
  public void testFormatOversizedPacket() {
    setUp();
    // Create packet that will be too large when formatted
    StringBuilder hugeBody = new StringBuilder();
    for (int i = 0; i < RconProtocol.MAX_FRAME_SIZE; i++) {
      hugeBody.append("x");
    }

    RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, hugeBody.toString());
    protocol.formatPacket(packet); // Should throw exception
  }
}
