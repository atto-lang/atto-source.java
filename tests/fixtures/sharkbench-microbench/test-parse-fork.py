#!/usr/bin/env python3
"""Source-free receipt parser controls; no guest execution claim."""
import pathlib
import runpy

parse = runpy.run_path(str(pathlib.Path(__file__).with_name("parse-fork.py")))["parse_fork"]
lines = ["22000"] * 5 + ["22000", "100"] * 7
raw = "\n".join(lines) + "\n"
assert parse(raw, 22000) == {"iterations": 1000, "warmups": 5, "checksum": 22000, "elapsed_ns": [100] * 7}
cases = [lines[:-1], lines + ["1"], [], ["bad"] + lines[1:]]
for index, value in [(0, "21999"), (4, "0"), (5, "21999"), (6, "0"), (6, "-1"),
                     (6, "120000000001"), (6, "1.5"), (6, " 1"), (6, "١"),
                     (6, "1" * 20), (17, "1"), (18, "0")]:
    copy = lines[:]
    copy[index] = value
    cases.append(copy)
for case in cases:
    try:
        parse("\n".join(case) + "\n", 22000)
    except ValueError:
        pass
    else:
        raise AssertionError(case)
print(f"EXACT_RECEIPT_PARSER_CASES={1 + len(cases)}")
