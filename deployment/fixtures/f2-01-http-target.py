from http.server import BaseHTTPRequestHandler, HTTPServer
import os
import time


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, status, body=b"ok"):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/ok")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path == "/delay":
            time.sleep(2)
        self._send(200, b'{"ok":true}')

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        if self.path == "/form":
            self._send(200 if b"username=alice" in body else 422)
            return
        if self.path == "/multipart":
            # 同时校验普通字段、文件字段名和文件内容，避免只命中边界/文本字段却漏掉真实上传。
            valid = (
                b"name=\"title\"" in body
                and b"report" in body
                and b'name="attachment"' in body
                and b'filename="report.txt"' in body
                and b"report-content" in body
            )
            self._send(200 if valid else 422)
            return
        if self.path == "/cookie":
            self._send(200 if b"session=abc123" in self.headers.get("Cookie", "").encode() else 401)
            return
        self._send(200, b'{"received":true}')

    def log_message(self, *_args):
        return


HTTPServer(("0.0.0.0", int(os.environ.get("F2_HTTP_TARGET_PORT", "8080"))), Handler).serve_forever()
