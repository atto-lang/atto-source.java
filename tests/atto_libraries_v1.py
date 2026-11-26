#!/usr/bin/env python3
"""core/alloc/std library guests (atto-spec core-alloc-std-libraries-v1).

Runs every tests/atto_libraries/*.atto through a launcher that executes the
program and exits with main's return value. The corpus is shared with the
other atto-source.* compilers. Headers:
  // flags: ...                 extra launcher flags
  // expect-exit: N             main's return value
  // expect-stdout-json: "..."  exact stdout
  // expect-reject              the program must be rejected
  // expect-error: s            rejection diagnostic must contain s
  // expect-error-<backend>: s  backend-specific diagnostic
usage: atto_libraries_v1.py --backend java --launcher path/to/launcher
"""
import argparse
import json
import pathlib
import shlex
import subprocess
import sys


REQUIRED_CASES = frozenset(["core_num.atto","core_cmp.atto","core_option_result.atto","core_mem.atto","alloc_arena.atto","std_io.atto","std_path_posix.atto","std_path_windows.atto","aliases.atto","neg_unknown_core.atto","neg_unknown_std.atto","neg_traversal.atto","neg_nested.atto","neg_private.atto","neg_dotted_selector.atto","neg_ores_selector.atto"])


def validate_corpus(cases):
    missing = REQUIRED_CASES - {case.name for case in cases}
    if missing:
        raise ValueError("missing required library cases: " + ", ".join(sorted(missing)))


def check_rejection(run, errors):
    output = run.stderr + run.stdout
    return run.returncode > 0 and bool(output.strip()) and all(e in output for e in errors)


def headers(path, backend):
    meta = {"flags": [], "errors": [], "reject": False}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("//"):
            break
        body = line[2:].strip()
        if body == "expect-reject":
            meta["reject"] = True
        elif body.startswith("flags:"):
            meta["flags"] = shlex.split(body[6:])
        elif body.startswith("expect-exit:"):
            meta["exit"] = int(body[12:])
        elif body.startswith("expect-stdout-json:"):
            meta["stdout"] = json.loads(body[19:])
        elif body.startswith("expect-error:"):
            meta["errors"].append(body[13:].strip())
        elif body.startswith(f"expect-error-{backend}:"):
            meta["errors"].append(body[len(backend) + 14:].strip())
    return meta


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", required=True)
    parser.add_argument("--launcher", required=True)
    args = parser.parse_args()
    launcher = shlex.split(args.launcher)
    corpus = pathlib.Path(__file__).resolve().parent / "atto_libraries"
    cases = sorted(corpus.glob("*.atto"))
    validate_corpus(cases)
    failures = []
    for case in cases:
        meta = headers(case, args.backend)
        if not meta["reject"] and "exit" not in meta:
            failures.append(f"{case.name}: missing explicit expected exit")
            continue
        run = subprocess.run([*launcher, *meta["flags"], case.name], cwd=corpus,
                             capture_output=True, text=True, timeout=300)
        if meta["reject"]:
            missing = [e for e in meta["errors"] if e not in run.stderr + run.stdout]
            if not check_rejection(run, meta["errors"]):
                failures.append(f"{case.name}: expected rejection {missing}, rc={run.returncode} "
                                f"{run.stderr.strip()[-400:]}")
            else:
                print(f"PASS {case.name} (rejected)")
            continue
        if run.returncode != meta.get("exit", 0):
            failures.append(f"{case.name}: exit {run.returncode} != {meta.get('exit', 0)}: "
                            f"{run.stderr.strip()[-600:]}")
        elif "stdout" in meta and run.stdout != meta["stdout"]:
            failures.append(f"{case.name}: stdout {run.stdout!r} != {meta['stdout']!r}")
        else:
            print(f"PASS {case.name} (exit {run.returncode})")
    for failure in failures:
        print("FAIL " + failure)
    print(f"ATTO_LIBRARIES_V1 {len(cases) - len(failures)}/{len(cases)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
