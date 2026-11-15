package com.madscientiste.rcon.transport;

import java.net.InetAddress;

/**
 * Callback interface for transport layer events. This is the ONLY interface the transport layer
 * exposes to upper layers.
 */
public interface TransportCallbacks {

  /**
   * Called once when a connection is accepted, before any of its bytes are delivered. Per
   * connection state is created here and only here.
   *
   * @param connectionId The unique connection identifier
   * @param remoteAddress Address of the peer
   */
  void onConnectionOpened(String connectionId, InetAddress remoteAddress);

  /**
   * Called when bytes are received from a connection.
   *
   * @param connectionId The unique connection identifier
   * @param data The raw bytes received
   */
  void onBytesReceived(String connectionId, byte[] data);

  /**
   * Called when a connection is closed (gracefully or due to error).
   *
   * @param connectionId The unique connection identifier
   * @param reason The reason for closure
   */
  void onConnectionClosed(String connectionId, String reason);
}
