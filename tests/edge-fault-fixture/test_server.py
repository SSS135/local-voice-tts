"""Host transport checks; Android owns MP3 decoding/PCM commit assertions."""
import base64
import json
import socket
import struct
import threading
import time
import unittest
import urllib.request
import uuid
from pathlib import Path
import server
import analyze


def masked(payload):
    mask = b"test"
    length = len(payload)
    header = bytes((129, 128 | length)) if length < 126 else bytes((129, 254)) + struct.pack("!H", length)
    return header + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(payload))


class FixtureTest(unittest.TestCase):
    def setUp(self):
        build = Path(__file__).resolve().parents[2] / "build" / "edge-fixture"
        build.mkdir(parents=True, exist_ok=True)
        self.log = build / ("hosttest-" + uuid.uuid4().hex + ".jsonl")
        self.audio = b"\xff\xf3" + bytes(range(256)) * 20
        self.server = server.make_server(self.audio, self.log,
                                        {"actions": [{"type": "close", "bytes": 500},
                                                     {"type": "truncate", "drop_bytes": 1},
                                                     {"type": "stall", "seconds": 2},
                                                     {"type": "success"}]}, 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        if self.log.exists():
            self.log.unlink()

    def connect(self, request_id):
        sock = socket.create_connection(self.server.server_address, timeout=2)
        stream = sock.makefile("rb")
        key = base64.b64encode(b"sixteen-byte-key").decode()
        sock.sendall(("GET /edge HTTP/1.1\r\nHost:localhost\r\nUpgrade:websocket\r\n"
                      "Connection:Upgrade\r\nSec-WebSocket-Version:13\r\n"
                      f"Sec-WebSocket-Key:{key}\r\n\r\n").encode())
        self.assertIn(b"101", stream.readline())
        while stream.readline() != b"\r\n":
            pass
        text = (f"X-RequestId:{request_id}\r\nPath:ssml\r\n\r\n"
                '<speak><voice name="test">Public fixture only.</voice></speak>').encode()
        sock.sendall(masked(text))
        return sock, stream

    def receive(self, stream):
        audio, ended = bytearray(), False
        try:
            while True:
                opcode, data = server.read_frame(stream)
                if opcode == 2:
                    header = struct.unpack("!H", data[:2])[0]
                    audio.extend(data[2 + header:])
                elif opcode == 1:
                    self.assertEqual(data, b"Path:turn.end\r\n\r\n")
                    ended = True
                    break
        except EOFError:
            pass
        return bytes(audio), ended

    def test_fault_sequence_preserves_hashes_and_private_text(self):
        sock, stream = self.connect("attempt-one")
        self.assertEqual(self.receive(stream), (self.audio[:500], False))
        sock.close(); stream.close()
        sock, stream = self.connect("attempt-two")
        self.assertEqual(self.receive(stream), (self.audio[:-1], True))
        sock.close(); stream.close()
        sock, stream = self.connect("attempt-three")
        time.sleep(0.1)
        sock.close(); stream.close()
        sock, stream = self.connect("attempt-four")
        self.assertEqual(self.receive(stream), (self.audio, True))
        sock.close(); stream.close()
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            raw = self.log.read_text()
            if '"event": "peer_cancelled"' in raw:
                break
            time.sleep(0.02)
        events = [json.loads(line) for line in raw.splitlines()]
        tries = [event for event in events if event["event"] == "attempt"]
        self.assertEqual([row["chunk_attempt"] for row in tries], [1, 2, 3, 4])
        self.assertEqual(len({row["speak_sha256"] for row in tries}), 1)
        self.assertEqual(len({row["text_sha256"] for row in tries}), 1)
        self.assertEqual(len({row["request_id_sha256"] for row in tries}), 4)
        self.assertEqual(sum(e["event"] == "served_complete" for e in events), 1)
        self.assertIn('"event": "peer_cancelled"', raw)
        self.assertNotIn("Public fixture only", raw)
        self.assertNotIn("attempt-one", raw)
        result = analyze.analyze(events, 1, [tries[0]["speak_sha256"]])
        self.assertEqual(result["attempts"], 4)
        with self.assertRaises(AssertionError):
            analyze.analyze(events, 2)
        with self.assertRaises(AssertionError):
            analyze.analyze(events, 1, ["wrong-expected-chunk"])

    def test_control_delayed_recovery_and_http_failure(self):
        address = f"http://127.0.0.1:{self.server.server_port}"
        control = urllib.request.Request(address + "/control", method="POST",
                  data=b'{"actions":[{"type":"http_fail"}],"repeat_last":true}',
                  headers={"Content-Type": "application/json"})
        self.assertEqual(json.load(urllib.request.urlopen(control)), {"configured": True})
        for _ in range(3):
            request = urllib.request.Request(address + "/edge", headers={"Upgrade": "websocket"})
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request)
            self.assertEqual(error.exception.code, 503)
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(address + "/edge")
        # The /edge endpoint requires WebSocket headers; plain HTTP remains a404.
        self.assertEqual(error.exception.code, 404)
        control.data = b'{"actions":[{"type":"success"}]}'
        urllib.request.urlopen(control).close()
        sock, stream = self.connect("recovered")
        self.assertEqual(self.receive(stream), (self.audio, True))
        sock.close(); stream.close()

    def test_streaming_actions(self):
        address = f"http://127.0.0.1:{self.server.server_port}"
        control = urllib.request.Request(address + "/control", method="POST", headers={"Content-Type": "application/json"},
                  data=json.dumps({"words": True, "repeat_last": False, "actions": [
                      {"type": "close", "bytes": 700, "fragment_bytes": 300, "code": 1007},
                      {"type": "idle_close"}]}).encode())
        urllib.request.urlopen(control).close()
        sock, stream = self.connect("declined-after-audio")
        texts, audio = [], bytearray()
        try:
            while True:
                opcode, data = server.read_frame(stream)
                if opcode == 1:
                    texts.append(data)
                elif opcode == 2:
                    audio.extend(data[2 + struct.unpack("!H", data[:2])[0]:])
                elif opcode == 8:
                    self.assertEqual(struct.unpack("!H", data[:2])[0], 1007)
                    break
        finally:
            sock.close(); stream.close()
        self.assertEqual(bytes(audio), self.audio[:700])
        self.assertEqual([json.loads(t.split(b"\r\n\r\n", 1)[1])["Metadata"][0]["Data"]["text"]["Text"] for t in texts],
                         ["Public", "fixture", "only"][:len(texts)])
        self.assertTrue(texts and all(b"Path:audio.metadata" in t for t in texts))
        # idle_close: a connection that never sends SSML is closed by the server (1001).
        sock = socket.create_connection(self.server.server_address, timeout=2)
        stream = sock.makefile("rb")
        sock.sendall(b"GET /edge HTTP/1.1\r\nHost:localhost\r\nUpgrade:websocket\r\nConnection:Upgrade\r\n"
                     b"Sec-WebSocket-Version:13\r\nSec-WebSocket-Key:c2l4dGVlbi1ieXRlLWtleQ==\r\n\r\n")
        while stream.readline() != b"\r\n":
            pass
        self.assertEqual(server.read_frame(stream), (8, struct.pack("!H", 1001)))
        sock.close(); stream.close()
        raw = self.log.read_text()
        self.assertIn('"idle_closed"', raw)
        self.assertIn('"words_sent": ', raw)

    def test_declined_turn_and_auth_status(self):
        address = f"http://127.0.0.1:{self.server.server_port}"
        control = urllib.request.Request(address + "/control", method="POST",
                  data=b'{"actions":[{"type":"no_audio"},{"type":"http_fail","status":403}],"repeat_last":false}',
                  headers={"Content-Type": "application/json"})
        urllib.request.urlopen(control).close()
        sock, stream = self.connect("declined")
        self.assertEqual(self.receive(stream), (b"", True))
        sock.close(); stream.close()
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(urllib.request.Request(address + "/edge", headers={"Upgrade": "websocket"}))
        self.assertEqual(error.exception.code, 403)


if __name__ == "__main__":
    unittest.main()
