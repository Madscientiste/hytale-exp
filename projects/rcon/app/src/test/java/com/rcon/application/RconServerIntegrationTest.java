package com.rcon.application;

import com.rcon.RconServer;
import com.rcon.infrastructure.RconConfig;
import com.rcon.infrastructure.RconLogger;
import com.rcon.protocol.RconPacket;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.Socket;

/**
 * Integration tests for the full RCON server.
 * Tests the complete data flow: Bytes → Transport → Protocol → Application → Response.
 */
public class RconServerIntegrationTest {
    
    private RconConfig config;
    private RconServer server;
    private RconLogger logger;
    
    public void setUp() throws Exception {
        config = new RconConfig("127.0.0.1", 25576, 10, 4096, 5000, 5000); // Fixed port for tests
        server = new RconServer(config);
        logger = new RconLogger();
        
        server.start();
    }
    
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    public void testBasicConnectionLifecycle() throws Exception {
        setUp();
        
        // Get actual server port (since we used 0 for auto-assign)
        int port = getServerPort();
        
        // Test connection
        Socket client = new Socket("127.0.0.1", port);
        assertTrue("Client should connect", client.isConnected());
        
        // Test disconnect
        client.close();
        Thread.sleep(100); // Give server time to process
        
        // Verify server is still running
        assertTrue("Server should still be running", server.isRunning());
        
        tearDown();
    }
    
    @Test
    public void testEchoFlow() throws Exception {
        setUp();
        
        int port = getServerPort();
        Socket client = new Socket("127.0.0.1", port);
        
        try {
            // Send auth packet
            RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
            client.getOutputStream().write(authPacket.toBytes());
            
            // Read auth response
            byte[] authResponse = readPacket(client);
            RconPacket parsedAuth = RconPacket.fromBytes(authResponse);
            assertEquals("Auth response ID should match", 100, parsedAuth.getId());
            assertEquals("Auth response type", RconPacket.SERVERDATA_AUTH_RESPONSE, parsedAuth.getType());
            
            // Send command packet
            RconPacket commandPacket = new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo hello world");
            client.getOutputStream().write(commandPacket.toBytes());
            
            // Read command response
            byte[] commandResponse = readPacket(client);
            RconPacket parsedCommand = RconPacket.fromBytes(commandResponse);
            assertEquals("Command response ID should match", 101, parsedCommand.getId());
            assertEquals("Command response type", RconPacket.SERVERDATA_RESPONSE_VALUE, parsedCommand.getType());
            assertEquals("Command should echo", "hello world", parsedCommand.getBody());
            
        } finally {
            client.close();
            tearDown();
        }
    }
    
    @Test
    public void testMultipleCommandsOnSameConnection() throws Exception {
        setUp();
        
        int port = getServerPort();
        Socket client = new Socket("127.0.0.1", port);
        
        try {
            // Authenticate once
            RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
            client.getOutputStream().write(authPacket.toBytes());
            readPacket(client); // Consume auth response
            
            // Send multiple commands
            String[] commands = {"first", "second", "third"};
            int requestId = 200;
            
            for (String command : commands) {
                RconPacket commandPacket = new RconPacket(requestId, RconPacket.SERVERDATA_EXECCOMMAND, "echo " + command);
                client.getOutputStream().write(commandPacket.toBytes());
                
                byte[] response = readPacket(client);
                RconPacket parsed = RconPacket.fromBytes(response);
                assertEquals("Response should match request", requestId, parsed.getId());
                assertEquals("Response should echo command", command, parsed.getBody());
                
                requestId++;
            }
            
        } finally {
            client.close();
            tearDown();
        }
    }
    
    @Test
    public void testServerShutdownWithActiveConnections() throws Exception {
        setUp();
        
        int port = getServerPort();
        Socket client1 = new Socket("127.0.0.1", port);
        Socket client2 = new Socket("127.0.0.1", port);
        
        // Give both connections time to establish
        Thread.sleep(100);
        
        try {
            assertTrue("Both clients should connect", 
                       client1.isConnected() && client2.isConnected());
            
            // Verify server stats
            RconApplication.ApplicationStats stats = server.getStats();
            assertEquals("Should have 2 connections", 2, stats.connectionCount);
            
        } finally {
            // Shutdown with active clients
            server.stop();
            
            // Verify clients are disconnected
            assertFalse("Server should not be running", server.isRunning());
            
            client1.close();
            client2.close();
        }
    }
    
    /**
     * Read a single packet from the socket.
     */
    private byte[] readPacket(Socket socket) throws IOException {
        // Read size field (4 bytes, little endian)
        byte[] sizeBytes = new byte[4];
        int bytesRead = 0;
        while (bytesRead < 4) {
            int read = socket.getInputStream().read(sizeBytes, bytesRead, 4 - bytesRead);
            if (read == -1) throw new IOException("Connection closed");
            bytesRead += read;
        }
        
        int size = (sizeBytes[0] & 0xFF) |
                  ((sizeBytes[1] & 0xFF) << 8) |
                  ((sizeBytes[2] & 0xFF) << 16) |
                  ((sizeBytes[3] & 0xFF) << 24);
        
        // Read the rest of the packet
        byte[] packetData = new byte[4 + size];
        System.arraycopy(sizeBytes, 0, packetData, 0, 4);
        
        bytesRead = 0;
        while (bytesRead < size) {
            int read = socket.getInputStream().read(packetData, 4 + bytesRead, size - bytesRead);
            if (read == -1) throw new IOException("Connection closed");
            bytesRead += read;
        }
        
        return packetData;
    }
    
    /**
     * Get the configured server port.
     */
    private int getServerPort() {
        return config.getPort();
    }
}