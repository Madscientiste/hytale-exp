package com.madscientiste.rcon.transport;

import com.madscientiste.rcon.infrastructure.AddressRange;
import com.madscientiste.rcon.infrastructure.AuthFailureTracker;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.LogThrottle;
import com.madscientiste.rcon.logging.RconLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a single client connection. Handles socket IO, timeouts, and connection lifecycle.
 *
 * <p>Two deadlines apply, and neither can be pushed back by trickling bytes:
 *
 * <ul>
 *   <li>Before login: the connection is closed {@code authTimeoutMs} after it was accepted.
 *   <li>After login: the connection is closed when no complete packet was exchanged for {@code
 *       readTimeoutMs}.
 * </ul>
 *
 * <p>Before login a packet may not be larger than the default frame size, whatever {@code
 * maxFrameSize} says: a login never needs more, and a peer nobody has vouched for gets no large
 * buffer to fill.
 */
public class RconConnection {

  private static final int HEADER_SIZE = 4;
  private static final int READ_CHUNK_SIZE = 4096;

  private final String id;
  private final Socket socket;
  private final TransportCallbacks callbacks;
  private final RconTransport transport;
  private final InputStream input;
  private final OutputStream output;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Thread readThread;
  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_TRANSPORT);

  private final String remoteIp;
  private final String addressKey;
  private final boolean loopback;
  private final LogThrottle preAuthLogs;

  private final int authTimeoutMs;
  private final int readTimeoutMs;

  /** Largest value of a packet's size field (the packet without its 4-byte size prefix). */
  private final int maxPacketSize;

  /** The same limit for a peer that has not logged in yet. */
  private final int maxPacketSizeBeforeLogin;

  private final long connectedAt = System.currentTimeMillis();
  private volatile long lastActivity = connectedAt;
  private volatile boolean authenticated = false;

  // Bytes received and not yet handled: at most one incomplete packet after each pass.
  // Only the read thread touches it.
  private byte[] readBuffer = new byte[2 * READ_CHUNK_SIZE];
  private int buffered = 0;

  public RconConnection(
      String id,
      Socket socket,
      TransportCallbacks callbacks,
      RconTransport transport,
      RconConfig config,
      LogThrottle preAuthLogs)
      throws IOException {
    this.id = id;
    this.socket = socket;
    this.callbacks = callbacks;
    this.transport = transport;
    this.preAuthLogs = preAuthLogs;
    this.input = socket.getInputStream();
    this.output = socket.getOutputStream();

    InetAddress address = socket.getInetAddress();
    this.remoteIp = address.getHostAddress();
    this.addressKey = AuthFailureTracker.keyFor(address);
    this.loopback = AddressRange.isLoopback(address);

    this.authTimeoutMs = config.getAuthTimeoutMs();
    this.readTimeoutMs = config.getReadTimeoutMs();
    this.maxPacketSize = config.getMaxFrameSize() - HEADER_SIZE;
    this.maxPacketSizeBeforeLogin =
        Math.min(maxPacketSize, RconConstants.DEFAULT_MAX_FRAME_SIZE - HEADER_SIZE);

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
   * buffering partial packets. However this loop ends, the connection is closed: its slot and its
   * session state are always given back.
   */
  private void readLoop() {
    try {
      while (!closed.get() && !Thread.currentThread().isInterrupted()) {
        long remaining = deadline() - System.currentTimeMillis();
        if (remaining <= 0) {
          close(authenticated ? "Idle timeout" : "Login timeout");
          return;
        }

        if (!makeRoomToRead()) {
          close("Read buffer overflow");
          return;
        }

        int bytesRead;
        try {
          // Wait no longer than the deadline, however slowly bytes arrive
          socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
          bytesRead = input.read(readBuffer, buffered, readBuffer.length - buffered);
        } catch (SocketTimeoutException e) {
          continue; // The deadline check above closes the connection
        }

        if (bytesRead == -1) {
          close("Client disconnected");
          return;
        }

        buffered += bytesRead;
        processCompletePackets();
      }

    } catch (IOException e) {
      // The peer went away or the socket was closed under us. Routine, and anyone can cause it,
      // so it is only the reason on the disconnect line: no error entry, no stack trace.
      close("Read error: " + e.getMessage());
    } catch (Throwable t) {
      // A fault in our own code or in a command, Errors included (a server API mismatch shows
      // up as NoSuchMethodError or AbstractMethodError). Say so once, then close like any other.
      if (!closed.get()) {
        logger
            .event(LogEvent.TRANSPORT_ERROR)
            .withParam("error_type", "internal_error")
            .withParam("message", "Connection handler failed: " + t)
            .withOptionalParam("connection_id", id)
            .withOptionalParam("remote_ip", remoteIp)
            .withCause(t)
            .throttledBy(preAuthLogThrottle(), addressKey)
            .log();
      }
      close("Internal error");
    } finally {
      // Does nothing if a close above already ran
      close("Connection handler ended");
    }
  }

  /** When this connection must be closed if nothing more happens. */
  private long deadline() {
    return authenticated ? lastActivity + readTimeoutMs : connectedAt + authTimeoutMs;
  }

  /** Largest packet (value of the size field) this peer may send right now. */
  private int packetSizeLimit() {
    return authenticated ? maxPacketSize : maxPacketSizeBeforeLogin;
  }

  /**
   * Make sure at least one more byte fits in the read buffer, growing it if needed. Returns false
   * if the buffer already holds more than one full packet could need, which cannot happen as long
   * as every packet size is checked on arrival.
   */
  private boolean makeRoomToRead() {
    if (buffered < readBuffer.length) {
      return true;
    }
    int limit = HEADER_SIZE + packetSizeLimit() + READ_CHUNK_SIZE;
    if (readBuffer.length >= limit) {
      return false;
    }
    readBuffer = Arrays.copyOf(readBuffer, (int) Math.min(limit, 2L * readBuffer.length));
    return true;
  }

  /**
   * Process complete packets from the read buffer. Extracts length-prefixed packets and passes them
   * to callbacks. Stops as soon as the connection is closed: packets still in the buffer after a
   * failed login or a protocol error are never handled.
   */
  private void processCompletePackets() {
    int offset = 0;

    while (!closed.get() && buffered - offset >= HEADER_SIZE) {
      // Read packet size (little-endian)
      int packetSize = readIntLittleEndian(readBuffer, offset);

      // Validate packet size before waiting for the rest of the packet
      if (packetSize < 10 || packetSize > packetSizeLimit()) {
        close("Invalid packet size: " + packetSize);
        return;
      }

      int totalPacketSize = HEADER_SIZE + packetSize;

      // Check if we have complete packet
      if (buffered - offset < totalPacketSize) {
        break; // Not enough data for complete packet
      }

      byte[] packetData = Arrays.copyOfRange(readBuffer, offset, offset + totalPacketSize);
      offset += totalPacketSize;

      updateActivity();
      callbacks.onBytesReceived(id, packetData);
    }

    // Keep what was not consumed (an incomplete packet) at the front for the next read
    if (offset > 0) {
      buffered -= offset;
      System.arraycopy(readBuffer, offset, readBuffer, 0, buffered);
    }
  }

  /** Read little-endian integer from buffer. */
  private int readIntLittleEndian(byte[] buffer, int offset) {
    return (buffer[offset] & 0xFF)
        | ((buffer[offset + 1] & 0xFF) << 8)
        | ((buffer[offset + 2] & 0xFF) << 16)
        | ((buffer[offset + 3] & 0xFF) << 24);
  }

  /**
   * Send bytes to the client.
   *
   * @throws IOException if the connection is closed or the peer is gone. That is an ordinary way
   *     for a connection to end, not a fault.
   */
  public void send(byte[] data) throws IOException {
    if (closed.get()) {
      throw new IOException("Connection is closed");
    }

    // One frame at a time on the wire
    synchronized (output) {
      output.write(data);
      output.flush();
      updateActivity();
    }
  }

  /** Close the connection and clean up resources. */
  public void close(String reason) {
    if (closed.compareAndSet(false, true)) {
      try {
        // Closing the socket below already unblocks a read; the interrupt is for a read thread
        // busy elsewhere. Never interrupt ourselves: the flag would break the logging that follows.
        if (readThread != null && readThread != Thread.currentThread()) {
          readThread.interrupt();
        }

        if (socket != null && !socket.isClosed()) {
          socket.close();
        }
      } catch (Exception e) {
        // Log error but don't re-throw
        logger
            .event(LogEvent.TRANSPORT_ERROR)
            .withParam("error_type", "close_failure")
            .withParam("message", "Error closing connection: " + e.getMessage())
            .withOptionalParam("connection_id", id)
            .withOptionalParam("remote_ip", remoteIp)
            .throttledBy(preAuthLogThrottle(), addressKey)
            .log();
      } finally {
        // Always release the slot and the session state, even if the socket close failed
        if (transport != null) {
          transport.onConnectionClosed(this, reason);
        }
      }
    }
  }

  /** Mark the connection as logged in: the idle timeout replaces the login deadline. */
  public void markAuthenticated() {
    lastActivity = System.currentTimeMillis();
    authenticated = true;
  }

  public boolean isAuthenticated() {
    return authenticated;
  }

  /**
   * The log budget to charge: the per-address one until the peer has logged in, none after. Lines
   * caused by someone who knows the password are always written.
   */
  LogThrottle preAuthLogThrottle() {
    return authenticated ? null : preAuthLogs;
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

  /** Remote address as text, for logs. */
  public String getRemoteIp() {
    return remoteIp;
  }

  /** Key the per-address limits are counted under (see AuthFailureTracker.keyFor). */
  public String getAddressKey() {
    return addressKey;
  }

  /** True if the peer is on this machine (127.0.0.0/8 or ::1). */
  public boolean isLoopback() {
    return loopback;
  }

  public long getConnectedAt() {
    return connectedAt;
  }

  /** Get last activity time. */
  public long getLastActivity() {
    return lastActivity;
  }
}
