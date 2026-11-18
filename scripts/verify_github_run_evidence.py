#!/usr/bin/env python3
"""Fail closed on GitHub Actions metadata that does not prove stepful exact-head CI.

This is an offline evidence validator, NOT a substitute for protected required
checks, independent review, or exact-tree provenance for private CI exports.
Supply a GitHub workflow-run record and its latest-attempt jobs in one JSON
object: {"run": {...}, "jobs": [{...}]}. Do not supply caller-authored
success records as trusted evidence.
"""
import argparse
import json
from pathlib import Path
import re
import sys

SHA = re.compile(r"[0-9a-f]{40}\Z")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z", re.ASCII)
MAX_EVIDENCE_BYTES = 1024 * 1024


class EvidenceError(ValueError):
    pass


def positive_int(value):
    return type(value) is int and value > 0


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def check(evidence, *, expected_repo, expected_repo_id, expected_sha, required):
    """Validate a complete success proof for each explicitly named job/step.

    'required' maps unique job names to nonempty sets of exact step names.
    All required jobs/steps must be actually executed and successful.
    """
    require(isinstance(expected_repo, str) and REPO.fullmatch(expected_repo),
            "invalid expected repository")
    require(positive_int(expected_repo_id), "invalid expected repository ID")
    require(isinstance(expected_sha, str) and SHA.fullmatch(expected_sha),
            "invalid expected SHA")
    require(isinstance(required, dict) and required, "required jobs are missing")
    for job_name, steps in required.items():
        require(isinstance(job_name, str) and job_name.strip() == job_name and job_name,
                "invalid required job name")
        require(isinstance(steps, (set, frozenset)) and bool(steps)
                and all(isinstance(s, str) and bool(s) and s.strip() == s for s in steps),
                "each required job needs named test/verification steps")

    require(isinstance(evidence, dict), "evidence is not an object")
    run = evidence.get("run")
    jobs = evidence.get("jobs")
    require(isinstance(run, dict) and isinstance(jobs, list),
            "run metadata or jobs missing")
    source = run.get("repository")
    require(isinstance(source, dict), "run repository identity missing")
    require(source.get("full_name") == expected_repo
            and type(source.get("id")) is int
            and source["id"] == expected_repo_id,
            "run repository name/ID mismatch")
    require(run.get("head_sha") == expected_sha, "run is not exact expected head SHA")
    require(run.get("event") == "pull_request", "run is not PR-head evidence")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "workflow run did not succeed")
    require(positive_int(run.get("id")) and positive_int(run.get("run_attempt")),
            "run identity/attempt missing")
    require(positive_int(run.get("workflow_id")), "workflow identity missing")

    seen_jobs = set()
    for job in jobs:
        require(isinstance(job, dict), "job entry is not an object")
        name = job.get("name")
        require(isinstance(name, str) and name, "job name missing")
        require(name not in seen_jobs, "duplicate job name: " + name)
        seen_jobs.add(name)
        if name not in required:
            continue
        require(job.get("run_id") == run["id"], "job is from another workflow run")
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                "required job did not succeed: " + name)
        require(positive_int(job.get("runner_id"))
                and isinstance(job.get("runner_name"), str)
                and bool(job["runner_name"]),
                "required job never received an identifiable runner: " + name)
        steps = job.get("steps")
        require(isinstance(steps, list) and bool(steps),
                "required job has zero executed steps: " + name)
        seen_steps = set()
        for step in steps:
            require(isinstance(step, dict), "malformed job step")
            step_name = step.get("name")
            require(isinstance(step_name, str) and bool(step_name)
                    and step_name not in seen_steps, "missing/duplicate step name")
            seen_steps.add(step_name)
            require(positive_int(step.get("number")), "missing step sequence number")
            # A green job may hide continue-on-error failures. Never accept
            # explicit failing/pending steps as successful CI evidence.
            require(step.get("status") == "completed"
                    and step.get("conclusion") in ("success", "skipped"),
                    "job contains a failed or unfinished step: " + step_name)
            if step_name in required[name]:
                require(step.get("status") == "completed"
                        and step.get("conclusion") == "success",
                        "required verification/test step did not succeed: " + step_name)
        require(required[name].issubset(seen_steps),
                "required executed steps missing in job: " + name)
    require(set(required).issubset(seen_jobs), "required CI jobs missing")
    return True


def parse_requirements(values):
    required = {}
    for value in values:
        job, sep, step = value.partition("=")
        require(bool(sep) and job and step and job.strip() == job
                and step.strip() == step, "--require needs JOB=EXACT_STEP_NAME")
        require(step not in required.get(job, set()), "duplicate required step")
        required.setdefault(job, set()).add(step)
    require(bool(required), "at least one --require is mandatory")
    return required


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--repo-id", type=int, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--require", action="append", default=[], metavar="JOB=STEP")
    args = parser.parse_args(argv)
    try:
        size = args.evidence.stat().st_size
        require(0 < size <= MAX_EVIDENCE_BYTES, "evidence file size denied")
        with args.evidence.open("r", encoding="utf-8") as stream:
            def unique_fields(pairs):
                result = {}
                for k, v in pairs:
                    require(k not in result, "duplicate JSON field")
                    result[k] = v
                return result
            def bad_constant(_value):
                raise EvidenceError("nonfinite JSON constant")
            evidence = json.load(stream, object_pairs_hook=unique_fields,
                                 parse_constant=bad_constant)
        check(evidence, expected_repo=args.repo, expected_repo_id=args.repo_id,
              expected_sha=args.sha, required=parse_requirements(args.require))
    except (OSError, ValueError, RecursionError, UnicodeError) as exc:
        print("CI_EVIDENCE_DENIED: " + str(exc), file=sys.stderr)
        return 1
    print("CI_EVIDENCE_VALIDATED: exact-head stepful run metadata")
    return 0


if __name__ == "__main__":
    sys.exit(main())
