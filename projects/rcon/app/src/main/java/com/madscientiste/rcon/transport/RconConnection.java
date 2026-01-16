package com.madscientiste.rcon.transport;

import com.madscientiste.rcon.protocol.RconProtocol;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Represents a single client connection. Handles socket IO, timeouts, and connection lifecycle. */
public class RconConnection {

  private final String id;
  private final Socket socket;
  private final TransportCallbacks callbacks;
  private final RconTransport transport;
  private final InputStream input;
  private final OutputStream output;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Semaphore inFlightFrames = new Semaphore(1);
  private final Thread readThread;

  private volatile long lastActivity = System.currentTimeMillis();

  // Buffer for accumulating partial packets (TCP fragmentation handling)
  private final java.io.ByteArrayOutputStream readBuffer = new java.io.ByteArrayOutputStream();
  private static final int HEADER_SIZE = 4;
  private static final int MAX_BUFFER_SIZE = RconProtocol.MAX_FRAME_SIZE * 2; // Allow some headroom

  public RconConnection(
      String id, Socket socket, TransportCallbacks callbacks, RconTransport transport)
      throws Exception {
    this.id = id;
    this.socket = socket;
    this.callbacks = callbacks;
    this.transport = transport;
    this.input = socket.getInputStream();
    this.output = socket.getOutputStream();

    // Start read thread for this connection
    this.readThread = new Thread(this::readLoop, "RconRead-" + id);
    this.readThread.setDaemon(true);
  }

  /** Start connection's read thread. */
  public void start() {
    readThread.start();
  }

  /**
   * Main read loop - reads bytes from socket and calls callbacks. Handles TCP fragmentation by
   * buffering partial packets.
   */
  private void readLoop() {
    try {
      byte[] buffer = new byte[4096];

      while (!closed.get() && !Thread.currentThread().isInterrupted()) {
        int bytesRead = input.read(buffer);

        if (bytesRead == -1) {
          close("Client disconnected");
          return;
        }

        if (bytesRead > 0) {
          updateActivity();

          // Add to read buffer
          synchronized (readBuffer) {
            // Prevent buffer from growing unbounded
            if (readBuffer.size() + bytesRead > MAX_BUFFER_SIZE) {
              close("Read buffer overflow");
              return;
            }

            readBuffer.write(buffer, 0, bytesRead);

            // Process complete packets from buffer
            processCompletePackets();
          }
        }
      }

    } catch (Exception e) {
      if (!closed.get()) {
        close("Read error: " + e.getMessage());
      }
    }
  }

  /**
   * Process complete packets from the read buffer. Extracts length-prefixed packets and passes them
   * to callbacks.
   */
  private void processCompletePackets() {
    byte[] bufferData = readBuffer.toByteArray();
    int offset = 0;

    while (offset < bufferData.length) {
      // Need at least HEADER_SIZE to read packet length
      if (bufferData.length - offset < HEADER_SIZE) {
        break; // Not enough data for header
      }

      // Read packet size (little-endian)
      int packetSize = readIntLittleEndian(bufferData, offset);

      // Validate packet size
      if (packetSize < 10 || packetSize > RconProtocol.MAX_FRAME_SIZE) {
        close("Invalid packet size: " + packetSize);
        return;
      }

      // Check for integer overflow
      if (offset > Integer.MAX_VALUE - HEADER_SIZE - packetSize) {
        close("Packet size overflow");
        return;
      }

      int totalPacketSize = HEADER_SIZE + packetSize;

      // Check if we have complete packet
      if (bufferData.length - offset < totalPacketSize) {
        break; // Not enough data for complete packet
      }

      // Extract complete packet
      byte[] packetData = new byte[totalPacketSize];
      System.arraycopy(bufferData, offset, packetData, 0, totalPacketSize);

      // Remove processed data from buffer
      byte[] remaining = new byte[bufferData.length - offset - totalPacketSize];
      if (remaining.length > 0) {
        System.arraycopy(bufferData, offset + totalPacketSize, remaining, 0, remaining.length);
      }
      readBuffer.reset();
      if (remaining.length > 0) {
        readBuffer.write(remaining, 0, remaining.length);
      }

      // Process packet
      callbacks.onBytesReceived(id, packetData);

      // Update offset for next iteration
      offset += totalPacketSize;

      // Reset offset since we've consumed the data
      bufferData = readBuffer.toByteArray();
      offset = 0;
    }
  }

  /** Read little-endian integer from buffer. */
  private int readIntLittleEndian(byte[] buffer, int offset) {
    return (buffer[offset] & 0xFF)
        | ((buffer[offset + 1] & 0xFF) << 8)
        | ((buffer[offset + 2] & 0xFF) << 16)
        | ((buffer[offset + 3] & 0xFF) << 24);
  }

  /** Send bytes to the client. Limits in-flight frames to prevent slow-burn DoS. */
  public void send(byte[] data) throws Exception {
    if (closed.get()) {
      throw new Exception("Connection is closed");
    }

    // Limit in-flight frames to prevent slow-burn DoS
    inFlightFrames.acquire();

    try {
      synchronized (output) {
        output.write(data);
        output.flush();
        updateActivity();
      }
    } finally {
      inFlightFrames.release();
    }
  }

  /** Close the connection and clean up resources. */
  public void close(String reason) {
    if (closed.compareAndSet(false, true)) {
      try {
        if (readThread != null) {
          readThread.interrupt();
        }

        if (socket != null && !socket.isClosed()) {
          socket.close();
        }

        // Remove from transport's connection map and notify callbacks
        if (transport != null) {
          transport.onConnectionClosed(id, reason);
        }
      } catch (Exception e) {
        // Log error but don't re-throw
        System.err.println("Error closing connection " + id + ": " + e.getMessage());
      }
    }
  }

  /** Update last activity timestamp. */
  private void updateActivity() {
    lastActivity = System.currentTimeMillis();
  }

  /** Check if connection is closed. */
  public boolean isClosed() {
    return closed.get();
  }

  /** Get connection ID. */
  public String getId() {
    return id;
  }

  /** Get last activity time. */
  public long getLastActivity() {
    return lastActivity;
  }
}
