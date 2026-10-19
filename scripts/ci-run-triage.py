#!/usr/bin/env python3
"""Read-only, fail-closed GitHub Actions run classification.

Uses only gh api GET; never triggers runs, transfers source, or reads logs.
A zero-step failure is *not* evidence of billing/minute exhaustion.
Step names are heuristic, not proof of actual guest-test execution.
"""
import argparse
import json
import re
import subprocess
import sys

REPO_RE = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*\Z")
SHA_RE = re.compile(r"\A[a-f0-9]{40}\Z")

HOUSEKEEPING = re.compile(
    r"^(?:set up job|complete job|initialize containers|start containers|"
    r"stop containers|pre .+|post .+|checkout(?: repository| source| code)?|"
    r"set ?up (?:node|java|python|go|rust|ruby|gradle|maven)|"
    r"cache(?: dependencies)?|upload artifact|download artifact|"
    r"(?:run )?actions/(?:checkout|cache|setup-[a-z0-9_-]+|"
    r"upload-artifact|download-artifact)(?:@\S+)?|"
    r"run (?:git checkout|npm ci|pip install|mvn dependency:resolve).*)$",
    re.I,
)
BUILD_TEST = re.compile(
    r"\b(?:test|tests|testing|pytest|unittest|spec|build|compile|compiler|"
    r"gradle|maven|cargo|make|cmake|lint|check|validation|conformance)\b", re.I,
)
OTHER_CONCLUSIONS = {"cancelled", "skipped", "timed_out", "neutral", "action_required", "stale"}
PENDING_STATES = {"queued", "in_progress", "waiting", "pending", "requested"}



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



def _valid_sha(value):
    return isinstance(value, str) and SHA_RE.fullmatch(value) is not None


def _positive_int(value):
    return type(value) is int and value > 0


def _purpose(name):
    if not isinstance(name, str) or not name.strip():
        return "unknown"
    if HOUSEKEEPING.fullmatch(name.strip()):
        return "housekeeping"
    if BUILD_TEST.search(name):
        return "build_test"
    return "other_execution"


def _classify_job(job, run, run_id=None):
    if not isinstance(job, dict):
        raise TriageError("invalid job metadata")
    if job.get("head_sha") is not None and job["head_sha"] != run["head_sha"]:
        raise TriageError("job head SHA mismatch")
    if run_id is not None and job.get("run_id") is not None and job["run_id"] != run_id:
        raise TriageError("job run ID mismatch")
    attempt = run.get("run_attempt")
    if attempt is not None and not _positive_int(attempt):
        raise TriageError("invalid run attempt")
    if attempt is not None and job.get("run_attempt") is not None and job["run_attempt"] != attempt:
        raise TriageError("job run attempt mismatch")
    status, conclusion = job.get("status"), job.get("conclusion")
    if status not in (None, "completed") and status not in PENDING_STATES:
        raise TriageError("invalid job status")
    if conclusion is not None and not isinstance(conclusion, str):
        raise TriageError("invalid job conclusion")
    result = {"job_id": job.get("id") if _positive_int(job.get("id")) else None,
              "classification": None, "steps_observed": None,
              "completed_failing_steps": 0, "completed_build_test_failures": 0,
              "reported_failures_without_timing": 0,
              "purpose_confidence": "HEURISTIC_ONLY"}
    if status in PENDING_STATES:
        result["classification"] = "pending"
        return result
    if status is None:
        result["classification"] = "unobserved_job_status"
        return result
    if conclusion in OTHER_CONCLUSIONS:
        result["classification"] = conclusion
        return result
    if conclusion not in ("success", "failure"):
        result["classification"] = "unobserved_conclusion"
        return result
    steps = job.get("steps")
    if steps is None:
        result["classification"] = "unobserved_failure" if conclusion == "failure" else "unobserved_success"
        return result
    if not isinstance(steps, list):
        raise TriageError("invalid steps collection")
    result["steps_observed"] = len(steps)
    if not steps:
        result["classification"] = ("observed_no_step_failure" if conclusion == "failure"
                                    else "success_without_step_evidence")
        return result
    failed, substantive_success = [], False
    uncertain_failed_steps = 0
    for step in steps:
        if not isinstance(step, dict):
            raise TriageError("invalid step metadata")
        step_status, step_conclusion = step.get("status"), step.get("conclusion")
        if step_status is not None and step_status not in ("completed", "queued", "in_progress", "pending", "waiting", "requested"):
            raise TriageError("invalid step status")
        if step_conclusion is not None and not isinstance(step_conclusion, str):
            raise TriageError("invalid step conclusion")
        # Completed step status and conclusion alone do not prove execution;
        # require an observable start and completion timestamp as well.
        timed_execution = (step_status == "completed" and
                           isinstance(step.get("started_at"), str) and bool(step["started_at"].strip()) and
                           isinstance(step.get("completed_at"), str) and bool(step["completed_at"].strip()))
        purpose = _purpose(step.get("name"))
        if step_status == "completed" and step_conclusion in ("failure", "timed_out"):
            if timed_execution:
                failed.append(purpose)
            else:
                uncertain_failed_steps += 1
        if timed_execution and step_conclusion == "success" and purpose != "housekeeping":
            substantive_success = True
    result["completed_failing_steps"] = len(failed)
    result["completed_build_test_failures"] = failed.count("build_test")
    result["reported_failures_without_timing"] = uncertain_failed_steps
    if conclusion == "failure":
        if "build_test" in failed:
            category = "executed_build_test_failure"
        elif "other_execution" in failed:
            category = "executed_step_failure_unclassified"
        elif "unknown" in failed:
            category = "failed_step_purpose_unknown"
        elif failed:
            category = "setup_only_failure"
        elif uncertain_failed_steps:
            category = "reported_failure_execution_unobserved"
        else:
            category = "failure_without_completed_failing_step"
    elif failed:
        category = "success_with_reported_failing_step"
    elif uncertain_failed_steps:
        category = "success_with_unverified_step_failure"
    elif substantive_success:
        category = "executed_success"
    else:
        category = "success_without_substantive_evidence"
    result["classification"] = category
    return result


