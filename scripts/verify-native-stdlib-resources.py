#!/usr/bin/env python3
"""Fail closed when Native Image stdlib resources and the Java loader drift.

Native Image includes resources at image build time, not at runtime. Keep the
image manifest and CoreLibrarySources.PACKAGED_SOURCES in exact agreement.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "src/main/resources/META-INF/native-image/dev.oreslang/oreslang-source/resource-config.json"
LOADER = ROOT / "src/main/java/dev/oreslang/stdlib/CoreLibrarySources.java"
SOURCES = ROOT / "stdlib"

def verify() -> None:
    manifest = json.loads(CONFIG.read_text(encoding="utf-8"))
    if set(manifest) != {"resources"}:
        raise AssertionError("Native Image stdlib manifest must contain only resources")
    resources = manifest["resources"]
    if set(resources) != {"includes"} or len(resources["includes"]) != 1:
        raise AssertionError("expected exactly one Native Image stdlib include pattern")
    entry = resources["includes"][0]
    if set(entry) != {"pattern"} or not isinstance(entry["pattern"], str):
        raise AssertionError("invalid Native Image stdlib resource pattern")

    source = LOADER.read_text(encoding="utf-8")
    declaration = re.search(r"PACKAGED_SOURCES\\s*=\\s*Set\\.of\\(([^)]*)\\)", source)
    if declaration is None:
        raise AssertionError("cannot identify the closed core-library registry")
    permitted = re.findall(r'"([^"]+)"', declaration.group(1))
    if not permitted or len(permitted) != len(set(permitted)):
        raise AssertionError("invalid or duplicate PACKAGED_SOURCES")
    if any(not re.fullmatch(r"[A-Za-z0-9_.-]+\\.ores", name) for name in permitted):
        raise AssertionError("malformed stdlib registry filename")

    actual = {path.name for path in SOURCES.glob("*.ores")}
    if actual != set(permitted):
        raise AssertionError(f"stdlib registry / source file mismatch: {actual ^ set(permitted)}")

    pattern = re.compile(entry["pattern"])
    for name in permitted:
        if pattern.fullmatch("dev/oreslang/std/" + name) is None:
            raise AssertionError(f"approved stdlib source omitted from image: {name}")
    for invalid in (
        "dev/oreslang/std/unknown.ores",
        "dev/oreslang/std/core.ores.bak",
        "dev/oreslang/std/../core.ores",
        "dev/oreslang/std/core.ores/evil",
        "dev/oreslang/std/subdir/core.ores",
        "other/dev/oreslang/std/core.ores",
    ):
        if pattern.fullmatch(invalid) is not None:
            raise AssertionError(f"unapproved resource admitted: {invalid}")

    print(f"PASS Native Image stdlib manifest: {len(permitted)} closed source resources")

if __name__ == "__main__":
    verify()
