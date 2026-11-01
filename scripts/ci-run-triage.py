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
from datetime import datetime, timezone

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
# Preparation can mention "test" or "build" without executing either.
# Conservative precedence prevents dependency/bootstrap failures being called tests.
PREPARATION = re.compile(
    r"^(?:run\s+)?(?:install|restore|download|fetch|prepare|bootstrap|"
    r"configure|initialize|set\s?up|provision|cache)\b", re.I,
)
OTHER_CONCLUSIONS = {"cancelled", "skipped", "timed_out", "neutral", "action_required", "stale"}
PENDING_STATES = {"queued", "in_progress", "waiting", "pending", "requested"}



class TriageError(Exception):
    pass


def get_json(path):
    try:
        p = subprocess.run(["gh", "api", "--hostname", "github.com",
                            "--method", "GET", path],
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
    if HOUSEKEEPING.fullmatch(name.strip()) or PREPARATION.match(name.strip()):
        return "housekeeping"
    if BUILD_TEST.search(name):
        return "build_test"
    return "other_execution"


def _execution_interval(record):
    """Return a verified UTC interval, or None for incomplete/invalid metadata."""
    stamps = []
    for field in ("started_at", "completed_at"):
        raw = record.get(field)
        if not isinstance(raw, str) or not raw.strip():
            return None
        try:
            stamp = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except (ValueError, OverflowError):
            return None
        if stamp.tzinfo is None or stamp.utcoffset() is None:
            return None
        stamps.append(stamp.astimezone(timezone.utc))
    return tuple(stamps) if stamps[0] <= stamps[1] else None


def _valid_execution_interval(step):
    return _execution_interval(step) is not None


def _classify_job(job, run, run_id=None):
    if not isinstance(job, dict):
        raise TriageError("invalid job metadata")
    # The exported classify/assess functions must enforce the same exact-run
    # identity as inspect(). Otherwise a synthetic positive job with absent
    # provenance could be called executed_success by direct API callers.
    if not _positive_int(job.get("id")):
        raise TriageError("invalid job ID")
    if not _valid_sha(run.get("head_sha")) or job.get("head_sha") != run["head_sha"]:
        raise TriageError("job head SHA missing or mismatched")
    if not _positive_int(run.get("id")) or job.get("run_id") != run["id"]:
        raise TriageError("job run ID missing or mismatched")
    if run_id is not None and run["id"] != run_id:
        raise TriageError("run ID mismatch")
    attempt = run.get("run_attempt")
    if attempt is not None and not _positive_int(attempt):
        raise TriageError("invalid run attempt")
    if attempt is not None and job.get("run_attempt") is not None and job["run_attempt"] != attempt:
        raise TriageError("job run attempt mismatch")
    status, conclusion = job.get("status"), job.get("conclusion")
    if status is not None and not isinstance(status, str):
        raise TriageError("invalid job status")
    if status not in (None, "completed") and status not in PENDING_STATES:
        raise TriageError("invalid job status")
    if conclusion is not None and not isinstance(conclusion, str):
        raise TriageError("invalid job conclusion")
    runner_id, runner_name = job.get("runner_id"), job.get("runner_name")
    if runner_id is not None and (type(runner_id) is not int or runner_id < 0):
        raise TriageError("invalid runner ID")
    if runner_name is not None and not isinstance(runner_name, str):
        raise TriageError("invalid runner name")
    runner_observed = (_positive_int(runner_id) and isinstance(runner_name, str)
                       and bool(runner_name.strip()))
    result = {"job_id": job.get("id") if _positive_int(job.get("id")) else None,
              "classification": None, "steps_observed": None,
              "completed_failing_steps": 0, "completed_build_test_failures": 0,
              "reported_failures_without_timing": 0,
              "purpose_confidence": "HEURISTIC_ONLY",
              "runner_assignment_observed": runner_observed}
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
    if any(not isinstance(step, dict) for step in steps):
        raise TriageError("invalid step metadata")
    if not steps:
        result["classification"] = ("observed_no_step_failure" if conclusion == "failure"
                                    else "success_without_step_evidence")
        return result
    # A populated or timestamped step array is not sufficient evidence of an
    # assigned runner. Without an observed positive runner identity, do not
    # promote a synthetic/test-name payload to executed success or test failure.
    if not runner_observed:
        result["classification"] = "reported_steps_without_runner_evidence"
        return result
    # Job bounds, when supplied by GitHub, must agree with every timestamped
    # executed step. A forged out-of-window success cannot certify this job.
    job_interval = None
    if job.get("started_at") is not None or job.get("completed_at") is not None:
        job_interval = _execution_interval(job)
        if job_interval is None:
            raise TriageError("invalid job execution interval")
    failed, substantive_success = [], False
    uncertain_failed_steps = 0
    # GitHub supplies step ordinals. Reject duplicate, reordered, malformed, or
    # partially omitted ordinals instead of accepting a fabricated success.
    last_step_number = 0
    numbered_steps = 0
    for step in steps:
        if not isinstance(step, dict):
            raise TriageError("invalid step metadata")
        if "number" in step:
            number = step["number"]
            if not _positive_int(number) or number <= last_step_number:
                raise TriageError("invalid or non-increasing step number")
            last_step_number = number
            numbered_steps += 1
        step_status, step_conclusion = step.get("status"), step.get("conclusion")
        if step_status is not None and not isinstance(step_status, str):
            raise TriageError("invalid step status")
        if step_status is not None and step_status not in ("completed", "queued", "in_progress", "pending", "waiting", "requested"):
            raise TriageError("invalid step status")
        if step_conclusion is not None and not isinstance(step_conclusion, str):
            raise TriageError("invalid step conclusion")
        # An unknown, missing, or inconsistent step result must never be
        # ignored while another timestamped step promotes executed_success.
        # A completed job cannot contain an unfinished step in a coherent
        # final GitHub API snapshot; treat it as inconclusive, not proof.
        valid_step_conclusions = {
            "success", "failure", "timed_out", "cancelled", "skipped",
            "neutral", "action_required", "stale",
        }
        if step_conclusion is not None and step_conclusion not in valid_step_conclusions:
            raise TriageError("unrecognized step conclusion")
        if step_status == "completed" and step_conclusion is None:
            raise TriageError("completed step missing conclusion")
        if step_status != "completed" and step_conclusion is not None:
            raise TriageError("unfinished step has conclusion")
        if status == "completed" and step_status != "completed":
            raise TriageError("completed job contains unfinished step")
        # Completed step status and conclusion alone do not prove execution;
        # require an observable start and completion timestamp as well.
        timed_execution = step_status == "completed" and _valid_execution_interval(step)
        if timed_execution and job_interval is not None:
            step_interval = _execution_interval(step)
            if (step_interval[0] < job_interval[0] or
                    step_interval[1] > job_interval[1]):
                raise TriageError("step outside job execution interval")
        if step_conclusion == "success" and not timed_execution:
            raise TriageError("successful step lacks verified execution interval")
        purpose = _purpose(step.get("name"))
        if step_status == "completed" and step_conclusion in ("failure", "timed_out"):
            if timed_execution:
                failed.append(purpose)
            else:
                uncertain_failed_steps += 1
        if timed_execution and step_conclusion == "success" and purpose != "housekeeping":
            substantive_success = True
    if numbered_steps not in (0, len(steps)):
        raise TriageError("partially missing step numbers")
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
    # Reject malformed JSON types without surfacing unhandled TypeError from set lookup.
    if run.get("status") is not None and not isinstance(run["status"], str):
        raise TriageError("invalid run status")
    if run.get("conclusion") is not None and not isinstance(run["conclusion"], str):
        raise TriageError("invalid run conclusion")
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
    elif "reported_steps_without_runner_evidence" in kinds:
        category = "reported_steps_without_runner_evidence"
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


def _fetch_jobs_pages(repo, run_id, attempt, fetch):
    """Read exactly one run attempt's jobs, or fail closed on incomplete pages.

    The general /runs/{id}/jobs endpoint defaults to the latest attempt.
    GitHub job objects do not necessarily expose run_attempt, so attempt
    provenance comes from the explicit /attempts/{n}/jobs endpoint.
    """
    if not _positive_int(attempt):
        raise TriageError("invalid run attempt")
    prefix = f"repos/{repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100"
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
        remaining = count - len(jobs)
        if len(batch) != min(100, remaining):
            raise TriageError("job pagination incomplete or invalid page size")
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


def _verify_current_pr_head(pr, expected_sha, pr_number):
    """Verify the live PR identity; a caller-provided SHA alone may be stale."""
    if not isinstance(pr, dict) or pr.get("number") != pr_number:
        raise TriageError("PR identity unavailable or mismatched")
    head = pr.get("head")
    if not isinstance(head, dict) or head.get("sha") != expected_sha:
        raise TriageError("PR current head SHA mismatches requested exact source")
    if pr.get("state") != "open":
        raise TriageError("PR is no longer open")


def inspect(repo, run_id, expected_sha, fetch=get_json, pr_number=None):
    if not isinstance(repo, str) or not REPO_RE.fullmatch(repo) or not _positive_int(run_id):
        raise TriageError("invalid repository or run ID")
    if not _valid_sha(expected_sha):
        raise TriageError("expected exact-head SHA required")
    if pr_number is not None and not _positive_int(pr_number):
        raise TriageError("invalid PR number")
    pr_path = f"repos/{repo}/pulls/{pr_number}" if pr_number is not None else None
    if pr_path:
        _verify_current_pr_head(fetch(pr_path), expected_sha, pr_number)
    run_path = f"repos/{repo}/actions/runs/{run_id}"
    run = fetch(run_path)
    if not isinstance(run, dict) or run.get("id") != run_id or run.get("head_sha") != expected_sha:
        raise TriageError("initial run identity/head SHA mismatched")
    if not _positive_int(run.get("run_attempt")):
        raise TriageError("run attempt unavailable or invalid")
    jobs = _fetch_jobs_pages(repo, run_id, run["run_attempt"], fetch)
    # The attempt-scoped jobs URL is necessary but not sufficient: require
    # every returned job to bind to the requested run and exact source head,
    # even when the caller did not supply --pr. Otherwise a mismatched or
    # incomplete job response could be credited as executed CI evidence.
    for job in jobs:
        if (job.get("run_id") != run_id or
                job.get("head_sha") != expected_sha or
                (job.get("run_attempt") is not None and
                 job.get("run_attempt") != run["run_attempt"])):
            raise TriageError("job exact-head/attempt provenance unverified")
    final_run = fetch(run_path)
    # The latest attempt can change while we page through jobs.
    identity_fields = ("id", "head_sha", "run_attempt", "status", "conclusion")
    if not isinstance(final_run, dict) or any(run.get(field) != final_run.get(field) for field in identity_fields):
        raise TriageError("run changed during job inspection")
    if pr_path:
        _verify_current_pr_head(fetch(pr_path), expected_sha, pr_number)
    outcome = assess(run, jobs, expected_sha, run_id=run_id)
    return {"repository": repo, "run_id": run_id, "head_sha": expected_sha,
            "run_attempt": run["run_attempt"], "pr_number": pr_number,
            "pr_head_verified": bool(pr_path),
            "job_provenance_verified": bool(jobs),
            "conclusion": run.get("conclusion"), **outcome}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("run_id", type=int)
    parser.add_argument("expected_head_sha")
    parser.add_argument("--pr", type=int, metavar="NUMBER",
                        help="verify current PR head and each job's exact run/attempt provenance")
    args = parser.parse_args(argv)
    try:
        report = inspect(args.repository, args.run_id, args.expected_head_sha,
                         pr_number=args.pr)
    except TriageError as exc:
        print("CI_TRIAGE_INCONCLUSIVE: " + str(exc), file=sys.stderr)
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0 if report["category"] == "executed_success" else 1


if __name__ == "__main__":
    sys.exit(main())
