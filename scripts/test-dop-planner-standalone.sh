#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
javac --release 21 -Xlint:all -Werror -d "$work_dir" \
  "$repo_root/src/main/java/dev/oreslang/runtime/DataOrientedLayout.java" \
  "$repo_root/src/main/java/dev/oreslang/runtime/DopRegionSchedule.java" \
  "$repo_root/src/main/java/dev/oreslang/runtime/accel/DopKernelIR.java" \
  "$repo_root/scripts/dop-planner/DopRegionScheduleStandaloneCheck.java" \
  "$repo_root/scripts/dop-planner/DopOwnedRegionStandaloneCheck.java"
java -ea -cp "$work_dir" dev.oreslang.runtime.DopRegionScheduleStandaloneCheck
java -ea -cp "$work_dir" dev.oreslang.runtime.accel.DopOwnedRegionStandaloneCheck
