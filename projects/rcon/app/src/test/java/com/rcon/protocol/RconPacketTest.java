package com.rcon.protocol;

import com.rcon.infrastructure.RconLogger;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for RconPacket serialization/deserialization.
 * 100% unit tested - no mocks needed.
 */
public class RconPacketTest {
    
    @Test
    public void testBasicSerialization() throws Exception {
        RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test body");
        byte[] data = packet.toBytes();
        
        RconPacket deserialized = RconPacket.fromBytes(data);
        
        assertEquals(123, deserialized.getId());
        assertEquals(RconPacket.SERVERDATA_AUTH, deserialized.getType());
        assertEquals("test body", deserialized.getBody());
    }
    
    @Test
    public void testEmptyBody() throws Exception {
        RconPacket packet = new RconPacket(456, RconPacket.SERVERDATA_RESPONSE_VALUE, "");
        byte[] data = packet.toBytes();
        
        RconPacket deserialized = RconPacket.fromBytes(data);
        
        assertEquals(456, deserialized.getId());
        assertEquals(RconPacket.SERVERDATA_RESPONSE_VALUE, deserialized.getType());
        assertEquals("", deserialized.getBody());
    }
    
    @Test
    public void testNullBody() throws Exception {
        RconPacket packet = new RconPacket(789, RconPacket.SERVERDATA_EXECCOMMAND, null);
        byte[] data = packet.toBytes();
        
        RconPacket deserialized = RconPacket.fromBytes(data);
        
        assertEquals(789, deserialized.getId());
        assertEquals(RconPacket.SERVERDATA_EXECCOMMAND, deserialized.getType());
        assertEquals("", deserialized.getBody());
    }
    
    @Test
    public void testComplexBody() throws Exception {
        String complexBody = "Hello World!\nWith newlines and \"quotes\" and 'apostrophes'";
        RconPacket packet = new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, complexBody);
        byte[] data = packet.toBytes();
        
        RconPacket deserialized = RconPacket.fromBytes(data);
        
        assertEquals(100, deserialized.getId());
        assertEquals(RconPacket.SERVERDATA_EXECCOMMAND, deserialized.getType());
        assertEquals(complexBody, deserialized.getBody());
    }
    
    @Test(expected = ProtocolException.class)
    public void testTooShortPacket() throws Exception {
        byte[] tooShort = {1, 2, 3}; // Less than minimum 14 bytes
        RconPacket.fromBytes(tooShort);
    }
    
    @Test(expected = ProtocolException.class)
    public void testSizeMismatch() throws Exception {
        // Create valid packet then corrupt the size
        RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
        byte[] data = packet.toBytes();
        
        // Corrupt the size field to something different
        data[0] = 99; // Change first byte of size
        
        RconPacket.fromBytes(data);
    }
    
    @Test
    public void testPacketEqualsAndHashCode() {
        RconPacket packet1 = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
        RconPacket packet2 = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
        RconPacket packet3 = new RconPacket(124, RconPacket.SERVERDATA_AUTH, "test");
        
        assertEquals(packet1, packet2);
        assertEquals(packet1.hashCode(), packet2.hashCode());
        assertNotEquals(packet1, packet3);
    }
    
    @Test
    public void testPacketToString() {
        RconPacket packet = new RconPacket(123, RconPacket.SERVERDATA_AUTH, "test");
        String str = packet.toString();
        
        assertTrue(str.contains("id=123"));
        assertTrue(str.contains("type=3"));
        assertTrue(str.contains("body='test'"));
    }
}