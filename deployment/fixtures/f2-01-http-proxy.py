import base64
import hashlib
import hmac
import json
import os
import threading
import time
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit


SIGNING_KEY = os.environ.get("F2_PROXY_SIGNING_KEY", "")
EXPECTED_PROXY_ENDPOINTS = [value.strip() for value in os.environ.get("F2_PROXY_ENDPOINTS", "").split(",") if value.strip()]
NONCE_LOCK = threading.Lock()
USED_NONCES = set()


def consume_nonce(nonce):
    if not nonce:
        return False
    with NONCE_LOCK:
        if nonce in USED_NONCES:
            return False
        USED_NONCES.add(nonce)
        return True


def canonical_target(value):
    target = urlsplit(str(value))
    if target.scheme.lower() not in ("http", "https") or not target.hostname:
        raise ValueError("invalid target")
    host = target.hostname.lower()
    if ":" in host:
        host = "[" + host + "]"
    port = target.port or (443 if target.scheme.lower() == "https" else 80)
    path = target.path or "/"
    query = ("?" + target.query) if target.query else ""
    return f"{target.scheme.lower()}://{host}:{port}{path}{query}"


def capability_target_matches(claims, requested_target):
    try:
        return (canonical_target(claims.get("targetUrl", "")) == claims.get("targetCanonical")
                and canonical_target(requested_target) == claims.get("targetCanonical"))
    except (TypeError, ValueError):
        return False


def capability_claims(value):
    """Validate a short-lived Platform capability without logging it."""
    try:
        payload, supplied_signature = value.split(".", 1)
        expected = base64.urlsafe_b64encode(
            hmac.new(SIGNING_KEY.encode("utf-8"), payload.encode("ascii"), hashlib.sha256).digest()
        ).decode("ascii").rstrip("=")
        if not SIGNING_KEY or not hmac.compare_digest(expected, supplied_signature):
            return None
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        if int(claims.get("expiresAt", 0)) <= int(time.time() * 1000):
            return None
        if (not claims.get("runId") or not claims.get("nonce") or not claims.get("proxyUrl")
                or not claims.get("targetCanonical") or not claims.get("proxyCanonical")
                or not claims.get("addresses")):
            return None
        if canonical_target(claims.get("targetUrl", "")) != claims.get("targetCanonical"):
            return None
        if canonical_target(claims.get("proxyUrl", "")) != claims.get("proxyCanonical"):
            return None
        return claims
    except (ValueError, TypeError, json.JSONDecodeError, base64.Error):
        return None


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reject_authentication(self):
        # JMeter's HTTP client only retries proxy credentials after a standard
        # challenge.  The capability itself remains in the in-memory
        # Proxy-Authorization value; this response contains no capability.
        self.send_response(407, "Proxy Authentication Required")
        self.send_header("Proxy-Authenticate", 'Basic realm="autotest-proxy"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def do_CONNECT(self):
        self.send_error(501, "CONNECT is not supported by the HTTP fixture")

    def forward(self):
        target = urlsplit(self.path)
        if not target.hostname or target.scheme not in ("http", ""):
            self.send_error(400, "absolute HTTP URL required")
            return
        auth = self.headers.get("Proxy-Authorization", "")
        if not auth.startswith("Basic "):
            self.reject_authentication()
            return
        try:
            user, capability = base64.b64decode(auth[6:]).decode("utf-8").split(":", 1)
        except (ValueError, UnicodeDecodeError, base64.Error):
            self.reject_authentication()
            return
        claims = capability_claims(capability) if user == "__autotest_capability__" else None
        target_port = target.port or 80
        expected_proxies = {canonical_target(value) for value in EXPECTED_PROXY_ENDPOINTS}
        if (not claims or not capability_target_matches(claims, self.path)
                or (expected_proxies and claims.get("proxyCanonical") not in expected_proxies)
                or not consume_nonce(claims.get("nonce"))):
            self.send_error(403, "proxy capability denied")
            return
        address = sorted(set(claims.get("addresses", [])))[0]
        with open("/tmp/f2-01-proxy.log", "a", encoding="utf-8") as log:
            # Deliberately omit capability, query values, and request headers.
            log.write(f"{self.command} host={target.hostname} port={target_port} address={address} authorized=1\n")
        path = target.path or "/"
        if target.query:
            path += "?" + target.query
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        headers = {
            key: value
            for key, value in self.headers.items()
            if key.lower() not in {"proxy-connection", "connection", "host", "proxy-authorization"}
        }
        headers["Host"] = target.netloc
        connection = HTTPConnection(address, target_port, timeout=5)
        try:
            connection.request(self.command, path, body=body, headers=headers)
            response = connection.getresponse()
            response_body = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in {"connection", "transfer-encoding"}:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)
        finally:
            connection.close()

    def log_message(self, *_args):
        return


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8081), ProxyHandler).serve_forever()
