#!/usr/bin/env sh
# Offline, non-uploading proof checks. Safe on an already-authorized source
# checkout even when GitHub Actions minutes, Maven, JDK, and compilers are
# unavailable. This script does NOT authorize mirroring any private source.
set -eu
cd "$(dirname "$0")/.."

if ! command -v bash >/dev/null 2>&1; then
  echo "PRIVATE_CI_SECURITY_BLOCKED: Bash is required" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "PRIVATE_CI_SECURITY_BLOCKED: Python 3 is required" >&2
  exit 1
fi

# Run all checks without writing .pyc files into a reviewed clean checkout.
export PYTHONDONTWRITEBYTECODE=1
bash -n scripts/check-private-ci-destination.sh \
  scripts/test-private-ci-destination.sh \
  scripts/run-in-private-ci-destination.sh
bash scripts/test-private-ci-destination.sh
python3 -B scripts/test-publish-private-ci-source.py
python3 -B tests/test_ci_private_mirror_preflight.py
# The Surefire gate itself must reject absent, skipped and fabricated
# coverage even on machines without Java or Maven installed.
python3 -B scripts/test-verify-native-actor-surefire.py
echo "ORES_PRIVATE_CI_SECURITY_SUITES_PASS"
