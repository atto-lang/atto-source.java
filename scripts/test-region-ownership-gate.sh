#!/usr/bin/env bash
# Tests host-runtime region ownership arbitration, NOT Oreslang guest lowering.
set -euo pipefail
root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
command -v javac >/dev/null
command -v java >/dev/null
scratch="$(mktemp -d "${TMPDIR:-/tmp}/ores-region-ownership.XXXXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/classes"
javac --release 21 -Xlint:all -Werror -d "$scratch/classes" \
  "$root/src/main/java/dev/oreslang/runtime/DopRegionSchedule.java" \
  "$root/src/main/java/dev/oreslang/runtime/RegionOwnershipGate.java" \
  "$root/src/test/java/dev/oreslang/runtime/RegionOwnershipGateCheck.java"
java -ea -cp "$scratch/classes" dev.oreslang.runtime.RegionOwnershipGateCheck
cat > "$scratch/UntrustedMint.java" <<'JAVA'
package untrusted;
import dev.oreslang.runtime.RegionOwnershipGate;
public final class UntrustedMint {
    Object counterfeit() { return RegionOwnershipGate.trustedCreate(null, new Object()); }
}
JAVA
if javac --release 21 -cp "$scratch/classes" -d "$scratch/classes" \
  "$scratch/UntrustedMint.java" >"$scratch/compiler.stdout" 2>"$scratch/compiler.stderr"; then
    echo "REGION_UNTRUSTED_MINT=UNEXPECTED_COMPILE_SUCCESS" >&2
    exit 1
fi
# Confirm rejected precisely for the private package boundary, not an absent
# JDK or another unrelated compilation failure.
grep -q 'RegionOwnershipGate is not public' "$scratch/compiler.stderr"
echo 'REGION_UNTRUSTED_MINT=COMPILE_REJECTED'
echo 'REGION_OWNERSHIP_HOST_GATE=PASS (NOT_ORESLANG_GUEST)'
