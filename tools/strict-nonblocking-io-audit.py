#!/usr/bin/env python3
"""Fail closed while Java stdlib-reachable I/O contains blocking host primitives.

This is intentionally a release gate, not a linter warning. Oreslang stdlib I/O
must use readiness/completion based adapters. Moving a blocking call to a
platform/virtual I/O worker is still non-compliant. Legacy blocking host adapters
may temporarily remain in the runtime only while they are unreachable from the
stdlib surface; this audit rejects those reachability edges.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

@dataclass(frozen=True)
class Rule:
    path: str
    pattern: str
    reason: str

RULES = (
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.nativeIo\(\)\.submit(?:Close)?\s*\(",
         "stdlib must not delegate I/O to worker-offloaded blocking adapters"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"\bNativeIo\.(?:readText|writeText|writeTextAtomic|appendText|exists|listDir|isDir|isSymlink|remove|createDirectories|connect|httpGetText|httpPostText)\s*\(",
         "stdlib must not call legacy blocking NativeIo operations"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"\bsocket\.(?:readLine|writeText|close)\s*\(",
         "stdlib must not call blocking legacy socket methods"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.input\(\)\.readLine\s*\(",
         "stdlib must not perform blocking stdin reads"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.output\(\)\.(?:print|println|flush)\s*\(",
         "stdlib must not perform blocking stdout/terminal writes"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.http\(\)",
         "strict stdlib must not reach the legacy NativeHttp transport at all"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"\bserver\.(?:accept|shutdown)\s*\(",
         "stdlib must not drive legacy blocking HTTP server transport"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"\bexchange\.(?:bodyText|readChunk|writeBytes|respondBytes|respond|start|write|finish|abort)\s*\(",
         "stdlib must not drive legacy blocking HTTP exchange transport"),
    Rule("src/main/java/dev/oreslang/types/TypeChecker.java",
         r'case\s+"(?:read_text|write_text|write_text_atomic|append_text|list_dir|is_dir|is_symlink|exists|remove|mkdir_all|listen|get_text|post_text)"\s*(?:,|:|->)',
         "typechecker must not advertise bare synchronous stdlib I/O names"),
    Rule("src/main/java/dev/oreslang/types/TypeChecker.java",
         r'member\(\)\.equals\("(?:print|println|read_line|write|log|logList)"\)',
         "typechecker must not advertise bare synchronous stdio members"),
    Rule("src/main/java/dev/oreslang/types/TypeChecker.java",
         r'name\.name\(\)\.equals\("print"\)',
         "global print must not remain an ambient blocking I/O builtin"),
)

def strip_line_comment(line: str) -> str:
    # Conservative only: matching inside block comments may produce a false
    # positive, which is preferable to silently admitting a blocking API.
    return line.split("//", 1)[0]

def main() -> int:
    violations: list[tuple[Rule, int, str]] = []
    for rule in RULES:
        path = ROOT / rule.path
        if not path.is_file():
            print(f"DENIED: required audit target missing: {rule.path}", file=sys.stderr)
            return 2
        rx = re.compile(rule.pattern)
        for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            code = strip_line_comment(raw)
            if rx.search(code):
                violations.append((rule, line_no, raw.strip()))

    if violations:
        print("STRICT_NONBLOCKING_STDLIB_IO=DENIED", file=sys.stderr)
        for rule, line_no, source in violations:
            print(f"{rule.path}:{line_no}: {rule.reason}: {source}", file=sys.stderr)
        print(f"blocking_stdlib_io_violations={len(violations)}", file=sys.stderr)
        return 1

    print("STRICT_NONBLOCKING_STDLIB_IO=PASS")
    print("blocking_stdlib_io_violations=0")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
