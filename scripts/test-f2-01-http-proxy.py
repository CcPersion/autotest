"""Black-box regression for the F2-01 controlled proxy challenge."""

import http.client
import os
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "deployment" / "fixtures" / "f2-01-http-proxy.py"


def main() -> int:
    env = os.environ.copy()
    env["F2_PROXY_SIGNING_KEY"] = "test-signing-key"
    process = subprocess.Popen([sys.executable, str(FIXTURE)], env=env)
    try:
        for _ in range(50):
            try:
                connection = http.client.HTTPConnection("127.0.0.1", 8081, timeout=1)
                connection.request("GET", "http://target.example:8080/health")
                response = connection.getresponse()
                challenge = response.getheader("Proxy-Authenticate")
                response.read()
                connection.close()
                if response.status != 407:
                    raise AssertionError(f"expected 407, got {response.status}")
                if challenge != 'Basic realm="autotest-proxy"':
                    raise AssertionError(f"missing deterministic proxy challenge: {challenge!r}")
                return 0
            except (ConnectionRefusedError, OSError):
                time.sleep(0.1)
        raise AssertionError("proxy fixture did not become ready")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
