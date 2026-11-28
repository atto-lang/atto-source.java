#!/usr/bin/env python3
"""Fail-closed checker for bounded Java FSM *host* trace witness, not guest execution.

The separate peer oracle is supplied from oreslang-common-source by CI and
checked against its Git blob SHA before this program executes.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

COUNT = 2040
ENTITY_COUNT = 5
EVENTS = (0, 1, 1, 2, 3, 4, 0, 1, 2, 2, 3, 99)
FIELDS = 12
MAX_BYTES = 512_000
INTEGER = re.compile(r"(?:0|[1-9][0-9]*)\Z")
REASONS = {"none", "invalid-state", "invalid-ticks",
           "version-exhausted", "guard", "unhandled"}


def require(value, message):
    if not value:
        raise ValueError(message)


def oracle_module(path):
    require(path.is_file(), "missing independent shared oracle")
    spec = importlib.util.spec_from_file_location("fsm_trace_peer", path)
    require(spec is not None and spec.loader is not None, "unloadable oracle")
    oracle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(oracle)
    require(oracle.CONTRACT == "ores-fsm-trace-v0", "unsupported contract")
    return oracle


def parse(raw, oracle):
    require(type(raw) is bytes and len(raw) <= MAX_BYTES,
            "invalid or oversized Java trace")
    try:
        data = raw.decode("utf-8", errors="strict")
    except UnicodeError as exc:
        raise ValueError("invalid UTF-8 Java trace") from exc
    lines = data.splitlines()
    require(len(lines) == COUNT and data.endswith("\n"),
            "missing, additional or unterminated Java trace lines")
    entries = []
    for seq, line in enumerate(lines):
        fields = line.split(",")
        require(len(fields) == FIELDS, "incorrect column count")
        for column in (0, 1, 2, 5, 6, 7, 8, 9, 10, 11):
            require(INTEGER.fullmatch(fields[column]) is not None,
                    "invalid decimal integer")
        numbers = [int(x) if i in (0, 1, 2, 5, 6, 7, 8, 9, 10, 11)
                   else x for i, x in enumerate(fields)]
        entity = seq % ENTITY_COUNT
        event = EVENTS[(seq // ENTITY_COUNT) % len(EVENTS)]
        require(numbers[0] == seq and numbers[1] == entity
                and numbers[2] == event, "unexpected event order")
        require(numbers[3] in ("true", "false") and numbers[4] in REASONS,
                "invalid acceptance flag or reason")
        effect = numbers[11]
        require(effect <= 4, "invalid effect ID")
        entries.append({
            "seq": seq,
            "entity": entity,
            "event": event,
            "accepted": numbers[3] == "true",
            "reason": numbers[4],
            "before": dict(zip(("state", "version", "ticks"), numbers[5:8])),
            "after": dict(zip(("state", "version", "ticks"), numbers[8:11])),
            "effects": [effect] if effect else [],
        })
    trace = {"contract": oracle.CONTRACT, "machine": "flat-fixture-v0",
             "entity_count": ENTITY_COUNT, "transitions": entries}
    require(oracle.validate(trace) is True,
            "shared trace semantic verification failed")
    digest = hashlib.sha256(json.dumps(trace, sort_keys=True,
                       separators=(",", ":")).encode("utf-8")).hexdigest()
    return {"host": "java21-reference-only", "contract": oracle.CONTRACT,
            "events": len(entries), "entities": ENTITY_COUNT,
            "canonical_trace_sha256": digest,
            "oracle_sha256": hashlib.sha256(
                Path(oracle.__file__).read_bytes()).hexdigest()}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--oracle", required=True, type=Path)
    a = p.parse_args()
    try:
        oracle = oracle_module(a.oracle.resolve())
        report = parse(sys.stdin.buffer.read(MAX_BYTES + 1), oracle)
    except (ValueError, OSError, AttributeError, TypeError, KeyError) as exc:
        print("FSM_JAVA_TRACE_DENIED: " + str(exc), file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
