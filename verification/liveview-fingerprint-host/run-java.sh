#!/usr/bin/env bash
set -euo pipefail
R=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
H="$R/verification/liveview-fingerprint-host"
python3 "$H/verify_tsv_against_fixture.py" "$R/contracts/v1/ores-liveview-event-fingerprint-v1.json" "$H/fingerprint-v1.tsv"
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
javac -encoding UTF-8 -d "$D" "$H/LiveViewFingerprintVectors.java"
java -cp "$D" LiveViewFingerprintVectors "$H/fingerprint-v1.tsv"
