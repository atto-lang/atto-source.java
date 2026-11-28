#!/usr/bin/env python3
"""Validate one raw guest fork. No scoring or host computation of guest work."""
import json
import re
import sys

def parse_fork(raw, expected):
    lines = raw.splitlines()
    if len(lines) != 19 or any(not re.fullmatch(r"[0-9]{1,19}", line) for line in lines):
        raise ValueError("expected 5 warmup checksums and 7 checksum/time pairs")
    values = [int(line) for line in lines]
    if any(value != expected for value in values[:5]):
        raise ValueError("warmup checksum mismatch")
    samples = []
    for checksum, elapsed in zip(values[5::2], values[6::2]):
        if checksum != expected or not 0 < elapsed <= 120_000_000_000:
            raise ValueError("bad checksum or monotonic duration")
        samples.append(elapsed)
    return {"iterations": 1000, "warmups": 5, "checksum": expected, "elapsed_ns": samples}

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: parse-fork.py EXPECTED RAW_FILE")
    with open(sys.argv[2], encoding="utf-8") as raw:
        print(json.dumps(parse_fork(raw.read(), int(sys.argv[1])), sort_keys=True))
