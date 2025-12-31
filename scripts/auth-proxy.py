#!/usr/bin/env python3
"""
Auth proxy for Maven - handles HTTPS CONNECT with proxy authentication.

Java's HttpURLConnection doesn't send proxy auth for HTTPS CONNECT requests.
This script reads credentials from HTTP_PROXY/HTTPS_PROXY, listens locally,
and injects authentication into CONNECT requests.
"""

import base64
import os
import select
import socket
import sys
import threading
import urllib.parse

LOCAL_HOST = '127.0.0.1'
LOCAL_PORT = 3128
BUFFER_SIZE = 65536


def parse_proxy_url(proxy_url):
    """Parse proxy URL and extract host, port, and credentials."""
    if not proxy_url:
        return None, None, None, None

    parsed = urllib.parse.urlparse(proxy_url)

    username = urllib.parse.unquote(parsed.username) if parsed.username else None
    password = urllib.parse.unquote(parsed.password) if parsed.password else None
    host = parsed.hostname
    port = parsed.port or 3128

    return host, port, username, password


def create_auth_header(username, password):
    """Create Proxy-Authorization header."""
    if not username:
        return None
    credentials = f"{username}:{password or ''}"
    encoded = base64.b64encode(credentials.encode()).decode()
    return f"Proxy-Authorization: Basic {encoded}\r\n"


def handle_connect(client_socket, target_host, target_port, proxy_host, proxy_port, auth_header):
    """Handle CONNECT method by connecting through upstream proxy with auth."""
    try:
        # Connect to upstream proxy
        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.connect((proxy_host, proxy_port))

        # Send CONNECT with auth
        connect_request = f"CONNECT {target_host}:{target_port} HTTP/1.1\r\n"
        connect_request += f"Host: {target_host}:{target_port}\r\n"
        if auth_header:
            connect_request += auth_header
        connect_request += "\r\n"

        upstream.sendall(connect_request.encode())

        # Read response from upstream proxy
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = upstream.recv(BUFFER_SIZE)
            if not chunk:
                break
            response += chunk

        # Check if connection established
        response_line = response.split(b"\r\n")[0].decode()
        if "200" in response_line:
            # Tell client connection is established
            client_socket.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")

            # Tunnel data between client and upstream
            tunnel(client_socket, upstream)
        else:
            # Forward error to client
            client_socket.sendall(response)

    except Exception as e:
        print(f"CONNECT error: {e}", file=sys.stderr)
        try:
            client_socket.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
        except:
            pass
    finally:
        try:
            upstream.close()
        except:
            pass


def handle_http(client_socket, request, proxy_host, proxy_port, auth_header):
    """Handle regular HTTP request by forwarding with auth."""
    try:
        # Connect to upstream proxy
        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.connect((proxy_host, proxy_port))

        # Inject auth header into request
        if auth_header:
            # Find end of first line
            first_line_end = request.find(b"\r\n")
            rest = request[first_line_end + 2:]

            # Insert auth header after first line
            modified_request = request[:first_line_end + 2]
            modified_request += auth_header.encode()
            modified_request += rest
            request = modified_request

        upstream.sendall(request)

        # Tunnel response
        tunnel(client_socket, upstream)

    except Exception as e:
        print(f"HTTP error: {e}", file=sys.stderr)
        try:
            client_socket.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
        except:
            pass
    finally:
        try:
            upstream.close()
        except:
            pass


def tunnel(client, upstream):
    """Tunnel data bidirectionally between two sockets."""
    sockets = [client, upstream]
    try:
        while True:
            readable, _, exceptional = select.select(sockets, [], sockets, 60)

            if exceptional:
                break

            if not readable:
                break

            for sock in readable:
                try:
                    data = sock.recv(BUFFER_SIZE)
                    if not data:
                        return

                    if sock is client:
                        upstream.sendall(data)
                    else:
                        client.sendall(data)
                except:
                    return
    except:
        pass


def handle_client(client_socket, proxy_host, proxy_port, auth_header):
    """Handle a client connection."""
    try:
        # Read initial request
        request = b""
        while b"\r\n\r\n" not in request and b"\n\n" not in request:
            chunk = client_socket.recv(BUFFER_SIZE)
            if not chunk:
                return
            request += chunk

        # Parse first line
        first_line = request.split(b"\r\n")[0].decode()
        parts = first_line.split()

        if len(parts) < 3:
            return

        method = parts[0]

        if method == "CONNECT":
            # HTTPS tunnel request
            target = parts[1]
            if ":" in target:
                target_host, target_port = target.rsplit(":", 1)
                target_port = int(target_port)
            else:
                target_host = target
                target_port = 443

            handle_connect(client_socket, target_host, target_port,
                         proxy_host, proxy_port, auth_header)
        else:
            # Regular HTTP request
            handle_http(client_socket, request, proxy_host, proxy_port, auth_header)

    except Exception as e:
        print(f"Client error: {e}", file=sys.stderr)
    finally:
        try:
            client_socket.close()
        except:
            pass


def main():
    # Parse proxy configuration from environment
    proxy_url = os.environ.get('HTTPS_PROXY') or os.environ.get('HTTP_PROXY') or \
                os.environ.get('https_proxy') or os.environ.get('http_proxy')

    if not proxy_url:
        print("No proxy environment variable found", file=sys.stderr)
        sys.exit(1)

    proxy_host, proxy_port, username, password = parse_proxy_url(proxy_url)

    if not proxy_host:
        print("Could not parse proxy URL", file=sys.stderr)
        sys.exit(1)

    auth_header = create_auth_header(username, password)

    print(f"Starting auth proxy on {LOCAL_HOST}:{LOCAL_PORT}")
    print(f"Upstream proxy: {proxy_host}:{proxy_port}")
    print(f"Auth: {'enabled' if auth_header else 'disabled'}")

    # Create listening socket
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LOCAL_HOST, LOCAL_PORT))
    server.listen(100)

    print(f"Listening on {LOCAL_HOST}:{LOCAL_PORT}")

    try:
        while True:
            client_socket, addr = server.accept()
            thread = threading.Thread(
                target=handle_client,
                args=(client_socket, proxy_host, proxy_port, auth_header)
            )
            thread.daemon = True
            thread.start()
    except KeyboardInterrupt:
        print("\nShutting down")
    finally:
        server.close()


if __name__ == "__main__":
    main()
