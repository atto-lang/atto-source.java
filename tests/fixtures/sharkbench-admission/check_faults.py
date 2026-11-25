#!/usr/bin/env python3
"""Negative and recovery probes; separate from the pinned 72-request workload."""
import collections
import http.client
import http.server
import json
import threading

from check_contract import strict_json, PREFIX, MAX_RESPONSE_BYTES

ELEMENT = b'{"He":{"name":"Helium","number":2,"group":18}}'
SHELLS = b'{"He":[2]}'


class FaultSource(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with self.server.lock:
            self.server.counts[self.path] += 1
            status, body = self.server.responses[self.path]
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Guest is allowed to close an oversized/error response early.

    def log_message(self, *_args):
        pass


def check(method, path, status, expected=None):
    conn = http.client.HTTPConnection("127.0.0.1", 5001, timeout=8)
    try:
        conn.request(method, PREFIX + path)
        with conn.getresponse() as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if response.status != status or len(raw) > MAX_RESPONSE_BYTES:
                raise AssertionError(f"{method} {path}: expected {status}, got {response.status}, {len(raw)} bytes")
            if expected is not None:
                if response.headers.get_content_type() != "application/json":
                    raise AssertionError("recovery must return JSON")
                if strict_json(raw) != expected:
                    raise AssertionError("incorrect recovery value")
    finally:
        conn.close()


def run():
    with http.server.ThreadingHTTPServer(("127.0.0.1", 5002), FaultSource) as source:
        source.lock = threading.Lock()
        source.counts = collections.Counter()
        source.responses = {"/element.json": (200, ELEMENT), "/shells.json": (200, SHELLS)}
        worker = threading.Thread(target=source.serve_forever, daemon=True)
        worker.start()
        cases = 0

        def exercise(method, path, expected_status, upstream=None, reply=None, expected=None, label=None):
            nonlocal cases
            with source.lock:
                source.counts.clear()
                source.responses = {"/element.json": (200, ELEMENT), "/shells.json": (200, SHELLS)}
                if reply is not None:
                    source.responses[upstream] = reply
            label = label or f'{method} {path} expected={expected_status}'
            print(f'BEGIN fault probe {cases + 1}: {label}', flush=True)
            check(method, path, expected_status, expected)
            with source.lock:
                actual = dict(source.counts)
            wanted = {upstream: 1} if upstream else {}
            if actual != wanted:
                raise AssertionError(f"{method} {path}: upstream counts {actual}, expected {wanted}")
            cases += 1
            print(f'PASS fault probe {cases}: {label}', flush=True)

        try:
            for method, path, status in (
                ("POST", "element?symbol=He", 405),
                ("GET", "missing?symbol=He", 404),
                ("GET", "element", 400),
                ("GET", "element?symbol=he", 400),
                ("GET", "element?symbol=%48e", 400),
                ("GET", "element?symbol=He&symbol=Na", 400),
                ("GET", "element?symbol=He?x", 400),
            ):
                exercise(method, path, status)

            exercise("GET", "element?symbol=Xx", 404, "/element.json")
            for label, body in (
                ("empty", b""),
                ("malformed-json", b"{"),
                ("null-element", b'{"He":null}'),
                ("missing-group", b'{"He":{"name":"Helium","number":2}}'),
                ("boolean-number", b'{"He":{"name":"Helium","number":true,"group":18}}'),
                ("duplicate-key", b'{"He":{"name":"A","name":"B","number":2,"group":18}}'),
                ("invalid-utf8", b'{"He":{"name":"' + bytes([0xff]) + b'","number":2,"group":18}}'),
                ("oversize", b" " * 131073),
            ):
                exercise("GET", "element?symbol=He", 502, "/element.json", (200, body), label=label)
            exercise("GET", "element?symbol=He", 502, "/element.json", (503, b"unavailable"),
                     label="upstream-503")
            for label, body in (
                ("empty-shells", b'{"He":[]}'),
                ("boolean-shell", b'{"He":[true]}'),
                ("object-shells", b'{"He":{}}'),
                ("oversize-shell-array", json.dumps({"He": [1] * 33}).encode("ascii")),
            ):
                exercise("GET", "shells?symbol=He", 502, "/shells.json", (200, body), label=label)
            exercise("GET", "element?symbol=He", 200, "/element.json",
                     expected={"name": "Helium", "number": 2, "group": 18})
            exercise("GET", "shells?symbol=He", 200, "/shells.json", expected={"shells": [2]})
        finally:
            source.shutdown()
            worker.join()
    print(f"PASS: {cases} fault/recovery probes; no performance measurement")


if __name__ == "__main__":
    run()
