#!/usr/bin/env python3
"""Fail-closed differential gate for compiled Java/GraalVM and LLVM actor guests.

Consume actual stdout from the Java guest and C runtime admission trace from
the compiled LLVM guest. Never reconstruct observations from source text.
"""
import argparse
import json
import pathlib
import re
import sys

JAVA_HOPS = (
    ("forward", "Source", 5),
    ("forward", "Transform", 10),
    ("backward", "Sink", 13),
    ("backward", "Transform", 14),
)
LLVM_ADMISSIONS = (
    (0, 0, 0, 5),
    (1, 1, 0, 5),
    (2, 1, 0, 10),
    (1, 2, 1, 13),
    (0, 2, 1, 14),
)
JAVA_EVENT = re.compile(r"PIPE_(FORWARD|BACKWARD)_(SOURCE|TRANSFORM|SINK)_(-?[0-9]+)\\|")
LLVM_EVENT = re.compile(r"^SOURCE_PIPELINE_ADMIT target=([0-9]+) direction=([0-9]+) variant=([0-9]+) value=(-?[0-9]+)$", re.M)
LLVM_SUMMARY = "SOURCE_PIPELINE_ROUNDTRIP_PASS actors=3 typed=1 forward=2 backward=2 result=19"
JAVA_FINAL = "JAVA_PIPELINE_RESULT_19"
HEAD = re.compile(r"^[a-f0-9]{40}$")


def compare(java_output: str, llvm_error: str, java_head: str, llvm_head: str) -> dict:
    if not (HEAD.fullmatch(java_head) and HEAD.fullmatch(llvm_head)):
        raise ValueError("missing exact source revision")
    java_events = tuple((direction.lower(), actor.title(), int(value))
        for direction, actor, value in JAVA_EVENT.findall(java_output))
    if java_events != JAVA_HOPS:
        raise ValueError("Java guest observed hop trace mismatch: " + repr(java_events))
    java_expected = "".join(
        f"PIPE_{direction.upper()}_{actor.upper()}_{value}|"
        for direction, actor, value in JAVA_HOPS) + JAVA_FINAL
    if java_output != java_expected:
        raise ValueError("unexpected extra, missing or forged Java guest output")
    llvm_events = tuple(tuple(int(value) for value in row)
        for row in LLVM_EVENT.findall(llvm_error))
    if llvm_events != LLVM_ADMISSIONS:
        raise ValueError("LLVM detached-frame DATA admissions mismatch: " + repr(llvm_events))
    if llvm_error.count(LLVM_SUMMARY) != 1 or "SOURCE_PIPELINE_FAILURE" in llvm_error:
        raise ValueError("LLVM missing terminal completion or unexpected failure")
    finalizers = re.findall(
        r"SOURCE_ACTOR_SPAWN_STOP_VERIFIED [^\\n]*processed=([0-9]+) last=(-?[0-9]+)",
        llvm_error)
    if sorted(int(row[0]) for row in finalizers) != [1, 2, 2]:
        raise ValueError("LLVM actor turn ownership/finalizer counts mismatch: " + repr(finalizers))
    return {
        "schema_version": "1.0",
        "source_revisions": {"java": java_head, "llvm": llvm_head},
        "three_actor_flow": {
            "input": 5,
            "forward": 2,
            "backward": 2,
            "result": 19,
            "java_guest_hops": [
                {"direction": direction, "actor": actor, "value": value}
                for direction, actor, value in java_events],
            "llvm_data_admissions": [
                {"target_index": target, "direction": direction, "variant_index": variant, "value": value}
                for target, direction, variant, value in llvm_events],
            "llvm_finalized_turns": sorted(int(row[0]) for row in finalizers),
        },
        "status": "passed",
    }


def self_test() -> None:
    java = "".join(f"PIPE_{d.upper()}_{a.upper()}_{v}|" for d, a, v in JAVA_HOPS) + JAVA_FINAL
    llvm = "".join(
        f"SOURCE_PIPELINE_ADMIT target={t} direction={d} variant={variant} value={v}\\n"
        for t, d, variant, v in LLVM_ADMISSIONS)
    llvm += LLVM_SUMMARY + "\\n"
    llvm += "".join(
        f"SOURCE_ACTOR_SPAWN_STOP_VERIFIED constructor_on_worker=1 released=1 processed={n} last=19\\n"
        for n in (1, 2, 2))
    sha = "a" * 40
    assert compare(java, llvm, sha, sha)["status"] == "passed"
    mutants = [
        (java.replace("_10|", "_11|"), llvm),
        (java.replace("PIPE_BACKWARD_SINK_13|", ""), llvm),
        (java.replace("PIPE_FORWARD_SOURCE_5|", "PIPE_FORWARD_SOURCE_5|PIPE_FORWARD_SOURCE_5|"), llvm),
        (java, llvm.replace("target=2 direction=1", "target=0 direction=1")),
        (java, llvm.replace("value=14", "value=15")),
        (java, llvm.replace("forward=2 backward=2", "forward=1 backward=2")),
        (java, llvm.replace("processed=2", "processed=1")),
    ]
    for index, (j, n) in enumerate(mutants):
        try:
            compare(j, n, sha, sha)
        except ValueError:
            continue
        raise AssertionError(f"unsafe differential mutant {index} escaped detection")
    print("PIPELINE_DIFFERENTIAL_MUTANTS_REJECTED=7")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--java-out", type=pathlib.Path)
    parser.add_argument("--llvm-err", type=pathlib.Path)
    parser.add_argument("--java-head")
    parser.add_argument("--llvm-head")
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not all((args.java_out, args.llvm_err, args.java_head, args.llvm_head, args.report)):
        parser.error("real replay requires guest logs, exact source SHAs, and report destination")
    report = compare(
        args.java_out.read_text(), args.llvm_err.read_text(),
        args.java_head, args.llvm_head)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\\n")
    print("PIPELINE_JAVA_LLVM_DIFFERENTIAL_PASS actors=3 forward=2 backward=2 result=19")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, AssertionError, OSError) as error:
        print("PIPELINE_DIFFERENTIAL_REJECTED: " + str(error), file=sys.stderr)
        sys.exit(1)
