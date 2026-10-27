#!/usr/bin/env python3
"""Read-only, fail-closed GitHub Actions evidence collector.

Usage:
  python3 tools/ci/runner_evidence.py --state state.json

The state file contains only qualifying attempts already reported. Nonqualifying
attempts are deliberately rechecked on later scans so delayed GitHub job
metadata cannot cause a permanent false negative.
"""
import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

OWNER = "ores-truffle-oreslang"
BACKENDS = ("java", "llvm", "net", "wasm", "forge")
HOUSEKEEPING = {"set up job", "complete job"}
API = "https://api.github.com"
SHA40 = re.compile(r"[a-fA-F0-9]{40}")

def meaningful(step):
    name = str(step.get("name") or "").strip()
    low = name.lower()
    return bool(
        name
        and low not in HOUSEKEEPING
        and not low.startswith("post ")
        and step.get("status") == "completed"
        and step.get("conclusion") is not None
    )

def runner_allocated(job):
    runner_id = job.get("runner_id")
    return (
        isinstance(runner_id, int)
        and not isinstance(runner_id, bool)
        and runner_id > 0
        and bool(str(job.get("runner_name") or "").strip())
    )

def qualified(job):
    return (
        job.get("status") == "completed"
        and job.get("conclusion") is not None
        and runner_allocated(job)
        and any(meaningful(s) for s in (job.get("steps") or []))
    )

def classify(job):
    """Conservative evidence classification; never implies backend parity."""
    text = " ".join(
        [str(job.get("name") or "")]
        + [str(s.get("name") or "") for s in (job.get("steps") or []) if meaningful(s)]
    ).lower()
    labels = []
    if any(x in text for x in ("native image", "native-image", "nativeaot", "native aot", " aot", "aot ")):
        labels.append("aot")
    if any(x in text for x in ("runtime", "execute", "execution", "run guest", "guest run")):
        labels.append("runtime")
    if any(x in text for x in ("compiler", "compile ores", "compile guest", "source compile")):
        labels.append("compiler")
    if any(x in text for x in ("contract", "lint", "format", "schema", "static check", "static-check")):
        labels.append("contract/static")
    if any(x in text for x in ("setup", "toolchain", "environment", "install dependencies", "bootstrap")):
        labels.append("setup/toolchain")
    return labels or ["unclassified"]

def request(path, token):
    req = urllib.request.Request(
        API + path,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "oreslang-runner-evidence/2",
        },
    )
    with urllib.request.urlopen(req, timeout=25) as response:
        return json.load(response)

def pages(path, token, key):
    output = []
    page = 1
    while True:
        sep = "&" if "?" in path else "?"
        data = request(path + sep + f"per_page=100&page={page}", token)
        batch = data.get(key)
        if not isinstance(batch, list):
            raise ValueError("missing " + key)
        output.extend(batch)
        if len(batch) < 100:
            return output
        page += 1
        if page > 10000:
            raise RuntimeError("pagination safety limit reached: " + path)

def attempt_key(repo, run_id, attempt):
    return f"{repo}:{run_id}:{attempt}"

def inspect_attempt(repo, run, attempt, token):
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        raise ValueError("invalid run attempt")
    jobs = pages(
        f"/repos/{repo}/actions/runs/{run['id']}/attempts/{attempt}/jobs",
        token,
        "jobs",
    )
    good = []
    for job in jobs:
        if qualified(job):
            steps = [
                {"name": s["name"], "conclusion": s.get("conclusion")}
                for s in (job.get("steps") or [])
                if meaningful(s)
            ]
            good.append(
                {
                    "name": job.get("name"),
                    "conclusion": job.get("conclusion"),
                    "runner_id": job["runner_id"],
                    "runner_name": job["runner_name"],
                    "classification": classify(job),
                    "meaningful_steps": steps,
                }
            )
    if not good:
        return None

    sha = str(run.get("head_sha") or "")
    branch = str(run.get("head_branch") or "").strip()
    url = str(run.get("html_url") or "").strip()
    if not SHA40.fullmatch(sha):
        raise ValueError("invalid exact commit SHA")
    if not branch or not url:
        raise ValueError("missing provenance")

    # GitHub exposes artifacts at run scope, not attempt scope. Report that
    # explicitly rather than pretending an artifact belongs to this attempt.
    artifacts = pages(f"/repos/{repo}/actions/runs/{run['id']}/artifacts", token, "artifacts")
    return {
        "backend": repo.rsplit(".", 1)[-1],
        "branch": branch,
        "sha": sha,
        "workflow_name": run.get("name"),
        "event": run.get("event"),
        "run_id": run["id"],
        "attempt": attempt,
        "url": url,
        "jobs": good,
        "artifacts_scope": "workflow_run",
        "artifacts": [
            {
                "name": a.get("name"),
                "expired": bool(a.get("expired")),
                "archive_download_url": a.get("archive_download_url"),
            }
            for a in artifacts
        ],
        "parity_claim": False,
    }

def collect(token, reported):
    reports = []
    newly_reported = set()
    for backend in BACKENDS:
        repo = f"{OWNER}/oreslang-source.{backend}"
        runs = pages(f"/repos/{repo}/actions/runs", token, "workflow_runs")
        for run in runs:
            # Wait for the workflow run to settle. This avoids emitting a
            # partial attempt while other jobs/artifacts are still changing.
            if run.get("status") != "completed":
                continue
            latest = run.get("run_attempt")
            if not isinstance(latest, int) or isinstance(latest, bool) or latest < 1:
                raise ValueError(f"missing/invalid run_attempt for {repo} run {run.get('id')}")
            run_id = run.get("id")
            if not isinstance(run_id, int) or isinstance(run_id, bool) or run_id < 1:
                raise ValueError(f"missing/invalid run id for {repo}")
            # Enumerate every attempt up to the latest reported by GitHub.
            # The attempt-specific jobs endpoint lets us recover reruns missed
            # between monitor executions.
            for attempt in range(1, latest + 1):
                key = attempt_key(repo, run_id, attempt)
                if key in reported or key in newly_reported:
                    continue
                evidence = inspect_attempt(repo, run, attempt, token)
                if evidence:
                    reports.append(evidence)
                    newly_reported.add(key)
    return reports, reported | newly_reported

def load_state(path):
    if not path.exists():
        return set()
    raw = json.loads(path.read_text())
    if not isinstance(raw, list) or not all(isinstance(x, str) for x in raw):
        raise ValueError("state must be a JSON array of reported attempt keys")
    return set(raw)

def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(sorted(state), indent=2) + "\n")
    tmp.replace(path)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("GITHUB_TOKEN required; refusing unauthenticated or partial scan")
    reported = load_state(args.state)
    reports, updated = collect(token, reported)
    # State is committed only after every repository, run, attempt, job page,
    # and artifact page completes successfully.
    save_state(args.state, updated)
    print(json.dumps(reports, indent=2))

if __name__ == "__main__":
    main()
