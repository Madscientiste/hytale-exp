package com.rcon.transport;

/**
 * Callback interface for transport layer events. Implemented by application layer to receive bytes
 * and connection events.
 */
public interface TransportCallbacks {

  /**
   * Called when bytes are received from a connection.
   *
   * @param connectionId Unique identifier for the connection
   * @param data Raw bytes received
   */
  void onBytesReceived(String connectionId, byte[] data);

  /**
   * Called when a connection is closed.
   *
   * @param connectionId Unique identifier for the connection
   * @param reason Reason for closure
   */
  void onConnectionClosed(String connectionId, String reason);
}
