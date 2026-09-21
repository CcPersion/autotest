"""Tiny deterministic DNS server used only by the isolated F2-01 proof."""

import os
import socket
import struct
from collections import defaultdict


PORT = int(os.environ.get("F2_DNS_PORT", "53"))
LOG_PATH = os.environ.get("F2_DNS_LOG", "")
COUNTS = defaultdict(int)


def read_question(packet: bytes):
    request_id = packet[:2]
    offset = 12
    labels = []
    while packet[offset] != 0:
        size = packet[offset]
        offset += 1
        labels.append(packet[offset:offset + size].decode("ascii"))
        offset += size
    name = ".".join(labels).lower()
    question_end = offset + 5
    qtype, qclass = struct.unpack("!HH", packet[offset + 1:question_end])
    return request_id, name, qtype, qclass, packet[12:question_end]


def records(name: str, qtype: int):
    if qtype == 1:
        if name == "rebind-target":
            COUNTS[(name, qtype)] += 1
            return ["172.31.0.11"] if COUNTS[(name, qtype)] <= 2 else ["127.0.0.1"]
        if name == "mixed-target":
            return ["172.31.0.11"]
    if qtype == 28:
        if name == "mixed-target":
            return ["ffff::1"]
        if name == "rebind-target":
            # Keep rebinding as a two-resolution A-only scenario.  Returning
            # an unrelated AAAA address here would make the first resolution
            # fail closed before the redirect hop can exercise rebinding.
            return []
    return []


def record(name: str, qtype: int, values):
    if LOG_PATH:
        with open(LOG_PATH, "a", encoding="utf-8") as log:
            log.write(f"query name={name} type={qtype} answers={','.join(values)}\n")


def response(packet: bytes) -> bytes:
    request_id, name, qtype, qclass, question = read_question(packet)
    if name not in {"rebind-target", "mixed-target"}:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as upstream:
            upstream.settimeout(1)
            upstream.sendto(packet, ("127.0.0.11", 53))
            return upstream.recv(4096)
    values = records(name, qtype) if qclass == 1 else []
    record(name, qtype, values)
    answers = []
    for value in values:
        family = socket.AF_INET if qtype == 1 else socket.AF_INET6
        raw = socket.inet_pton(family, value)
        answers.append(b"\xc0\x0c" + struct.pack("!HHIH", qtype, 1, 5, len(raw)) + raw)
    header = request_id + struct.pack("!HHHHH", 0x8180, 1, len(answers), 0, 0)
    return header + question + b"".join(answers)


with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
    server.bind(("0.0.0.0", PORT))
    while True:
        payload, address = server.recvfrom(4096)
        try:
            server.sendto(response(payload), address)
        except (IndexError, ValueError, OSError):
            # Malformed probes get no answer and are never logged or proxied.
            continue
