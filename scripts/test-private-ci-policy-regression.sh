#!/usr/bin/env bash
# Regression tests for private CI policy denial. No GitHub credentials or transfers.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
policy="$root/.github/private-ci-approved-repositories.txt"
python3 - "$policy" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
entries = [line.strip() for line in p.read_text().splitlines() if line.strip() and not line.lstrip().startswith("#")]
if entries:
    raise SystemExit("Expected no approved private CI destinations on this branch; review policy before updating this fixture")
print("PASS: no authorized destination in current policy")
PY
bash "$root/scripts/test-private-ci-destination.sh"
echo "PASS: private CI destination policy regression suite"
