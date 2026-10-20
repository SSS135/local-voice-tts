"""Loopback-only, stdlib Edge WebSocket fault fixture. Never logs request text."""
import argparse
import base64
import hashlib
import json
import socket
import struct
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_FRAME = 65536
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def exact(stream, count):
    result = bytearray()
    while len(result) < count:
        data = stream.read(count - len(result))
        if not data:
            raise EOFError()
        result.extend(data)
    return bytes(result)


def read_frame(stream):
    first, second = exact(stream, 2)
    length = second & 127
    if length == 126:
        length = struct.unpack("!H", exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", exact(stream, 8))[0]
    if length > MAX_FRAME or not first & 128 or first & 112:
        raise ValueError("unsupported frame")
    mask = exact(stream, 4) if second & 128 else b""
    data = exact(stream, length)
    if mask:
        data = bytes(value ^ mask[i % 4] for i, value in enumerate(data))
    return first & 15, data


def frame(opcode, payload):
    length = len(payload)
    if length < 126:
        header = bytes((128 | opcode, length))
    elif length < 65536:
        header = bytes((128 | opcode, 126)) + struct.pack("!H", length)
    else:
        header = bytes((128 | opcode, 127)) + struct.pack("!Q", length)
    return header + payload


class State:
    def __init__(self, mp3, log, scenario):
        self.mp3, self.log = mp3, log
        self.lock = threading.Lock()
        self.counter = 0
        self.tries = {}
        self.configure(scenario)

    def configure(self, scenario):
        if not isinstance(scenario, dict):
            raise ValueError("invalid scenario")
        actions = scenario.get("actions", [{"type": "success"}])
        if not isinstance(actions, list) or not actions or len(actions) > 1024 or any(not isinstance(a, dict) or a.get("type") not in
                              {"success", "close", "truncate", "stall", "http_fail", "no_audio"}
                              for a in actions):
            raise ValueError("invalid actions")
        for action in actions:
            if not 0 <= float(action.get("seconds", 0)) <= 3600:
                raise ValueError("invalid delay")
            if not 400 <= int(action.get("status", 503)) <= 599:
                raise ValueError("invalid status")
            for key in ("bytes", "drop_bytes", "fragment_bytes"):
                if key in action and not 1 <= int(action[key]) <= 1048576:
                    raise ValueError("invalid byte count")
        self.actions = actions
        self.position = 0
        self.repeat_last = bool(scenario.get("repeat_last", True))

    def event(self, event, **values):
        row = {"event": event, "time_ns": time.monotonic_ns(), **values}
        with self.lock:
            with self.log.open("a", encoding="utf-8") as out:
                out.write(json.dumps(row, sort_keys=True) + "\n")

    def next_action(self):
        with self.lock:
            i = self.position
            self.position += 1
            if i >= len(self.actions):
                return self.actions[-1] if self.repeat_last else {"type": "success"}
            return self.actions[i]

    def attempt(self, payload, action):
        headers, body = payload.split(b"\r\n\r\n", 1)
        root = ET.fromstring(body)
        if root.tag.rsplit("}", 1)[-1] != "speak":
            raise ValueError("expected speak")
        text_hash = digest("".join(root.itertext()).encode("utf-8"))
        speak_hash = digest(body)
        request_id = next((line.split(b":", 1)[1].strip() for line in headers.split(b"\r\n")
                           if line.lower().startswith(b"x-requestid:")), b"")
        with self.lock:
            self.counter += 1
            attempt_id = self.counter
            self.tries[speak_hash] = self.tries.get(speak_hash, 0) + 1
            chunk_attempt = self.tries[speak_hash]
        self.event("attempt", attempt_id=attempt_id, chunk_attempt=chunk_attempt,
                   text_sha256=text_hash, speak_sha256=speak_hash,
                   request_id_sha256=digest(request_id), action=action["type"])
        return attempt_id


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    @property
    def state(self):
        return self.server.fixture

    def reply(self, status, value):
        data = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path != "/control":
            return self.reply(404, {"error": "unknown path"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= MAX_FRAME:
                raise ValueError()
            scenario = json.loads(exact(self.rfile, size))
            with self.state.lock:
                self.state.configure(scenario)
            self.state.event("configured", actions=[a["type"] for a in self.state.actions])
            self.reply(200, {"configured": True})
        except (ValueError, KeyError, TypeError):
            self.reply(400, {"error": "invalid scenario"})

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"ready": True, "attempts": self.state.counter,
                                    "mp3_sha256": digest(self.state.mp3)})
        if self.path.split("?", 1)[0] != "/edge" or self.headers.get("Upgrade", "").lower() != "websocket":
            return self.reply(404, {"error": "unknown path"})
        action = self.state.next_action()
        if action["type"] == "http_fail":
            status = int(action.get("status", 503))
            self.state.event("http_fail", status=status)
            self.close_connection = True
            return self.reply(status, {"error": "fixture unavailable"})
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest()).decode("ascii")
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        self.connection.settimeout(15)
        attempt_id = None
        try:
            while True:
                opcode, data = read_frame(self.rfile)
                if opcode == 8:
                    return
                if opcode == 9:
                    self.connection.sendall(frame(10, data))
                elif opcode == 1 and b"Path:ssml\r\n" in data:
                    attempt_id = self.state.attempt(data, action)
                    self.respond(action, attempt_id)
                    return
        except (EOFError, OSError, ValueError, ET.ParseError):
            self.state.event("disconnected", attempt_id=attempt_id)

    def respond(self, action, attempt_id):
        kind = action["type"]
        if kind == "stall":
            self.state.event("stalled", attempt_id=attempt_id)
            deadline = time.monotonic() + float(action.get("seconds", 60))
            self.connection.settimeout(0.1)
            while time.monotonic() < deadline:
                try:
                    if not self.connection.recv(1024):
                        self.state.event("peer_cancelled", attempt_id=attempt_id)
                        return
                except socket.timeout:
                    pass
            self.state.event("stall_elapsed", attempt_id=attempt_id)
            return
        audio = self.state.mp3
        if kind == "no_audio":
            audio = b""
        elif kind == "truncate":
            audio = audio[:-min(len(audio) - 1, int(action.get("drop_bytes", 1)))]
        elif kind == "close":
            audio = audio[:int(action.get("bytes", max(1, len(audio) // 2)))]
        header = b"Content-Type:audio/mpeg\r\nPath:audio\r\n"
        piece = int(action.get("fragment_bytes", 4096))
        for start in range(0, len(audio), piece):
            body = struct.pack("!H", len(header)) + header + audio[start:start + piece]
            self.connection.sendall(frame(2, body))
        if kind == "close":
            self.state.event("mid_response_close", attempt_id=attempt_id, bytes_sent=len(audio))
            self.connection.shutdown(socket.SHUT_RDWR)
            return
        self.connection.sendall(frame(1, b"Path:turn.end\r\n\r\n"))
        self.state.event({"success": "served_complete", "no_audio": "served_no_audio"}.get(kind, "served_truncated"),
                         attempt_id=attempt_id, bytes_sent=len(audio), audio_sha256=digest(audio))
        # Keep the socket open long enough to observe the client's terminal close/cancel.
        self.connection.settimeout(2)
        try:
            opcode, _ = read_frame(self.rfile)
            self.state.event("peer_close", attempt_id=attempt_id, opcode=opcode)
        except (EOFError, OSError, ValueError):
            pass


def make_server(mp3, log, scenario, port=8765):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    server.fixture = State(mp3, Path(log), scenario)
    return server


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mp3", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--scenario", type=Path)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    mp3 = args.mp3.read_bytes()
    if not 1 <= len(mp3) <= 8 * 1024 * 1024:
        parser.error("MP3 must be nonempty and at most 8MiB")
    args.log.parent.mkdir(parents=True, exist_ok=True)
    scenario = json.loads(args.scenario.read_text(encoding="utf-8-sig")) if args.scenario else {"actions": [{"type": "success"}]}
    server = make_server(mp3, args.log, scenario, args.port)
    print(json.dumps({"endpoint": f"ws://127.0.0.1:{server.server_port}/edge", "mp3_sha256": digest(mp3)}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
