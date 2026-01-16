package com.madscientiste.rcon.protocol;

/**
 * RCON packet structure for protocol layer. This is a pure data class - no external dependencies.
 */
public class RconPacket {
  // RCON packet types (from original RCON protocol)
  public static final int SERVERDATA_AUTH = 3;
  public static final int SERVERDATA_AUTH_RESPONSE = 2;
  public static final int SERVERDATA_EXECCOMMAND = 2;
  public static final int SERVERDATA_RESPONSE_VALUE = 0;

  private final int id;
  private final int type;
  private final String body;

  public RconPacket(int id, int type, String body) {
    this.id = id;
    this.type = type;
    this.body = body != null ? body : "";
  }

  public int getId() {
    return id;
  }

  public int getType() {
    return type;
  }

  public String getBody() {
    return body;
  }

  /**
   * Serializes packet to little-endian byte array (RCON protocol format). Format per Source RCON
   * standard: - 4-byte size (value = id + type + body + body_null + padding_null) - 4-byte id -
   * 4-byte type - body bytes - 1-byte body null terminator - 1-byte empty string padding (second
   * null)
   *
   * <p>Man this makes my head hurt; it is so simple
   */
  public byte[] toBytes() {
    byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // Size field value = id + type + body + body_null + padding_null
    int packetSize =
        4 + 4 + bodyBytes.length + 1 + 1; // id + type + body + body_null + padding_null

    // Total packet = size field (4) + packet content
    byte[] result = new byte[4 + packetSize];
    writeIntLittleEndian(result, 0, packetSize); // Size field
    writeIntLittleEndian(result, 4, id);
    writeIntLittleEndian(result, 8, type);
    System.arraycopy(bodyBytes, 0, result, 12, bodyBytes.length);
    result[12 + bodyBytes.length] = 0; // Body null terminator
    result[12 + bodyBytes.length + 1] = 0; // Empty string padding (second null)

    return result;
  }

  /**
   * Deserializes packet from little-endian byte array. Input should be the complete packet
   * including size prefix.
   */
  public static RconPacket fromBytes(byte[] data) throws ProtocolException {
    // RCON standard minimum: 4 (size) + 4 (id) + 4 (type) + 0 (body) + 1
    // (body_null) + 1 (padding_null) = 14 bytes
    if (data.length < 14) {
      throw new ProtocolException("Packet too short");
    }

    int declaredSize = readIntLittleEndian(data, 0);
    if (declaredSize != data.length - 4) {
      throw new ProtocolException(
          "Size mismatch: declared=" + declaredSize + ", actual=" + (data.length - 4));
    }

    int id = readIntLittleEndian(data, 4);
    int type = readIntLittleEndian(data, 8);

    // Calculate body length from declared size
    // declaredSize = 4 (id) + 4 (type) + bodyLength + 1 (body null) + 1 (padding null)
    // So: bodyLength = declaredSize - 10
    int bodyLength = declaredSize - 10;

    if (bodyLength < 0) {
      throw new ProtocolException("Invalid body length calculated from size");
    }

    int bodyStart = 12;
    int bodyNullIndex = bodyStart + bodyLength;
    int paddingNullIndex = bodyNullIndex + 1;

    // Verify null terminators exist at expected positions
    if (bodyNullIndex >= data.length || data[bodyNullIndex] != 0) {
      throw new ProtocolException("Missing body null terminator at expected position");
    }

    if (paddingNullIndex >= data.length || data[paddingNullIndex] != 0) {
      throw new ProtocolException("Missing padding null terminator");
    }

    // Extract body bytes (including any null bytes within the body) and decode as UTF-8
    byte[] bodyBytes = new byte[bodyLength];
    System.arraycopy(data, bodyStart, bodyBytes, 0, bodyLength);
    String body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);

    return new RconPacket(id, type, body);
  }

  private static void writeIntLittleEndian(byte[] buffer, int offset, int value) {
    buffer[offset] = (byte) (value & 0xFF);
    buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
    buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
    buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
  }

  private static int readIntLittleEndian(byte[] buffer, int offset) {
    return (buffer[offset] & 0xFF)
        | ((buffer[offset + 1] & 0xFF) << 8)
        | ((buffer[offset + 2] & 0xFF) << 16)
        | ((buffer[offset + 3] & 0xFF) << 24);
  }

  @Override
  public String toString() {
    return "RconPacket{id=" + id + ", type=" + type + ", body='" + body + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    RconPacket that = (RconPacket) o;
    return id == that.id && type == that.type && body.equals(that.body);
  }

  @Override
  public int hashCode() {
    return 31 * (31 * id + type) + body.hashCode();
  }
}
