#!/usr/bin/env python3
"""Compile and exercise the actual host-topology sources without shell eval.

This is a JDK-only component gate. It cannot prove native-carrier JNI
behavior, compiler integration, actor concurrency or hardware counters.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [
    "src/main/java/dev/oreslang/runtime/CpuTopology.java",
    "src/main/java/dev/oreslang/runtime/ExecutionPlacement.java",
    "src/test/java/dev/oreslang/runtime/RegionLocalitySmoke.java",
]

def main():
    with tempfile.TemporaryDirectory(prefix="oreslang-region-locality-") as temp:
        subprocess.run([
            "javac", "--release", "21", "-encoding", "UTF-8",
            "-Xlint:all", "-Werror", "-d", temp,
            *[str(ROOT / path) for path in SOURCES]
        ], check=True, cwd=ROOT)
        subprocess.run([
            "java", "-ea", "-cp", temp,
            "dev.oreslang.runtime.RegionLocalitySmoke",
        ], check=True, cwd=ROOT)

if __name__ == "__main__":
    main()
