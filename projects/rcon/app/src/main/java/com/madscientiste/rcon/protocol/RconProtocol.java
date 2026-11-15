package com.madscientiste.rcon.protocol;

import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.RconLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RconProtocol {

  /** Default limit for a packet (size prefix included); the config can change it. */
  public static final int MAX_FRAME_SIZE = RconConstants.DEFAULT_MAX_FRAME_SIZE;

  public static final int HEADER_SIZE = 4;

  /** Bytes in a packet besides the body: size, id, type and the two null terminators. */
  public static final int PACKET_OVERHEAD = 14;

  /** Request id of a failed login reply, as the RCON protocol defines it. */
  public static final int AUTH_FAILED_ID = -1;

  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_PROTOCOL);

  /** Largest packet accepted from a client, size prefix included. */
  private final int maxFrameSize;

  /** Largest packet sent to a client, size prefix included. */
  private final int maxOutboundSize;

  public RconProtocol() {
    this(MAX_FRAME_SIZE);
  }

  public RconProtocol(int maxFrameSize) {
    this.maxFrameSize = maxFrameSize;
    this.maxOutboundSize = Math.min(maxFrameSize, RconConstants.MAX_OUTBOUND_PACKET_SIZE);
  }

  public int getMaxFrameSize() {
    return maxFrameSize;
  }

  public ProtocolResult parseBytes(String connectionId, byte[] data) {
    try {
      if (data.length > maxFrameSize) {
        return new ProtocolError("Frame too large: " + data.length + " > " + maxFrameSize);
      }

      if (data.length < HEADER_SIZE) {
        return new ProtocolError("Frame too small: " + data.length + " < " + HEADER_SIZE);
      }

      List<RconPacket> packets = new ArrayList<>();
      int offset = 0;

      while (offset < data.length) {
        int packetSize = readIntLittleEndian(data, offset);

        if (packetSize < 10 || packetSize > maxFrameSize) {
          return new ProtocolError("Invalid packet size: " + packetSize);
        }

        if (offset > Integer.MAX_VALUE - HEADER_SIZE - packetSize) {
          return new ProtocolError("Invalid packet size or overflow");
        }

        if (offset + HEADER_SIZE + packetSize > data.length) {
          return new ProtocolError(
              "Incomplete packet: need "
                  + (offset + HEADER_SIZE + packetSize)
                  + ", have "
                  + data.length);
        }

        byte[] packetData = new byte[HEADER_SIZE + packetSize];
        System.arraycopy(data, offset, packetData, 0, packetData.length);

        try {
          RconPacket packet = RconPacket.fromBytes(packetData);
          packets.add(packet);

          // Fine detail logging - not a structured event, just debug info
          logger.atFine().log(
              "Packet received: id=%d, type=%d, bodyLength=%d",
              packet.getId(), packet.getType(), packet.getBody().length());

          offset += packetData.length;

          while (offset < data.length && data[offset] == 0) {
            offset++;
          }

        } catch (ProtocolException e) {
          // A malformed packet is the peer's doing, not a server fault: the caller logs one
          // "packet.invalid" line. No stack trace here, or junk input could flood the log.
          return new ProtocolError("Packet parsing failed: " + e.getMessage());
        }
      }

      if (packets.isEmpty()) {
        return new ProtocolError("No valid packets found");
      }

      return new ProtocolSuccess(packets);

    } catch (Exception e) {
      return new ProtocolError("Unexpected error: " + e.getMessage());
    }
  }

  public byte[] formatPacket(RconPacket packet) {
    byte[] data = packet.toBytes();

    if (data.length > maxOutboundSize) {
      throw new RuntimeException("Packet too large: " + data.length);
    }

    // Fine detail logging - not a structured event, just debug info
    logger.atFine().log(
        "Packet sent: id=%d, type=%d, bodyLength=%d",
        packet.getId(), packet.getType(), packet.getBody().length());
    return data;
  }

  /**
   * Reply to a login. On success the request id is echoed; on failure the id is -1, which is how
   * RCON clients tell a refused login from an accepted one.
   */
  public RconPacket createAuthResponse(int requestId, boolean success) {
    return new RconPacket(
        success ? requestId : AUTH_FAILED_ID,
        RconPacket.SERVERDATA_AUTH_RESPONSE,
        success ? "1" : "-1");
  }

  public RconPacket createCommandResponse(int requestId, String response) {
    return new RconPacket(requestId, RconPacket.SERVERDATA_RESPONSE_VALUE, response);
  }

  /**
   * Reply to a command. A reply that does not fit in one packet is split over several packets with
   * the same request id, in order, as Source and Minecraft servers do. Never splits inside a UTF-8
   * character.
   */
  public List<RconPacket> createCommandResponses(int requestId, String response) {
    byte[] bytes = (response != null ? response : "").getBytes(StandardCharsets.UTF_8);
    int maxBody = maxOutboundSize - PACKET_OVERHEAD;
    List<RconPacket> packets = new ArrayList<>();

    int start = 0;
    do {
      int end = Math.min(start + maxBody, bytes.length);
      // Step back to the start of a character: continuation bytes look like 10xxxxxx
      while (end < bytes.length && end > start && (bytes[end] & 0xC0) == 0x80) {
        end--;
      }
      if (end == start && end < bytes.length) {
        // Cannot happen with a body of 4 bytes or more; never loop forever
        end = Math.min(start + maxBody, bytes.length);
      }
      String part = new String(bytes, start, end - start, StandardCharsets.UTF_8);
      packets.add(new RconPacket(requestId, RconPacket.SERVERDATA_RESPONSE_VALUE, part));
      start = end;
    } while (start < bytes.length);

    return packets;
  }

  private int readIntLittleEndian(byte[] buffer, int offset) {
    return (buffer[offset] & 0xFF)
        | ((buffer[offset + 1] & 0xFF) << 8)
        | ((buffer[offset + 2] & 0xFF) << 16)
        | ((buffer[offset + 3] & 0xFF) << 24);
  }

  public sealed interface ProtocolResult {
    boolean isSuccess();

    String getErrorMessage();
  }

  public static final class ProtocolSuccess implements ProtocolResult {
    private final List<RconPacket> packets;

    public ProtocolSuccess(List<RconPacket> packets) {
      this.packets = new ArrayList<>(packets);
    }

    public List<RconPacket> getPackets() {
      return new ArrayList<>(packets);
    }

    @Override
    public boolean isSuccess() {
      return true;
    }

    @Override
    public String getErrorMessage() {
      return null;
    }
  }

  public static final class ProtocolError implements ProtocolResult {
    private final String message;

    public ProtocolError(String message) {
      this.message = message;
    }

    public String getError() {
      return message;
    }

    @Override
    public boolean isSuccess() {
      return false;
    }

    @Override
    public String getErrorMessage() {
      return message;
    }
  }
}
