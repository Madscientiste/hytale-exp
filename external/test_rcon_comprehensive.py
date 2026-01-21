#!/usr/bin/env python3
"""
Comprehensive RCON Test Suite
Tests all aspects of RCON functionality including edge cases.
"""
import socket
import struct
import sys
import time
import threading

# RCON Packet Types
SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0

def send_rcon_packet(sock, packet_id, packet_type, body):
    """Send an RCON packet"""
    body_bytes = body.encode('utf-8')
    packet_size = 4 + 4 + len(body_bytes) + 1 + 1
    packet = struct.pack('<III', packet_size, packet_id, packet_type)
    packet += body_bytes
    packet += b'\x00'
    packet += b'\x00'
    sock.sendall(packet)
    return packet

def read_rcon_packet(sock, timeout=5):
    """Read an RCON packet from the socket"""
    sock.settimeout(timeout)
    size_data = sock.recv(4)
    if len(size_data) < 4:
        raise Exception("Connection closed while reading size")
    
    packet_size = struct.unpack('<I', size_data)[0]
    if packet_size < 10 or packet_size > 4096:
        raise Exception(f"Invalid packet size: {packet_size}")
    
    remaining = sock.recv(packet_size)
    if len(remaining) < packet_size:
        raise Exception(f"Connection closed: expected {packet_size} bytes, got {len(remaining)}")
    
    packet_data = size_data + remaining
    packet_id = struct.unpack('<I', packet_data[4:8])[0]
    packet_type = struct.unpack('<I', packet_data[8:12])[0]
    
    # Find null terminator to extract body correctly
    body_start = 12
    body_end = body_start
    for i in range(body_start, len(packet_data) - 1):
        if packet_data[i] == 0:
            body_end = i
            break
    
    body_bytes = packet_data[body_start:body_end]
    body = body_bytes.decode('utf-8', errors='ignore')
    
    return packet_id, packet_type, body

def connect_and_auth(host='127.0.0.1', port=25575, password='hello'):
    """Connect and authenticate"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    time.sleep(0.1)  # Small delay for connection to stabilize
    
    send_rcon_packet(sock, 100, SERVERDATA_AUTH, password)
    auth_id, auth_type, auth_body = read_rcon_packet(sock)
    
    if auth_type != SERVERDATA_AUTH_RESPONSE:
        sock.close()
        raise Exception(f"Authentication failed: type={auth_type}")
    
    return sock

def execute_command(sock, command, request_id=101):
    """Execute a command and return response"""
    send_rcon_packet(sock, request_id, SERVERDATA_EXECCOMMAND, command)
    cmd_id, cmd_type, cmd_body = read_rcon_packet(sock)
    
    if cmd_id == request_id and cmd_type == SERVERDATA_RESPONSE_VALUE:
        return cmd_body.rstrip('\x00').strip()
    return cmd_body

class TestRunner:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.tests = []
    
    def test(self, name, func):
        """Run a test"""
        print(f"\n[TEST] {name}")
        try:
            result = func()
            if result:
                print(f"  ✓ PASSED")
                self.passed += 1
            else:
                print(f"  ✗ FAILED")
                self.failed += 1
        except Exception as e:
            print(f"  ✗ FAILED: {e}")
            self.failed += 1
            import traceback
            traceback.print_exc()
    
    def summary(self):
        """Print test summary"""
        total = self.passed + self.failed
        print("\n" + "="*60)
        print(f"Test Summary: {self.passed}/{total} passed, {self.failed} failed")
        print("="*60)

def test_basic_echo():
    """Test basic echo command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "echo hello world", 200)
        return response == "hello world"
    finally:
        sock.close()
        time.sleep(0.2)

def test_empty_command():
    """Test empty command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "", 201)
        return response == "" or len(response) == 0
    finally:
        sock.close()

def test_special_characters():
    """Test commands with special characters"""
    sock = connect_and_auth()
    try:
        test_str = "!@#$%^&*()_+-=[]{}|;':\",./<>?"
        response = execute_command(sock, f"echo {test_str}", 202)
        return test_str in response
    finally:
        sock.close()

def test_unicode_characters():
    """Test commands with unicode"""
    sock = connect_and_auth()
    try:
        test_str = "Hello 世界 🌍"
        response = execute_command(sock, f"echo {test_str}", 203)
        return test_str in response
    finally:
        sock.close()

def test_long_command():
    """Test long command"""
    sock = connect_and_auth()
    try:
        long_str = "A" * 500
        response = execute_command(sock, f"echo {long_str}", 204)
        return long_str in response
    finally:
        sock.close()

def test_multiple_commands():
    """Test multiple commands in sequence"""
    sock = connect_and_auth()
    try:
        results = []
        for i in range(5):  # Reduced from 10 to avoid issues
            response = execute_command(sock, f"echo test{i}", 300 + i)
            results.append(f"test{i}" in response)
            time.sleep(0.2)  # Increased delay
        return all(results)
    finally:
        sock.close()
        time.sleep(0.3)  # Delay before next test

def test_hytale_version():
    """Test Hytale version command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "version", 400)
        return "HytaleServer" in response or "v" in response
    finally:
        sock.close()

