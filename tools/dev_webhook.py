"""Loopback-only development receiver. State is in memory, not production durable storage.

Run: python tools/dev_webhook.py
Emulator URL: http://10.0.2.2:8765/hooks/sms
Optional auth: set SMS_WEBHOOK_TOKEN and/or SMS_WEBHOOK_SECRET in the environment.
GET /state reports IDs and response counts without SMS contents or credentials.
POST /hooks/flaky returns 500 once per ID; /hooks/unauthorized always returns 401.
"""
import argparse
import hashlib
import hmac
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
ACCEPTED = {}
ATTEMPTS = {}
TOKEN = os.environ.get("SMS_WEBHOOK_TOKEN", "")
SECRET = os.environ.get("SMS_WEBHOOK_SECRET", "")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # No payloads, phone numbers, tokens or URLs in logs.

    def reply(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != "/state":
            self.reply(404, {"error": "not found"})
            return
        with LOCK:
            state = {"accepted_count": len(ACCEPTED), "attempts": dict(ATTEMPTS),
                     "accepted": [{"id": key, "source": value["source"]}
                                  for key, value in ACCEPTED.items()]}
        self.reply(200, state)

    def do_POST(self):
        if self.path not in ("/hooks/sms", "/hooks/flaky", "/hooks/unauthorized"):
            self.reply(404, {"error": "not found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 262144:
                self.reply(413, {"error": "invalid size"})
                return
            raw = self.rfile.read(size)
            event = json.loads(raw)
            event_id = self.headers.get("Idempotency-Key", "")
            if not event_id or event.get("delivery_id") != event_id:
                raise ValueError("missing id")
            if TOKEN and not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + TOKEN):
                self.reply(401, {"error": "authentication failed"})
                return
            if SECRET:
                stamp = self.headers.get("X-SMS-Timestamp", "")
                expected = "v1=" + hmac.new(SECRET.encode(), stamp.encode() + b"." + raw, hashlib.sha256).hexdigest()
                if abs(time.time() - int(stamp)) > 300 or not hmac.compare_digest(
                        self.headers.get("X-SMS-Signature", ""), expected):
                    self.reply(403, {"error": "signature failed"})
                    return
            with LOCK:
                ATTEMPTS[event_id] = ATTEMPTS.get(event_id, 0) + 1
                count = ATTEMPTS[event_id]
                if self.path == "/hooks/unauthorized":
                    status = 401
                elif self.path == "/hooks/flaky" and count == 1:
                    status = 500
                else:
                    digest = hashlib.sha256(raw).hexdigest()
                    previous = ACCEPTED.get(event_id)
                    status = 409 if previous and previous["digest"] != digest else 200
                    if status == 200:
                        ACCEPTED[event_id] = {"digest": digest, "source": event.get("source", "unknown")}
            print(json.dumps({"id": event_id, "attempt": count, "status": status}), flush=True)
            self.reply(status, {"accepted": status == 200, "delivery_id": event_id})
        except (ValueError, TypeError, json.JSONDecodeError):
            self.reply(400, {"error": "invalid request"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Development receiver on http://127.0.0.1:{args.port}; use /state for status", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
