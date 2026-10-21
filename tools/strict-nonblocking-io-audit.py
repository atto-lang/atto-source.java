#!/usr/bin/env python3
"""Fail closed while Java stdlib-reachable I/O contains blocking host primitives.

This is intentionally a release gate, not a linter warning. Oreslang stdlib I/O
must use readiness/completion based adapters. Moving a blocking call to a
platform/virtual I/O worker is still non-compliant.
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
    Rule("src/main/java/dev/oreslang/runtime/AsyncNativeIo.java",
         r"Executors\.(?:newThreadPerTaskExecutor|newVirtualThreadPerTaskExecutor)",
         "thread-offloaded blocking I/O fallback is forbidden"),
    Rule("src/main/java/dev/oreslang/runtime/AsyncNativeIo.java",
         r"\bworkers\.execute\s*\(",
         "stdlib I/O must complete from readiness/completion, not worker execution"),
    Rule("src/main/java/dev/oreslang/runtime/AsyncNativeIo.java",
         r"\bwork\.call\s*\(",
         "arbitrary Callable I/O can block a worker thread"),
    Rule("src/main/java/dev/oreslang/runtime/NativeIo.java",
         r"\bFiles\.(?:readString|writeString|list|delete|createDirectories|createTempFile|move)\s*\(",
         "java.nio.file synchronous filesystem operation is stdlib-reachable"),
    Rule("src/main/java/dev/oreslang/runtime/NativeIo.java",
         r"\bclient\.send\s*\(",
         "synchronous HttpClient.send is forbidden"),
    Rule("src/main/java/dev/oreslang/runtime/NativeIo.java",
         r"\bnew\s+Socket\s*\(",
         "java.net.Socket is a blocking socket API"),
    Rule("src/main/java/dev/oreslang/runtime/NativeIo.java",
         r"\bsocket\.connect\s*\(",
         "blocking socket connect is forbidden"),
    Rule("src/main/java/dev/oreslang/runtime/NativeIo.java",
         r"\breader\.readLine\s*\(",
         "blocking socket line read is forbidden"),
    Rule("src/main/java/dev/oreslang/runtime/NativeIo.java",
         r"\bwriter\.(?:write|flush)\s*\(",
         "blocking socket output is forbidden"),
    Rule("src/main/java/dev/oreslang/runtime/NativeHttp.java",
         r"\bHttpServer\.create\s*\(",
         "JDK HttpServer transport is not a strict nonblocking stdlib backend"),
    Rule("src/main/java/dev/oreslang/runtime/NativeHttp.java",
         r"Executors\.newVirtualThreadPerTaskExecutor\s*\(",
         "virtual-thread I/O offload is forbidden by the strict contract"),
    Rule("src/main/java/dev/oreslang/runtime/NativeHttp.java",
         r"getResponseBody\(\)\.(?:write|flush)\s*\(",
         "blocking response stream output is forbidden"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"case\s+\"(?:read_text|write_text|write_text_atomic|append_text|list_dir|is_dir|is_symlink|exists|remove|mkdir_all|connect|get_text|post_text)\"",
         "source-visible bare synchronous I/O member is forbidden"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"\bsocket\.(?:readLine|writeText|close)\s*\(",
         "source-visible synchronous socket operation is forbidden"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.nativeIo\(\)\.submit(?:Close)?\s*\(",
         "stdlib async path still delegates to worker-offloaded blocking I/O"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.input\(\)\.readLine\s*\(",
         "blocking stdin read is forbidden"),
    Rule("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",
         r"context\.output\(\)\.(?:print|println|flush)\s*\(",
         "blocking stdout/terminal writes are forbidden"),
    Rule("src/main/java/dev/oreslang/runtime/OresContext.java",
         r"new\s+InputStreamReader\s*\(\s*env\.in\(\)",
         "blocking stdin adapter is stdlib-reachable"),
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
