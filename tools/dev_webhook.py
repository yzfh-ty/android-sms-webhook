"""Loopback-only WeCom group robot mock; no real group receives messages.

Run: python tools/dev_webhook.py --mode ok
Emulator: http://10.0.2.2:8765/cgi-bin/webhook/send?key=local-test
Modes: ok, busy-once, invalid-bot, rate-limit-once.
GET /state exposes counts and byte lengths, never bot keys or SMS contents.
"""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

LOCK = threading.Lock()
STATE = {"attempts": 0, "accepted": 0, "requests": []}
MODE = "ok"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # The request URL contains a credential; do not use the default access log.

    def reply(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path != "/state":
            self.reply(404, {"errcode": -2, "errmsg": "mock route not found"})
            return
        with LOCK:
            snapshot = {"attempts": STATE["attempts"], "accepted": STATE["accepted"],
                        "requests": list(STATE["requests"])}
        self.reply(200, snapshot)

    def do_POST(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query, keep_blank_values=True)
        if url.path != "/cgi-bin/webhook/send" or set(query) != {"key"} or len(query["key"]) != 1 or not query["key"][0]:
            self.reply(404, {"errcode": 93000, "errmsg": "invalid mock robot URL"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 262144:
                raise ValueError("invalid size")
            event = json.loads(self.rfile.read(size))
            content = event["text"]["content"]
            if event.get("msgtype") != "text" or not isinstance(content, str):
                raise ValueError("expected WeCom text contract")
            byte_count = len(content.encode("utf-8"))
            if not 0 < byte_count <= 2048:
                raise ValueError("text content must fit 2048 UTF-8 bytes")
            with LOCK:
                STATE["attempts"] += 1
                attempt = STATE["attempts"]
                code = 93000 if MODE == "invalid-bot" else (-1 if MODE == "busy-once" and attempt == 1
                    else (45009 if MODE == "rate-limit-once" and attempt == 1 else 0))
                if code == 0:
                    STATE["accepted"] += 1  # Deliberately no idempotency: same behavior as the real bot API.
                record = {"attempt": attempt, "errcode": code, "content_bytes": byte_count}
                STATE["requests"].append(record)
            print(json.dumps(record), flush=True)
            self.reply(200, {"errcode": code, "errmsg": "ok" if code == 0 else "simulated robot error"})
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            self.reply(400, {"errcode": -2, "errmsg": "invalid mock request"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--mode", choices=("ok", "busy-once", "invalid-bot", "rate-limit-once"), default="ok")
    args = parser.parse_args()
    MODE = args.mode
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Local WeCom mock on port {args.port}, mode={MODE}; GET /state for metadata", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