def assess(run, jobs, expected_head_sha, run_id=None):
    if not isinstance(run, dict) or not isinstance(jobs, list):
        raise TriageError("invalid API response shape")
    if not _valid_sha(expected_head_sha) or run.get("head_sha") != expected_head_sha:
        raise TriageError("run head SHA mismatches requested exact source")
    if run_id is not None and (not _positive_int(run_id) or run.get("id") not in (None, run_id)):
        raise TriageError("run ID mismatch")
    if run.get("status") in PENDING_STATES:
        return {"category": "pending", "jobs": [], "job_count": len(jobs)}
    if run.get("status") != "completed":
        raise TriageError("unrecognized run status")
    if run.get("conclusion") in OTHER_CONCLUSIONS:
        return {"category": run["conclusion"], "jobs": [], "job_count": len(jobs)}
    if run.get("conclusion") not in ("success", "failure"):
        return {"category": "unobserved_conclusion", "jobs": [], "job_count": len(jobs)}
    if not jobs:
        return {"category": "no_jobs_recorded", "jobs": [], "job_count": 0}
    outcomes = [_classify_job(j, run, run_id) for j in jobs]
    kinds = {j["classification"] for j in outcomes}
    if "executed_build_test_failure" in kinds:
        category = "executed_build_test_failure"
    elif "executed_step_failure_unclassified" in kinds:
        category = "executed_step_failure_unclassified"
    elif "failed_step_purpose_unknown" in kinds:
        category = "failed_step_purpose_unknown"
    elif "unobserved_failure" in kinds or "unobserved_job_status" in kinds:
        category = "unobserved_failure" if run["conclusion"] == "failure" else "unobserved_success"
    elif "setup_only_failure" in kinds:
        category = "setup_only_failure"
    elif "observed_no_step_failure" in kinds:
        category = "observed_no_step_failure"
    elif "reported_failure_execution_unobserved" in kinds:
        category = "reported_failure_execution_unobserved"
    elif "success_with_reported_failing_step" in kinds:
        category = "success_with_reported_failing_step"
    elif "success_with_unverified_step_failure" in kinds:
        category = "success_with_unverified_step_failure"
    elif "pending" in kinds:
        category = "pending"
    elif run["conclusion"] == "failure":
        category = "failure_without_completed_failing_step"
    elif kinds == {"executed_success"}:
        category = "executed_success"
    else:
        category = "success_without_substantive_evidence"
    return {"category": category, "jobs": outcomes, "job_count": len(jobs)}


def classify(run, jobs, expected_head_sha):
    return assess(run, jobs, expected_head_sha)["category"]


def _fetch_jobs_pages(repo, run_id, fetch):
    """Read every jobs page, or reject a partial or racing snapshot."""
    prefix = f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"
    jobs = []
    expected = None
    seen_ids = set()
    for page in range(1, 101):
        response = fetch(prefix + ("" if page == 1 else f"&page={page}"))
        if not isinstance(response, dict) or not isinstance(response.get("jobs"), list):
            raise TriageError("job pagination incomplete: missing jobs collection")
        count, batch = response.get("total_count"), response["jobs"]
        if type(count) is not int or not 0 <= count <= 10000 or (expected is not None and count != expected):
            raise TriageError("job pagination incomplete or inconsistent")
        expected = count
        if len(batch) > 100 or (len(jobs) < count and not batch):
            raise TriageError("job pagination incomplete or invalid")
        for job in batch:
            if not isinstance(job, dict) or not _positive_int(job.get("id")) or job["id"] in seen_ids:
                raise TriageError("job pagination duplicate or invalid ID")
            seen_ids.add(job["id"])
        jobs.extend(batch)
        if len(jobs) == count:
            return jobs
        if len(jobs) > count:
            raise TriageError("job pagination exceeded total count")
    raise TriageError("job pagination exceeds safe page limit")


def inspect(repo, run_id, expected_sha, fetch=get_json):
    if not isinstance(repo, str) or not REPO_RE.fullmatch(repo) or not _positive_int(run_id):
        raise TriageError("invalid repository or run ID")
    if not _valid_sha(expected_sha):
        raise TriageError("expected exact-head SHA required")
    run_path = f"repos/{repo}/actions/runs/{run_id}"
    run = fetch(run_path)
    if not isinstance(run, dict) or run.get("id") != run_id or run.get("head_sha") != expected_sha:
        raise TriageError("initial run identity/head SHA mismatched")
    jobs = _fetch_jobs_pages(repo, run_id, fetch)
    final_run = fetch(run_path)
    # The latest attempt can change while we page through jobs.
    identity_fields = ("id", "head_sha", "run_attempt", "status", "conclusion")
    if not isinstance(final_run, dict) or any(run.get(field) != final_run.get(field) for field in identity_fields):
        raise TriageError("run changed during job inspection")
    outcome = assess(run, jobs, expected_sha, run_id=run_id)
    return {"repository": repo, "run_id": run_id, "head_sha": expected_sha,
            "conclusion": run.get("conclusion"), **outcome}


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
