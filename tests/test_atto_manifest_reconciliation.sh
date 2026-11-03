#!/usr/bin/env bash
# AttoLang canonical manifest/CLI source-only invariants. Synthetic checks are
# NOT evidence of actual Java compiler or AttoLang guest execution.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
config="${1:-$root/src/main/java/dev/oreslang/config/OresProjectConfig.java}"
reader="${2:-$root/src/main/java/dev/oreslang/runtime/BoundedSourceReader.java}"
launcher="${3:-$root/src/main/java/dev/oreslang/launcher/OresMain.java}"
die() { echo "FAIL AttoLang canonical manifest contract: $*" >&2; exit 1; }
[[ -f "$config" && -f "$reader" && -f "$launcher" ]] ||
  die "missing compiler config, reader or CLI"

grep -Fq 'MANIFEST_NAME = ".atto.pkg.toml"' "$config" ||
  die "canonical manifest name is not .atto.pkg.toml"
grep -Fq 'LEGACY_MANIFEST_NAME = ".oreslangc.cfg.toml"' "$config" ||
  die "legacy compatibility manifest missing"
grep -Fq 'Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)' "$config" ||
  die "canonical manifest entry can downgrade when a symlink exists"
grep -Fq 'readPinnedAttoManifestUtf8(' "$config" ||
  die "canonical manifest bypasses pinned reader"
grep -Fq 'ENV_ATTOLANG_PATH' "$config" ||
  die "canonical environment lookup missing"
grep -Fq 'environment.containsKey(ENV_ATTOLANG_PATH)' "$config" ||
  die "explicit empty canonical path must suppress legacy injection"
grep -Fq 'canonicalAttoImporter' "$config" ||
  die "canonical importer extension contract missing"
grep -Fq '? List.of(".atto") : List.of(".atto", ".ores", ".java")' "$config" ||
  die "new .atto source must not silently import legacy suffix"
grep -Fq 'existsNoFollow(source)' "$config" ||
  die "canonical symlink collision guard missing"

grep -Fq 'readPinnedAttoManifestUtf8(' "$reader" ||
  die "secure pinned manifest reader absent"
grep -Fq 'SecureDirectoryStream<Path>' "$reader" ||
  die "manifest reader lacks pinned directory handles"
grep -Fq 'pinned.newByteChannel(part,' "$reader" ||
  die "manifest leaf read is not pinned"
grep -Fq 'LinkOption.NOFOLLOW_LINKS' "$reader" ||
  die "source/manifest reader lacks no-follow open"
grep -Fq 'readWithByteCount(Path path)' "$reader" ||
  die "existing .atto source reader was replaced"

grep -Fq 'usage: attolang [--check|--build-analysis]' "$launcher" ||
  die "public CLI identity is not attolang"
grep -Fq 'System.err.println("attolang: "' "$launcher" ||
  die "public CLI diagnostics were not renamed"

# Canonical source/example remains the exact three reviewed Git blobs
# from the prior standalone AttoLang manifest PR (#2486).
while IFS='|' read -r path pinned; do
  actual="$root/$path"
  [[ -f "$actual" && ! -L "$actual" ]] ||
    die "missing or symlinked canonical .atto sample: $path"
  [[ "$(git hash-object "$actual")" == "$pinned" ]] ||
    die "canonical sample differs from the reviewed source: $path"
done <<'SAMPLES'
examples/atto-canonical/.atto.pkg.toml|7fd95c537574f33ed267bb4f165fa37c6ef65eb3
examples/atto-canonical/src/main.atto|0e63a031e3570e4d32ed8027ee86dead4c72a5dc
examples/atto-canonical/src/value.atto|fe9cfb1fda24f127aa762d626c59aa08204dc94d
SAMPLES

if [[ "$#" -eq 0 ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  cp "$config" "$tmp/config"
  sed -i 's/\.atto\.pkg\.toml/\.atto\.pkg\.disabled/g' "$tmp/config"
  if bash "$0" "$tmp/config" "$reader" "$launcher" >/dev/null 2>&1; then
    die "accepted canonical manifest rename mutation"
  fi

  cp "$config" "$tmp/config"
  sed -i 's/readPinnedAttoManifestUtf8(/readUtf8(/g' "$tmp/config"
  if bash "$0" "$tmp/config" "$reader" "$launcher" >/dev/null 2>&1; then
    die "accepted unsafe canonical manifest reader mutation"
  fi

  cp "$config" "$tmp/config"
  sed -i 's/? List.of("\.atto") : List.of("\.atto", "\.ores", "\.java")/? List.of(".ores") : List.of(".atto", ".ores", ".java")/g' "$tmp/config"
  if bash "$0" "$tmp/config" "$reader" "$launcher" >/dev/null 2>&1; then
    die "accepted silent .ores import fallback mutation"
  fi

  cp "$launcher" "$tmp/launcher"
  sed -i 's/usage: attolang/usage: oreslang-compiler/g' "$tmp/launcher"
  if bash "$0" "$config" "$reader" "$tmp/launcher" >/dev/null 2>&1; then
    die "accepted CLI identity downgrade"
  fi
fi

echo 'PASS: AttoLang canonical manifest, no-follow import, CLI and three pinned sample blobs (source-only)'
