#!/usr/bin/env python3
"""Experimental, conservative Oreslang lexical concurrency lint.

Warnings are NOT sound proofs: this is a lexer-based first stage pending typed
AST/CFG and interprocedural effects. String/comment content is never interpreted
as code. Future analyses belong in the compiler's typed IR.
"""
from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

# Ordered alternatives ensure strings/comments are skipped atomically.
TOKEN = re.compile(r"(?P<space>\s+)|(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)|"
                   r"(?P<string>\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|"
                   r"`(?:\\.|[^`\\])*`)|(?P<ident>[A-Za-z_][\w]*)|"
                   r"(?P<other>.)")
LOOPS = {"for", "while", "loop"}


@dataclass(frozen=True)
class Token:
    value: str
    line: int
    column: int


@dataclass(frozen=True)
class Diagnostic:
    path: str
    line: int
    column: int
    code: str
    severity: str
    message: str


def lex(source: str) -> list[Token]:
    tokens = []
    line = column = 1
    for match in TOKEN.finditer(source):
        value, kind = match.group(), match.lastgroup
        if kind not in {"space", "comment", "string"}:
            tokens.append(Token(value, line, column))
        lines = value.count("\n")
        if lines:
            line += lines
            column = len(value) - value.rfind("\n")
        else:
            column += len(value)
    return tokens


def paired_braces(tokens: list[Token]) -> dict[int, int]:
    stack = []
    pairs = {}
    for i, token in enumerate(tokens):
        if token.value == "{":
            stack.append(i)
        elif token.value == "}" and stack:
            start = stack.pop()
            pairs[start] = i
    return pairs


def find_header(tokens: list[Token], brace: int) -> list[str]:
    # Skip over balanced parameter/condition delimiters backwards.
    i, depth = brace - 1, 0
    while i >= 0:
        value = tokens[i].value
        if value in {"}", ";", "{"} and depth == 0:
            break
        if value in {")", "]"}:
            depth += 1
        elif value in {"(", "["}:
            depth = max(0, depth - 1)
        i -= 1
    return [t.value for t in tokens[i + 1:brace]]


def lint(source: str, path: str = "<input>", pedantic: bool = False) -> list[Diagnostic]:
    tokens = lex(source)
    braces = paired_braces(tokens)
    # Nesting tracks loop-ness and known async function context; None means unknown.
    scopes: list[tuple[bool, bool | None, int]] = []
    result = []

    def report(tok: Token, code: str, severity: str, message: str):
        result.append(Diagnostic(path, tok.line, tok.column, code, severity, message))

    for i, tok in enumerate(tokens):
        value = tok.value
        if value == "{":
            header = find_header(tokens, i)
            is_loop = bool(set(header) & LOOPS)
            fn_decl = "fnc" in header or "routine" in header
            async_ctx = ("async" in header) if fn_decl else (
                scopes[-1][1] if scopes else None)
            scopes.append((is_loop, async_ctx, i))
            # Only warn for a recognized select block, not select in a string.
            if pedantic and "select" in header and i in braces:
                contents = {t.value for t in tokens[i + 1:braces[i]]}
                if "default" not in contents and "timeout" not in contents:
                    report(tok, "ORES103", "info",
                           "select has no syntactic default/timeout; verify blocking is intentional")
            if pedantic and "match" in header and i in braces:
                contents = {t.value for t in tokens[i + 1:braces[i]]}
                if "_" not in contents and not {"true", "false"} <= contents:
                    report(tok, "ORES104", "info",
                           "match exhaustiveness is not established by lexical lint; use typed checking")
        elif value == "}":
            if scopes:
                scopes.pop()
        elif value == "defer" and any(scope[0] for scope in scopes):
            report(tok, "ORES101", "warning",
                   "defer occurs in a loop; review its cleanup lifetime and repeated registration")
        elif value == "await" and scopes and scopes[-1][1] is False:
            report(tok, "ORES102", "warning",
                   "await occurs inside an apparently non-async function")
    return result


def paths_from(arguments: list[str]):
    found = []
    for raw in arguments:
        p = Path(raw)
        if p.is_dir():
            found.extend(sorted(p.rglob("*.ores")))
        elif p.is_file():
            found.append(p)
        else:
            raise FileNotFoundError(raw)
    return sorted(set(found))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", help="Oreslang files or directories")
    parser.add_argument("--format", choices=["text", "json"], default="text")
    parser.add_argument("--pedantic", action="store_true")
    parser.add_argument("--strict", action="store_true", help="nonzero exit on warnings")
    args = parser.parse_args()
    issues = []
    for path in paths_from(args.paths):
        issues.extend(lint(path.read_text(encoding="utf-8"), str(path), args.pedantic))
    if args.format == "json":
        print(json.dumps([asdict(x) for x in issues], indent=2))
    else:
        for x in issues:
            print(f"{x.path}:{x.line}:{x.column}: {x.severity} {x.code}: {x.message}")
        print(f"Oreslang lint: {len(issues)} advisory finding(s)")
    return int(args.strict and any(x.severity == "warning" for x in issues))


if __name__ == "__main__":
    raise SystemExit(main())
