#!/usr/bin/env python3
"""Loopback lifecycle admission, not a latency or throughput benchmark."""
import collections
import concurrent.futures
import http.client
import http.server
import socket
import threading
import time

from check_contract import PREFIX, MAX_RESPONSE_BYTES, strict_json

ELEMENT = b'{"He":{"name":"Helium","number":2,"group":18}}'
SHELLS = b'{"He":[2]}'
DEADLINE_SECONDS = 5  # Candidate listener contract, not a benchmark metric.
CLIENT_LIMIT = 12


class LifecycleSource(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with self.server.lock:
            self.server.counts[self.path] += 1
            stall = self.path == "/element.json" and self.server.stall
        body = ELEMENT if self.path == "/element.json" else SHELLS
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if stall:
                self.wfile.write(body[:1])
                self.wfile.flush()
                self.server.started.set()
                if not self.server.release.wait(CLIENT_LIMIT + 2):
                    return
                body = body[1:]
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            if stall:
                self.server.finished.set()

    def log_message(self, *_args):
        pass


def get(route, status, expected=None):
    conn = http.client.HTTPConnection("127.0.0.1", 5001, timeout=CLIENT_LIMIT)
    try:
        conn.request("GET", PREFIX + route + "?symbol=He")
        with conn.getresponse() as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if response.status != status or len(raw) > MAX_RESPONSE_BYTES:
                raise AssertionError(f"{route}: expected {status}, got {response.status}")
            if expected is not None:
                if response.headers.get_content_type() != "application/json":
                    raise AssertionError("expected JSON")
                if strict_json(raw) != expected:
                    raise AssertionError(f"{route}: invalid recovery body")
    finally:
        conn.close()


def run():
    with http.server.ThreadingHTTPServer(("127.0.0.1", 5002), LifecycleSource) as source:
        source.lock = threading.Lock()
        source.counts = collections.Counter()
        source.started = threading.Event()
        source.release = threading.Event()
        source.finished = threading.Event()
        source.stall = True
        worker = threading.Thread(target=source.serve_forever, daemon=True)
        worker.start()

        def reset_stall():
            source.release.set()
            if not source.finished.wait(3):
                raise AssertionError("fixture handler did not retire")
            with source.lock:
                source.stall = True
                source.started.clear()
                source.release.clear()
                source.finished.clear()

        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                slow = pool.submit(get, "element", 504)
                try:
                    if not source.started.wait(3):
                        raise AssertionError("slow request never reached upstream")
                    get("shells", 200, {"shells": [2]})
                    # Healthy route must finish while the stalled request is still held.
                    if slow.done():
                        raise AssertionError("healthy route did not finish before stalled request deadline")
                    slow.result(timeout=CLIENT_LIMIT)
                finally:
                    source.release.set()
            reset_stall()

            # Abandon an inbound socket only after proving upstream I/O began.
            with socket.create_connection(("127.0.0.1", 5001), timeout=3) as abandoned:
                abandoned.sendall(("GET " + PREFIX + "element?symbol=He HTTP/1.1\r\n"
                                   "Host: 127.0.0.1:5001\r\nConnection: close\r\n\r\n").encode("ascii"))
                if not source.started.wait(3):
                    raise AssertionError("abandoned request never reached upstream")
            get("shells", 200, {"shells": [2]})
            # Allow the configured listener deadline to retire the abandoned
            # exchange before releasing the source. No claim of immediate cancellation.
            time.sleep(DEADLINE_SECONDS + 1)
            source.release.set()
            if not source.finished.wait(3):
                raise AssertionError("abandoned upstream fixture did not retire")
            with source.lock:
                source.stall = False
            get("element", 200, {"name": "Helium", "number": 2, "group": 18})
            get("shells", 200, {"shells": [2]})
            with source.lock:
                actual = dict(source.counts)
            if actual != {"/element.json": 3, "/shells.json": 3}:
                raise AssertionError(f"unexpected upstream request counts: {actual}")
        finally:
            source.release.set()
            source.shutdown()
            worker.join(timeout=3)
    print("PASS: deadline, sibling progress, abandoned socket, recovery; no performance measurement")
    print("Socket-level lifecycle proof only; actor/handle reclamation needs runtime counters.")


if __name__ == "__main__":
    run()
