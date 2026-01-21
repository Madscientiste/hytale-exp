package com.madscientiste.rcon.protocol;

import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.RconLogger;
import java.util.ArrayList;
import java.util.List;

public class RconProtocol {

  public static final int MAX_FRAME_SIZE = 4096;
  public static final int HEADER_SIZE = 4;

  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_PROTOCOL);

  public RconProtocol() {}

  public ProtocolResult parseBytes(String connectionId, byte[] data) {
    try {
      if (data.length > MAX_FRAME_SIZE) {
        return new ProtocolError("Frame too large: " + data.length + " > " + MAX_FRAME_SIZE);
      }

      if (data.length < HEADER_SIZE) {
        return new ProtocolError("Frame too small: " + data.length + " < " + HEADER_SIZE);
      }

      List<RconPacket> packets = new ArrayList<>();
      int offset = 0;

      while (offset < data.length) {
        int packetSize = readIntLittleEndian(data, offset);

        if (packetSize < 10 || packetSize > MAX_FRAME_SIZE) {
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
          // Log protocol error for packet parsing failure
          logger
              .event(LogEvent.PROTOCOL_ERROR)
              .withParam("error_code", "packet_parsing_failed")
              .withParam("message", "Packet parsing failed: " + e.getMessage())
              .withOptionalParam("connection_id", connectionId)
              .withCause(e)
              .log();
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

    if (data.length > MAX_FRAME_SIZE) {
      throw new RuntimeException("Packet too large: " + data.length);
    }

    // Fine detail logging - not a structured event, just debug info
    logger.atFine().log(
        "Packet sent: id=%d, type=%d, bodyLength=%d",
        packet.getId(), packet.getType(), packet.getBody().length());
    return data;
  }

  public RconPacket createAuthResponse(int requestId, boolean success) {
    return new RconPacket(requestId, RconPacket.SERVERDATA_AUTH_RESPONSE, success ? "1" : "-1");
  }

  public RconPacket createCommandResponse(int requestId, String response) {
    return new RconPacket(requestId, RconPacket.SERVERDATA_RESPONSE_VALUE, response);
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
