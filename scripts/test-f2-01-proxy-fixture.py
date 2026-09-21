"""Fast unit regression for the controlled proxy capability boundary."""

import ast
import base64
import hashlib
import hmac
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


source = Path("deployment/fixtures/f2-01-http-proxy.py").read_text(encoding="utf-8")
tree = ast.parse(source)
tree.body = tree.body[:-1]  # do not start the fixture server during this test
namespace = {"__name__": "f2_01_proxy_fixture_test"}
exec(compile(tree, "f2-01-http-proxy.py", "exec"), namespace)

key = "fixture-signing-key"
namespace["SIGNING_KEY"] = key
claims = {
    "runId": "run-1",
    "nonce": "nonce-1",
    "targetUrl": "http://echo-target:8080/health",
    "targetCanonical": "http://echo-target:8080/health",
    "proxyUrl": "http://http-proxy:8081/",
    "proxyCanonical": "http://http-proxy:8081/",
    "expiresAt": 4102444800000,
    "addresses": ["172.31.0.10"],
}
payload = base64.urlsafe_b64encode(json.dumps(claims, separators=(",", ":")).encode()).decode().rstrip("=")
signature = base64.urlsafe_b64encode(
    hmac.new(key.encode(), payload.encode("ascii"), hashlib.sha256).digest()
).decode().rstrip("=")
token = payload + "." + signature

parsed = namespace["capability_claims"](token)
assert parsed["nonce"] == "nonce-1"
assert namespace["capability_target_matches"](parsed, "http://echo-target:8080/health")
assert not namespace["capability_target_matches"](parsed, "http://echo-target:8080/other")
assert namespace["consume_nonce"]("nonce-1")
assert not namespace["consume_nonce"]("nonce-1")
with ThreadPoolExecutor(max_workers=8) as pool:
    consumed = list(pool.map(lambda _: namespace["consume_nonce"]("nonce-2"), range(8)))
assert consumed.count(True) == 1
print("F2-01 proxy capability fixture: PASS")
