import base64
import json
import os
import ssl
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


class EchoHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _record(self):
        log_path = os.environ.get("F2_ACCESS_LOG", "")
        if log_path:
            path = urlsplit(self.path).path
            host = self.headers.get("Host", "")
            with open(log_path, "a", encoding="utf-8") as log:
                log.write(f"{self.command} host={host} path={path}\n")

    def _send(self, status, payload, headers=None):
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._record()
        path = urlsplit(self.path).path
        if self.headers.get("Host", "").split(":", 1)[0] == "rebind-target":
            if path == "/redirect-1":
                self._send(302, {"redirect": 1}, {"Location": "/redirect-2"})
                return
            if path == "/redirect-2":
                self._send(302, {"redirect": 2}, {"Location": "/redirect-3"})
                return
            if path == "/redirect-3":
                self._send(200, {"redirect": 3})
                return
        if path == "/redirect-1":
            self._send(302, {"redirect": 1}, {"Location": "/redirect-2"})
            return
        if path == "/redirect-2":
            self._send(200, {"redirect": 2, "cookie": self.headers.get("Cookie", "")})
            return
        if path == "/slow":
            time.sleep(float(os.environ.get("F2_SLOW_SECONDS", "3")))
        self._send(200, self._request_payload(b""))

    def do_POST(self):
        self._record()
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        self._send(200, self._request_payload(body))

    def _request_payload(self, body):
        split = urlsplit(self.path)
        content_type = self.headers.get("Content-Type", "")
        form = parse_qs(body.decode("utf-8", "replace"), keep_blank_values=True) if "x-www-form-urlencoded" in content_type else {}
        return {
            "path": split.path,
            "query": parse_qs(split.query, keep_blank_values=True),
            "headers": {key.lower(): value for key, value in self.headers.items()},
            "bodyBase64": base64.b64encode(body).decode(),
            "bodySize": len(body),
            "form": form,
            "mtls": bool(getattr(self.connection, "getpeercert", lambda: None)()),
        }

    def log_message(self, *_args):
        return


port = int(os.environ.get("F2_TARGET_PORT", "8080"))
server = ThreadingHTTPServer(("0.0.0.0", port), EchoHandler)
if os.environ.get("F2_TLS_CERT"):
    context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    context.load_cert_chain(os.environ["F2_TLS_CERT"], os.environ["F2_TLS_KEY"])
    context.load_verify_locations(cafile=os.environ["F2_TLS_CA"])
    context.verify_mode = ssl.CERT_REQUIRED
    server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
