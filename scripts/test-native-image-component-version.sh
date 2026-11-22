#!/usr/bin/env bash
# Pure local preflight self-test; no compiler downloads, GitHub credentials or source transfer.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat >"$tmp/pom.xml" <<'POM'
<project><properties><graalvm.version>25.3.4.1</graalvm.version></properties></project>
POM
cat >"$tmp/bin/native-image" <<'STUB'
#!/usr/bin/env bash
printf 'native-image %s test\n' "${MOCK_NATIVE_IMAGE_VERSION:-25.3.4.1}"
STUB
chmod +x "$tmp/bin/native-image"
PATH="$tmp/bin:$PATH" MOCK_NATIVE_IMAGE_VERSION=25.3.4.1 \
  bash "$root/verify-native-image-component-version.sh" "$tmp/pom.xml"
if PATH="$tmp/bin:$PATH" MOCK_NATIVE_IMAGE_VERSION=25.0.4 \
  bash "$root/verify-native-image-component-version.sh" "$tmp/pom.xml"; then
  echo "version gate accepted incompatible GraalVM release" >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" MOCK_NATIVE_IMAGE_VERSION=25.3.4.10 \
  bash "$root/verify-native-image-component-version.sh" "$tmp/pom.xml"; then
  echo "version gate accepted prefix-only version match" >&2
  exit 1
fi
cat >"$tmp/empty-pom.xml" <<'POM'
<project><properties></properties></project>
POM
if PATH="$tmp/bin:$PATH" \
  bash "$root/verify-native-image-component-version.sh" "$tmp/empty-pom.xml"; then
  echo "version gate accepted missing Maven pin" >&2
  exit 1
fi
echo "native-image exact component version self-test: PASS (1 positive, 3 negative)"
