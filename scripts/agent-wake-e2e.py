#!/usr/bin/env python3
"""Connect one agent wake socket and print the next server text frame.

This deliberately uses only Python's standard library.  It is a protocol-level test client for
the Tomcat WebSocket endpoint, not a replacement for the Android agent's WebSocket client.
"""
import base64
import hashlib
import os
import socket
import ssl
import sys
from urllib.parse import quote, urlsplit


def receive_until(sock, marker):
    data = b""
    while marker not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("connection closed during WebSocket handshake")
        data += chunk
        if len(data) > 65536:
            raise RuntimeError("WebSocket handshake response is unexpectedly large")
    return data.split(marker, 1)[0]


def receive_exact(sock, size):
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError("connection closed while reading WebSocket frame")
        data += chunk
    return data


def receive_text_frame(sock):
    first, second = receive_exact(sock, 2)
    opcode = first & 0x0F
    if opcode == 0x8:
        raise RuntimeError("server closed the WebSocket before sending a wake signal")
    if opcode != 0x1:
        raise RuntimeError("expected a text WebSocket frame, got opcode {}".format(opcode))

    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        length = int.from_bytes(receive_exact(sock, 2), "big")
    elif length == 127:
        length = int.from_bytes(receive_exact(sock, 8), "big")
    if length > 65536:
        raise RuntimeError("wake frame is unexpectedly large")

    mask = receive_exact(sock, 4) if masked else None
    payload = receive_exact(sock, length)
    if mask is not None:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return payload.decode("utf-8")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: agent-wake-e2e.py BASE_URL DEVICE_ID < DEVICE_SECRET")

    base_url, device_id = sys.argv[1:]
    secret = sys.stdin.readline().rstrip("\r\n")
    if not secret:
        raise RuntimeError("device secret was not supplied on standard input")
    base = urlsplit(base_url)
    if base.scheme not in ("http", "https") or not base.hostname:
        raise RuntimeError("BASE_URL must be an absolute http(s) URL")

    scheme = "wss" if base.scheme == "https" else "ws"
    port = base.port or (443 if scheme == "wss" else 80)
    prefix = base.path.rstrip("/")
    path = "{}/agent/ws/{}".format(prefix, quote(device_id, safe=""))
    host = base.hostname
    host_header = host if base.port is None else "{}:{}".format(host, base.port)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    expected_accept = base64.b64encode(hashlib.sha1(
        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()).decode("ascii")

    raw_socket = socket.create_connection((host, port), timeout=15)
    if scheme == "wss":
        context = ssl.create_default_context()
        sock = context.wrap_socket(raw_socket, server_hostname=host)
    else:
        sock = raw_socket

    try:
        request = (
            "GET {} HTTP/1.1\r\n"
            "Host: {}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: {}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Authorization: Bearer {}\r\n"
            "\r\n"
        ).format(path, host_header, key, secret)
        sock.sendall(request.encode("ascii"))
        response = receive_until(sock, b"\r\n\r\n").decode("iso-8859-1")
        lines = response.split("\r\n")
        if not lines or " 101 " not in " {} ".format(lines[0]):
            raise RuntimeError("WebSocket upgrade failed: {}".format(lines[0] if lines else "no response"))
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                name, value = line.split(":", 1)
                headers[name.lower()] = value.strip()
        if headers.get("sec-websocket-accept") != expected_accept:
            raise RuntimeError("WebSocket server returned an invalid Sec-WebSocket-Accept header")

        print("READY", flush=True)
        sock.settimeout(20)
        print(receive_text_frame(sock), flush=True)
    finally:
        sock.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("ERROR: {}".format(error), file=sys.stderr, flush=True)
        sys.exit(1)