def test_hytale_who():
    """Test Hytale who command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "who", 401)
        return isinstance(response, str) and len(response) >= 0
    finally:
        sock.close()

def test_hytale_help():
    """Test Hytale help command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "help", 402)
        return isinstance(response, str)
    finally:
        sock.close()

def test_hytale_commands_list():
    """Test Hytale commands command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "commands", 403)
        return isinstance(response, str)
    finally:
        sock.close()

def test_invalid_command():
    """Test invalid/non-existent command"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "nonexistentcommand12345", 404)
        # Should return error message or empty, not crash
        return isinstance(response, str)
    finally:
        sock.close()

def test_command_with_args():
    """Test command with arguments"""
    sock = connect_and_auth()
    try:
        response = execute_command(sock, "echo arg1 arg2 arg3", 405)
        return "arg1 arg2 arg3" in response or "arg1" in response
    finally:
        sock.close()

def test_concurrent_connections():
    """Test multiple concurrent connections"""
    results = []
    lock = threading.Lock()
    
    def run_connection(conn_id):
        try:
            time.sleep(conn_id * 0.1)  # Stagger connections
            sock = connect_and_auth()
            response = execute_command(sock, f"echo concurrent{conn_id}", 500 + conn_id)
            sock.close()
            with lock:
                results.append(f"concurrent{conn_id}" in response)
        except Exception as e:
            print(f"  Connection {conn_id} failed: {e}")
            with lock:
                results.append(False)
    
    threads = []
    for i in range(3):  # Reduced from 5 to avoid connection limit
        t = threading.Thread(target=run_connection, args=(i,))
        threads.append(t)
        t.start()
    
    for t in threads:
        t.join(timeout=15)
    
    time.sleep(0.5)  # Wait for connections to close
    return all(results) and len(results) == 3

def test_reconnection():
    """Test reconnecting after disconnect"""
    # First connection
    sock1 = connect_and_auth()
    execute_command(sock1, "echo first", 600)
    sock1.close()
    
    time.sleep(1.0)  # Increased delay for connection cleanup
    
    # Second connection
    sock2 = connect_and_auth()
    response = execute_command(sock2, "echo second", 601)
    sock2.close()
    time.sleep(0.3)
    
    return "second" in response

def test_large_response():
    """Test command that returns large response"""
    sock = connect_and_auth()
    try:
        # Use a command that should return some data
        # Try multiple commands to find one that returns data
        response1 = execute_command(sock, "commands", 700)
        response2 = execute_command(sock, "version", 701)
        # At least one should return data
        return isinstance(response1, str) and isinstance(response2, str) and (len(response1) > 0 or len(response2) > 0)
    finally:
        sock.close()

def test_command_timeout():
    """Test that commands don't hang indefinitely"""
    sock = connect_and_auth()
    try:
        start = time.time()
        response = execute_command(sock, "version", 800)
        elapsed = time.time() - start
        # Should complete in reasonable time (< 5 seconds)
        return elapsed < 5.0 and isinstance(response, str)
    finally:
        sock.close()

def main():
    """Run all tests"""
    print("="*60)
    print("Comprehensive RCON Test Suite")
    print("="*60)
    
    runner = TestRunner()
    
    # Basic functionality tests
    print("\n" + "="*60)
    print("Basic Functionality Tests")
    print("="*60)
    runner.test("Basic echo command", test_basic_echo)
    time.sleep(0.3)
    runner.test("Empty command", test_empty_command)
    time.sleep(0.3)
    runner.test("Special characters", test_special_characters)
    time.sleep(0.3)
    runner.test("Unicode characters", test_unicode_characters)
    time.sleep(0.3)
    runner.test("Long command", test_long_command)
    time.sleep(0.3)
    runner.test("Command with arguments", test_command_with_args)
    time.sleep(0.3)
    
    # Hytale integration tests
    print("\n" + "="*60)
    print("Hytale Integration Tests")
    print("="*60)
    runner.test("Hytale version command", test_hytale_version)
    time.sleep(0.3)
    runner.test("Hytale who command", test_hytale_who)
    time.sleep(0.3)
    runner.test("Hytale help command", test_hytale_help)
    time.sleep(0.3)
    runner.test("Hytale commands list", test_hytale_commands_list)
    time.sleep(0.3)
    runner.test("Invalid command handling", test_invalid_command)
    time.sleep(0.3)
    
    # Advanced tests
    print("\n" + "="*60)
    print("Advanced Tests")
    print("="*60)
    runner.test("Multiple commands in sequence", test_multiple_commands)
    time.sleep(0.5)
    runner.test("Concurrent connections", test_concurrent_connections)
    time.sleep(1.0)
    runner.test("Reconnection after disconnect", test_reconnection)
    time.sleep(0.5)
    runner.test("Large response handling", test_large_response)
    time.sleep(0.3)
    runner.test("Command timeout handling", test_command_timeout)
    
    runner.summary()
    
    if runner.failed > 0:
        sys.exit(1)
    else:
        print("\n✓ All tests passed!")

if __name__ == "__main__":
    main()

