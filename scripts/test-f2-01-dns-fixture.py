"""Black-box regression for the isolated F2-01 DNS rebinding fixture."""

import os
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "deployment" / "fixtures" / "f2-01-dns.py"


def query(name: str, qtype: int) -> bytes:
    labels = b"".join(bytes([len(label)]) + label.encode() for label in name.split(".")) + b"\0"
    packet = struct.pack("!HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0) + labels + struct.pack("!HH", qtype, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
        client.settimeout(2)
        client.sendto(packet, ("127.0.0.1", 15353))
        return client.recv(2048)


def answer_addresses(response: bytes, qtype: int) -> list[str]:
    answer_count = struct.unpack("!H", response[6:8])[0]
    offset = 12
    while response[offset] != 0:
        offset += response[offset] + 1
    offset += 5
    result = []
    for _ in range(answer_count):
        offset += 2
        record_type, record_class, _, data_length = struct.unpack("!HHIH", response[offset:offset + 10])
        offset += 10
        data = response[offset:offset + data_length]
        offset += data_length
        if record_type == qtype and record_class == 1:
            if qtype == 1:
                result.append(socket.inet_ntop(socket.AF_INET, data))
            elif qtype == 28:
                result.append(socket.inet_ntop(socket.AF_INET6, data))
    return result


def main() -> int:
    env = os.environ.copy()
    env["F2_DNS_PORT"] = "15353"
    process = subprocess.Popen([sys.executable, str(FIXTURE)], env=env)
    try:
        for _ in range(50):
            try:
                first = answer_addresses(query("rebind-target", 1), 1)
                second = answer_addresses(query("rebind-target", 1), 1)
                rebound = answer_addresses(query("rebind-target", 1), 1)
                rebind_aaaa = answer_addresses(query("rebind-target", 28), 28)
                mixed = answer_addresses(query("mixed-target", 28), 28)
                if first != ["172.31.0.11"]:
                    raise AssertionError(f"unexpected first A answer: {first!r}")
                if second != ["172.31.0.11"]:
                    raise AssertionError(f"unexpected stable A answer: {second!r}")
                if rebound != ["127.0.0.1"]:
                    raise AssertionError(f"unexpected rebound A answer: {rebound!r}")
                if rebind_aaaa != []:
                    raise AssertionError(f"rebind target must not publish AAAA: {rebind_aaaa!r}")
                if mixed != ["ffff::1"]:
                    raise AssertionError(f"unexpected mixed AAAA answer: {mixed!r}")
                return 0
            except (ConnectionRefusedError, ConnectionResetError, TimeoutError, socket.timeout):
                time.sleep(0.1)
        raise AssertionError("DNS fixture did not become ready")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
