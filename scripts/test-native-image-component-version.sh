#!/usr/bin/env bash
# Pure local version gate tests; no compiler downloads, secrets or source transfer.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/pom.xml" <<'POM'
<project><properties><graalvm.version>25.3.4.1</graalvm.version></properties></project>
POM
cat > "$tmp/bin/native-image" <<'STUB'
#!/usr/bin/env bash
mode="${MOCK_MODE:-valid}"
jdk=25.0.4.1
runtime=25.3.4.1+1.1
substrate=25.3.4.1+1.1
case "$mode" in
  valid) ;;
  wrong-runtime) runtime=25.3.4.10+1.1 ;;
  wrong-substrate) substrate=25.3.4.10+1.1 ;;
  wrong-jdk-major) jdk=26.0.4.1 ;;
  missing-substrate) substrate= ;;
  duplicate-runtime) ;;
  legacy-only)
    printf 'native-image 25.3.4.1 test\n'
    exit 0 ;;
  *) echo "unknown test mode: $mode" >&2; exit 3 ;;
esac
printf 'native-image %s 2026-08-18\n' "$jdk"
printf 'GraalVM Runtime Environment Oracle GraalVM %s (build %s)\n' "$runtime" "$jdk"
if [[ "$mode" == duplicate-runtime ]]; then
  printf 'GraalVM Runtime Environment Oracle GraalVM %s (build %s)\n' "$runtime" "$jdk"
fi
if [[ -n "$substrate" ]]; then
  printf 'Substrate VM Oracle GraalVM %s (build %s)\n' "$substrate" "$jdk"
fi
STUB
chmod +x "$tmp/bin/native-image"
PATH="$tmp/bin:$PATH" MOCK_MODE=valid bash "$root/verify-native-image-component-version.sh" "$tmp/pom.xml"

negative=0
for mode in wrong-runtime wrong-substrate wrong-jdk-major missing-substrate duplicate-runtime legacy-only; do
  if PATH="$tmp/bin:$PATH" MOCK_MODE="$mode" \
      bash "$root/verify-native-image-component-version.sh" "$tmp/pom.xml"; then
    echo "native-image version gate accepted invalid $mode" >&2
    exit 1
  fi
  negative=$((negative + 1))
done
cat > "$tmp/empty-pom.xml" <<'POM'
<project><properties></properties></project>
POM
if PATH="$tmp/bin:$PATH" MOCK_MODE=valid \
    bash "$root/verify-native-image-component-version.sh" "$tmp/empty-pom.xml"; then
  echo "native-image gate accepted missing Maven component release pin" >&2
  exit 1
fi
negative=$((negative + 1))
echo "native-image exact component version self-test: PASS (1 positive, $negative negative)"
