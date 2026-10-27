#!/usr/bin/env python3
"""Read-only, fail-closed GitHub Actions evidence collector. Requires GITHUB_TOKEN.
Usage: python3 tools/ci/runner_evidence.py --state state.json
Only newly verified (repo, run_id, run_attempt) tuples are emitted.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OWNER = "ores-truffle-oreslang"
BACKENDS = ("java", "llvm", "net", "wasm", "forge")
HOUSEKEEPING = {"set up job", "complete job"}
API = "https://api.github.com"

def meaningful(step):
    name = str(step.get("name") or "").strip()
    low = name.lower()
    return bool(name and low not in HOUSEKEEPING and not low.startswith("post ") and step.get("status") == "completed" and step.get("conclusion") is not None)

def qualified(job):
    return (isinstance(job.get("runner_id"), int) and not isinstance(job["runner_id"], bool)
            and job["runner_id"] > 0 and bool(str(job.get("runner_name") or "").strip())
            and any(meaningful(s) for s in (job.get("steps") or [])))

def request(path, token):
    req = urllib.request.Request(API + path, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "oreslang-runner-evidence/1"})
    with urllib.request.urlopen(req, timeout=25) as response:
        return json.load(response)

def pages(path, token, key):
    output = []
    for page in range(1, 11):
        sep = "&" if "?" in path else "?"
        data = request(path + sep + "per_page=100&page=" + str(page), token)
        batch = data.get(key)
        if not isinstance(batch, list):
            raise ValueError("missing " + key)
        output.extend(batch)
        if len(batch) < 100:
            return output
    raise RuntimeError("pagination limit reached: " + path)

def inspect(repo, run, token):
    attempt = run.get("run_attempt")
    if not isinstance(attempt, int) or attempt < 1:
        raise ValueError("missing run attempt")
    jobs = pages(f"/repos/{repo}/actions/runs/{run['id']}/attempts/{attempt}/jobs", token, "jobs")
    good = []
    for job in jobs:
        if qualified(job):
            good.append({"name": job.get("name"), "conclusion": job.get("conclusion"),
                         "runner_id": job["runner_id"], "runner_name": job["runner_name"],
                         "steps": [{"name": s["name"], "conclusion": s.get("conclusion")}
                                   for s in job["steps"] if meaningful(s)]})
    if not good:
        return None
    artifacts = pages(f"/repos/{repo}/actions/runs/{run['id']}/artifacts", token, "artifacts")
    return {"backend": repo.rsplit(".", 1)[-1], "branch": run.get("head_branch"),
            "sha": run.get("head_sha"), "run_id": run["id"], "attempt": attempt,
            "url": run.get("html_url"), "jobs": good,
            "artifacts": [{"name": a.get("name"), "url": a.get("archive_download_url")}
                          for a in artifacts if not a.get("expired")]}

def collect(token, seen):
    reports = []
    updated = set(seen)
    for backend in BACKENDS:
        repo = f"{OWNER}/oreslang-source.{backend}"
        runs = pages(f"/repos/{repo}/actions/runs", token, "workflow_runs")
        for run in runs:
            if run.get("status") != "completed":
                continue
            attempt = run.get("run_attempt")
            if not isinstance(attempt, int) or attempt < 1:
                continue
            key = f"{repo}:{run['id']}:{attempt}"
            if key in updated:
                continue
            evidence = inspect(repo, run, token)
            if evidence:
                if not re.fullmatch(r"[a-fA-F0-9]{40}", str(evidence["sha"] or "")):
                    raise ValueError("invalid exact commit SHA")
                if not evidence["branch"] or not evidence["url"]:
                    raise ValueError("missing provenance")
                reports.append(evidence)
            # Mark inspected attempts, including nonqualifying ones; reruns use a new key.
            updated.add(key)
    return reports, updated

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--state", type=Path, required=True)
    args = p.parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("GITHUB_TOKEN required; refusing unauthenticated or partial scan")
    state = set(json.loads(args.state.read_text())) if args.state.exists() else set()
    reports, updated = collect(token, state)
    # Only commit state after every backend and every paginated request succeeds.
    tmp = args.state.with_suffix(args.state.suffix + ".tmp")
    tmp.write_text(json.dumps(sorted(updated), indent=2) + "\n")
    tmp.replace(args.state)
    print(json.dumps(reports, indent=2))

if __name__ == "__main__":
    main()
