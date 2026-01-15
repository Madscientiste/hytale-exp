package com.rcon.protocol;

import com.rcon.infrastructure.RconLogger;
import java.util.ArrayList;
import java.util.List;

/**
 * Main protocol handler - pure parsing and validation logic.
 * Input: bytes, Output: messages OR protocol errors
 */
public class RconProtocol {

    public static final int MAX_FRAME_SIZE = 4096;
    public static final int HEADER_SIZE = 4;

    private final RconLogger logger;

    public RconProtocol(RconLogger logger) {
        this.logger = logger;
    }

    /**
     * Parses incoming bytes into RCON packets.
     * 
     * @param data Raw bytes from transport layer
     * @return ProtocolResult containing parsed packets or error
     */
    public ProtocolResult parseBytes(String connectionId, byte[] data) {
        try {
            // Basic size validation
            if (data.length > MAX_FRAME_SIZE) {
                return new ProtocolError("Frame too large: " + data.length + " > " + MAX_FRAME_SIZE);
            }

            if (data.length < HEADER_SIZE) {
                return new ProtocolError("Frame too small: " + data.length + " < " + HEADER_SIZE);
            }

            // Parse length-prefixed packets
            List<RconPacket> packets = new ArrayList<>();
            int offset = 0;

            while (offset < data.length) {
                // Read packet size
                int packetSize = readIntLittleEndian(data, offset);

                // RCON standard minimum: 4 (id) + 4 (type) + 0 (body) + 1 (body_null) + 1
                // (padding_null) = 10 bytes
                if (packetSize < 10 || packetSize > MAX_FRAME_SIZE) {
                    return new ProtocolError("Invalid packet size: " + packetSize);
                }

                // Check if we have enough data for complete packet
                if (offset + HEADER_SIZE + packetSize > data.length) {
                    return new ProtocolError("Incomplete packet: need " + (offset + HEADER_SIZE + packetSize) +
                            ", have " + data.length);
                }

                // Extract packet data
                byte[] packetData = new byte[HEADER_SIZE + packetSize];
                System.arraycopy(data, offset, packetData, 0, packetData.length);

                try {
                    RconPacket packet = RconPacket.fromBytes(packetData);
                    packets.add(packet);

                    logger.logPacketReceived(connectionId, packet.getId(), packet.getType(), packet.getBody().length());

                    offset += packetData.length;

                    // Skip potential padding between packets
                    while (offset < data.length && data[offset] == 0) {
                        offset++;
                    }

                } catch (ProtocolException e) {
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

    /**
     * Formats packet into bytes for transmission.
     * 
     * @param packet Packet to format
     * @return Byte array ready for transport layer
     */
    public byte[] formatPacket(RconPacket packet) {
        byte[] data = packet.toBytes();

        // Enforce size limits
        if (data.length > MAX_FRAME_SIZE) {
            throw new RuntimeException("Packet too large: " + data.length);
        }

        logger.logPacketSent("unknown", packet.getId(), packet.getType(), packet.getBody().length());
        return data;
    }

    /**
     * Creates authentication response packet.
     */
    public RconPacket createAuthResponse(int requestId, boolean success) {
        return new RconPacket(requestId, RconPacket.SERVERDATA_AUTH_RESPONSE, success ? "1" : "-1");
    }

    /**
     * Creates command response packet.
     */
    public RconPacket createCommandResponse(int requestId, String response) {
        return new RconPacket(requestId, RconPacket.SERVERDATA_RESPONSE_VALUE, response);
    }

    private int readIntLittleEndian(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) |
                ((buffer[offset + 1] & 0xFF) << 8) |
                ((buffer[offset + 2] & 0xFF) << 16) |
                ((buffer[offset + 3] & 0xFF) << 24);
    }

    /**
     * Sealed interface for protocol parsing results.
     */
    public sealed interface ProtocolResult {
        boolean isSuccess();

        String getErrorMessage();
    }

    /**
     * Successful parsing result containing packets.
     */
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

    /**
     * Error result from protocol parsing.
     */
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