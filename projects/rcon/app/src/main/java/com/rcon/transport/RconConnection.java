package com.rcon.transport;

import com.rcon.infrastructure.RconLogger;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a single client connection.
 * Handles socket IO, timeouts, and connection lifecycle.
 */
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

    public RconConnection(String id, Socket socket, TransportCallbacks callbacks, RconTransport transport)
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

    /**
     * Start connection's read thread.
     */
    public void start() {
        readThread.start();
    }

    /**
     * Main read loop - reads bytes from socket and calls callbacks.
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

                    // Copy to bytes we actually read
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);

                    callbacks.onBytesReceived(id, data);
                }
            }

        } catch (Exception e) {
            if (!closed.get()) {
                close("Read error: " + e.getMessage());
            }
        }
    }

    /**
     * Send bytes to the client.
     * Limits in-flight frames to prevent slow-burn DoS.
     */
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

    /**
     * Close the connection and clean up resources.
     */
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

    /**
     * Update last activity timestamp.
     */
    private void updateActivity() {
        lastActivity = System.currentTimeMillis();
    }

    /**
     * Check if connection is closed.
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Get connection ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Get last activity time.
     */
    public long getLastActivity() {
        return lastActivity;
    }
}