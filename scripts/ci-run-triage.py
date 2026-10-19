#!/usr/bin/env python3
"""Read-only, fail-closed GitHub Actions run classification.

Uses only gh api GET; never triggers runs, transfers source, or reads logs.
A zero-step failure is *not* evidence of billing/minute exhaustion.
"""
import argparse
import json
import re
import subprocess
import sys

REPO_RE = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*\Z")
SHA_RE = re.compile(r"\A[a-f0-9]{40}\Z")


class TriageError(Exception):
    pass


def get_json(path):
    try:
        p = subprocess.run(["gh", "api", "--method", "GET", path],
                           text=True, capture_output=True, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise TriageError("GitHub API unavailable") from None
    if p.returncode:
        raise TriageError("GitHub API request failed")
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError:
        raise TriageError("GitHub API returned invalid JSON") from None


def classify(run, jobs, expected_head_sha):
    if not isinstance(run, dict) or not isinstance(jobs, list):
        raise TriageError("invalid API response shape")
    if not SHA_RE.fullmatch(expected_head_sha):
        raise TriageError("expected exact-head SHA required")
    if run.get("head_sha") != expected_head_sha:
        raise TriageError("run head SHA mismatches requested exact source")
    state = run.get("status")
    if state != "completed":
        return "not_terminal"
    if not jobs:
        return "no_jobs_recorded"
    if any(not isinstance(j, dict) or not isinstance(j.get("steps", []), list) for j in jobs):
        raise TriageError("missing or invalid job metadata")
    steps = [s for job in jobs for s in job.get("steps", [])]
    # A job step can be queued or skipped without ever being started.
    executed = [s for s in steps if isinstance(s, dict) and s.get("started_at")]
    if executed:
        if run.get("conclusion") == "success" and all(j.get("conclusion") == "success" for j in jobs):
            return "executed_success"
        return "executed_non_success"
    if run.get("conclusion") == "failure":
        return "failed_before_steps_cause_unverified"
    return "no_executed_steps"


def inspect(repo, run_id, expected_sha, fetch=get_json):
    if not REPO_RE.fullmatch(repo) or type(run_id) is not int or run_id <= 0:
        raise TriageError("invalid repository or run ID")
    if not isinstance(expected_sha, str) or not SHA_RE.fullmatch(expected_sha):
        raise TriageError("expected exact-head SHA required")
    run = fetch(f"repos/{repo}/actions/runs/{run_id}")
    response = fetch(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")
    if not isinstance(response, dict) or not isinstance(response.get("jobs"), list):
        raise TriageError("missing jobs collection")
    if response.get("total_count", len(response["jobs"])) > len(response["jobs"]):
        raise TriageError("job pagination incomplete")
    jobs = response["jobs"]
    category = classify(run, jobs, expected_sha)
    return {"repository": repo, "run_id": run_id, "head_sha": expected_sha,
            "conclusion": run.get("conclusion"), "category": category,
            "job_count": len(jobs),
            "executed_steps": sum(bool(s.get("started_at")) for j in jobs for s in j.get("steps", []))}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("run_id", type=int)
    parser.add_argument("expected_head_sha")
    args = parser.parse_args(argv)
    try:
        report = inspect(args.repository, args.run_id, args.expected_head_sha)
    except TriageError as exc:
        print("CI_TRIAGE_INCONCLUSIVE: " + str(exc), file=sys.stderr)
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0 if report["category"] == "executed_success" else 1


if __name__ == "__main__":
    sys.exit(main())
