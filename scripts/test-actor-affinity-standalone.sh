#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$work_dir" \
  "$repo_root/src/main/java/dev/oreslang/runtime/SoftLaneSelector.java" \
  "$repo_root/src/main/java/dev/oreslang/runtime/ActorAffinityDispatcher.java" \
  "$repo_root/src/main/java/dev/oreslang/runtime/NativeCarrierExecutor.java" \
  "$repo_root/scripts/actor-affinity/SoftLaneSelectorStandaloneCheck.java" \
  "$repo_root/scripts/actor-affinity/ActorAffinityDispatcherStandaloneCheck.java"
java -ea -cp "$work_dir" dev.oreslang.runtime.SoftLaneSelectorStandaloneCheck
java -ea -cp "$work_dir" dev.oreslang.runtime.ActorAffinityDispatcherStandaloneCheck
