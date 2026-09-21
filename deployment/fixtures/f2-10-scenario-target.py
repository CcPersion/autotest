"""F2-10 复杂场景的受控 HTTP 目标，仅用于本地验收覆盖。"""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse


EXPECTED_USERNAME = os.environ.get("SCENARIO_TARGET_USERNAME", "scenario-user")
EXPECTED_PASSWORD = os.environ.get("SCENARIO_TARGET_PASSWORD", "scenario-password")
TOKEN = os.environ.get("SCENARIO_TARGET_TOKEN", "scenario-token")


class Handler(BaseHTTPRequestHandler):
    server_version = "AutotestScenarioTarget/1.0"

    def log_message(self, format, *args):
        # 目标服务不输出请求参数，避免测试令牌进入容器日志。
        return

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path)
        if path.path == "/health":
            self.send_json(200, {"status": "UP"})
            return
        if path.path == "/business":
            token = parse_qs(path.query).get("token", [""])[0]
            if token != TOKEN:
                self.send_json(401, {"code": "INVALID_TOKEN"})
                return
            self.send_json(200, {"status": "ready", "business": "order-created"})
            return
        self.send_json(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        if urlparse(self.path).path != "/login":
            self.send_json(404, {"code": "NOT_FOUND"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self.send_json(400, {"code": "INVALID_JSON"})
            return
        if request.get("username") != EXPECTED_USERNAME or request.get("password") != EXPECTED_PASSWORD:
            self.send_json(401, {"code": "INVALID_CREDENTIALS"})
            return
        self.send_json(200, {"token": TOKEN, "user": EXPECTED_USERNAME})


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()
