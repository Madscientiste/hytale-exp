#!/usr/bin/env python3
"""
RCON Test Client for Hytale Server
Tests RCON protocol and command execution capabilities.
"""
import socket
import struct
import sys
import time

# RCON Packet Types
SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0

def send_rcon_packet(sock, packet_id, packet_type, body):
    """Send an RCON packet (little-endian format) per Source RCON standard"""
    body_bytes = body.encode('utf-8')
    # RCON standard: packet_size = id + type + body + body_null + padding_null
    packet_size = 4 + 4 + len(body_bytes) + 1 + 1
    
    # Build packet: size (4 bytes) + id (4 bytes) + type (4 bytes) + body + body_null + padding_null
    packet = struct.pack('<III', packet_size, packet_id, packet_type)
    packet += body_bytes
    packet += b'\x00'  # Body null terminator
    packet += b'\x00'  # Empty string padding
    
    sock.sendall(packet)
    return packet

def read_rcon_packet(sock, timeout=5):
    """Read an RCON packet from the socket"""
    sock.settimeout(timeout)
    
    # Read size (4 bytes, little-endian)
    size_data = sock.recv(4)
    if len(size_data) < 4:
        raise Exception("Connection closed while reading size")
    
    packet_size = struct.unpack('<I', size_data)[0]
    
    if packet_size < 10 or packet_size > 4096:
        raise Exception(f"Invalid packet size: {packet_size}")
    
    # Read the rest of the packet
    remaining = sock.recv(packet_size)
    if len(remaining) < packet_size:
        raise Exception(f"Connection closed: expected {packet_size} bytes, got {len(remaining)}")
    
    packet_data = size_data + remaining
    
    # Parse packet
    packet_id = struct.unpack('<I', packet_data[4:8])[0]
    packet_type = struct.unpack('<I', packet_data[8:12])[0]
    
    # Extract body (up to null terminator)
    body_bytes = packet_data[12:-1]
    body = body_bytes.decode('utf-8', errors='ignore')
    
    return packet_id, packet_type, body

def connect_rcon(host='127.0.0.1', port=25575):
    """Connect and authenticate to RCON server"""
    print(f"Connecting to {host}:{port}...")
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    print("✓ Connected successfully!")
    
    # Authenticate
    print("\nAuthenticating...")
    send_rcon_packet(sock, 100, SERVERDATA_AUTH, "")
    
    auth_id, auth_type, auth_body = read_rcon_packet(sock)
    if auth_type == SERVERDATA_AUTH_RESPONSE:
        print("✓ Authentication successful!")
        return sock
    else:
        sock.close()
        raise Exception(f"Authentication failed: type={auth_type}, body='{auth_body}'")

def execute_command(sock, command, request_id=101):
    """Execute a command and return the response"""
    print(f"\nExecuting: {command}")
    send_rcon_packet(sock, request_id, SERVERDATA_EXECCOMMAND, command)
    
    cmd_id, cmd_type, cmd_body = read_rcon_packet(sock)
    if cmd_id == request_id and cmd_type == SERVERDATA_RESPONSE_VALUE:
        # Clean up response (remove trailing nulls)
        response = cmd_body.rstrip('\x00').strip()
        print(f"✓ Response: {response}")
        return response
    else:
        print(f"⚠ Unexpected response: id={cmd_id}, type={cmd_type}, body='{cmd_body}'")
        return cmd_body

def test_basic_commands(sock):
    """Test basic RCON commands"""
    print("\n" + "="*60)
    print("Testing Basic RCON Commands")
    print("="*60)
    
    # Test echo command
    execute_command(sock, "echo Hello from RCON!", 200)
    
    # Test empty command
    execute_command(sock, "", 201)
    
    # Test echo with special characters
    execute_command(sock, "echo Test with special chars: !@#$%^&*()", 202)

def test_hytale_commands(sock):
    """Test Hytale server commands"""
    print("\n" + "="*60)
    print("Testing Hytale Server Commands")
    print("="*60)
    
    # Test version command
    execute_command(sock, "version", 300)
    
    # Test help command
    execute_command(sock, "help", 301)
    
    # Test who command (list players)
    execute_command(sock, "who", 302)
    
    # Test commands command (list all commands)
    execute_command(sock, "commands", 303)

def test_interactive_mode(sock):
    """Interactive command mode"""
    print("\n" + "="*60)
    print("Interactive Command Mode")
    print("="*60)
    print("Type commands to execute (or 'quit' to exit)")
    print()
    
    request_id = 1000
    while True:
        try:
            command = input("RCON> ").strip()
            if not command:
                continue
            if command.lower() in ['quit', 'exit', 'q']:
                break
            
            response = execute_command(sock, command, request_id)
            request_id += 1
            time.sleep(0.1)  # Small delay between commands
            
        except KeyboardInterrupt:
            print("\nInterrupted by user")
            break
        except Exception as e:
            print(f"✗ Error: {e}")
            break

def main():
    """Main test function"""
    host = '127.0.0.1'
    port = 25575
    
    if len(sys.argv) > 1:
        if sys.argv[1] == '--interactive' or sys.argv[1] == '-i':
            # Interactive mode
            try:
                sock = connect_rcon(host, port)
                test_interactive_mode(sock)
                sock.close()
                print("\n✓ Session ended")
            except Exception as e:
                print(f"✗ Error: {e}")
                import traceback
                traceback.print_exc()
                sys.exit(1)
        elif sys.argv[1] == '--help' or sys.argv[1] == '-h':
            print("Usage:")
            print("  python3 test_rcon.py              - Run all tests")
            print("  python3 test_rcon.py -i          - Interactive mode")
            print("  python3 test_rcon.py --help     - Show this help")
            sys.exit(0)
        else:
            print(f"Unknown option: {sys.argv[1]}")
            print("Use --help for usage information")
            sys.exit(1)
    else:
        # Run all tests
        try:
            sock = connect_rcon(host, port)
            
            test_basic_commands(sock)
            test_hytale_commands(sock)
            
            sock.close()
            print("\n" + "="*60)
            print("✓ All tests completed successfully!")
            print("="*60)
            
        except socket.timeout:
            print("✗ Error: Connection timeout")
            import traceback
            traceback.print_exc()
            sys.exit(1)
        except Exception as e:
            print(f"✗ Error: {e}")
            import traceback
            traceback.print_exc()
            sys.exit(1)

if __name__ == "__main__":
    main()
