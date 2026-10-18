#!/usr/bin/env python3
"""Fail-closed consistency check for proposed public Oreslang demo evidence.

Validates local evidence only. It does not claim a GitHub Actions run succeeded.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat

SHA = re.compile(r"^[0-9a-f]{40}$")
RUN = re.compile(r"^https://github[.]com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/[1-9][0-9]*$")
STATUS = {"pending_ci", "not_verified", "design_only", "partial_legacy_implementation", "verified"}
PROPERTIES = {"pipelineCode", "mlCode", "httpCode", "selectCode"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_manifest(record, root):
    require(type(record) is dict and record.get("schema_version") == 1,
            "unsupported evidence schema")
    website = record.get("public_site")
    candidate = record.get("candidate")
    require(type(website) is dict and type(candidate) is dict, "missing revisions")
    for desc, value in (
        ("website", website.get("merged_revision")),
        ("baseline compiler", candidate.get("baseline_compiler_revision")),
    ):
        require(type(value) is str and SHA.fullmatch(value), "invalid " + desc + " revision")
    require(candidate.get("not_in_main") is True,
            "experimental branch must not be presented as main compiler support")
    require(type(record.get("promotion_rules")) is list
            and len(record["promotion_rules"]) >= 3,
            "release gate instructions missing")
    examples = record.get("examples")
    require(type(examples) is list and len(examples) == 4, "expected four examples")
    require({x.get("public_property") for x in examples if type(x) is dict} == PROPERTIES,
            "public examples must each be represented once")
    seen = set()
    for entry in examples:
        require(type(entry) is dict, "invalid entry")
        identifier = entry.get("id")
        require(type(identifier) is str and identifier not in seen, "duplicate example ID")
        seen.add(identifier)
        for backend in ("java", "llvm"):
            status = entry.get(backend + "_status")
            require(status in STATUS, "unknown status for " + backend)
            evidence = entry.get(backend + "_evidence")
            if status == "verified":
                require(type(evidence) is dict, "verified example missing immutable evidence")
                for key in ("commit_sha", "fixture_blob_sha1"):
                    require(type(evidence.get(key)) is str and SHA.fullmatch(evidence[key]),
                            "verified evidence requires exact " + key)
                require(type(evidence.get("run_url")) is str and RUN.fullmatch(evidence["run_url"]),
                        "verified evidence needs GitHub Actions run URL")
                require(type(evidence.get("executed_steps")) is int
                        and evidence["executed_steps"] > 0,
                        "verification requires actual executed steps")
                require(evidence["fixture_blob_sha1"] == entry.get("source_blob_sha1"),
                        "evidence fixture identity mismatch")
            else:
                require(evidence is None, "unverified status must not carry success evidence")
        require(entry.get("ci_evidence_url") is None or
                (type(entry["ci_evidence_url"]) is str and RUN.fullmatch(entry["ci_evidence_url"])),
                "invalid CI run URL")
        path = entry.get("source_file")
        if path is None:
            require(entry.get("source_blob_sha1") is None,
                    "blob SHA without source fixture")
            continue
        require(type(path) is str and len(path) <= 180, "invalid fixture path")
        components = Path(path).parts
        require(not Path(path).is_absolute() and len(components) >= 2
                and components[0] == "examples"
                and all(part not in (".", "..") for part in components),
                "fixture path must stay inside examples/")
        location = root.joinpath(*components)
        require(location.is_file() and not location.is_symlink()
                and stat.S_ISREG(location.stat().st_mode),
                "fixture missing or not a regular file")
        source = location.read_bytes()
        require(0 < len(source) <= 102400, "fixture exceeds size bound")
        digest = hashlib.sha1(b"blob " + str(len(source)).encode("ascii")
                              + bytes((0,)) + source).hexdigest()
        require(entry.get("source_blob_sha1") == digest,
                "fixture content differs from pinned Git blob")
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    root = args.root.resolve()
    manifest_path = root / "contracts/conformance/language-motion-v1.json"
    record = json.loads(manifest_path.read_text(encoding="utf-8"))
    check_manifest(record, root)
    print("LANGUAGE_MOTION_EVIDENCE_CONSISTENT: no unearned compiler verification")


if __name__ == "__main__":
    main()
