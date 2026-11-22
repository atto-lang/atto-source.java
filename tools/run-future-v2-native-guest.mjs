#!/usr/bin/env node
// Source-local Java/GraalVM Native Image gate for conformance #206.
// FAIL CLOSED. No Oreslang guest parity is claimed without observed execution.
// No cross-repo checkout, source export, Python, remote compiler or fake adapter.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { constants, openSync, fstatSync, readSync, closeSync, mkdtempSync,
  writeFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const root = fileURLToPath(new URL('../', import.meta.url));
const cases = [
  ['flat_map_one_layer', 'db7006ae3b564c8ebcb80680ac113a72c065f507', 'positive', '42'],
  ['map_preserves_nested', 'c62fbae210bc2c501cb1b30a6887219086f78391', 'positive', '42'],
  ['flatten_one_layer', 'ce032c78f574d0d61f0496a719d881af63dc580c', 'positive', '42'],
  ['flat_map_preserves_deeper_nesting', '2cf2bff1a2f516fde2b8f85272bacf52de180d55', 'positive', '84'],
  ['reject_flat_map_scalar', '5f9f51d91f32bb1a7154f7bc1d1a4ece1015d94c', 'reject', 'FUTURE_CALLBACK_NOT_FUTURE'],
  ['reject_implicit_map_flatten', '8a2f07c2453763ed585462556cf1c4c5330774fd', 'reject', 'WRAPPER_LAYER_MISMATCH'],
  ['reject_one_await_flatten', 'e99a49f2b30964cbdbba4cb6250859fbc1180fd6', 'reject', 'WRAPPER_LAYER_MISMATCH'],
  ['reject_flat_map_deeper_nesting', '979ea56ccb11c8e90ca2dcc7029ff081364eb8e4', 'reject', 'WRAPPER_LAYER_MISMATCH']
];
const hash = (algo, data) => createHash(algo).update(data).digest('hex');
const blobSha = data => createHash('sha1')
  .update(`blob ${data.length}\0`).update(data).digest('hex');
function readRegular(file, maxBytes) {
  assert.ok(Number.isInteger(constants.O_NOFOLLOW) && Number.isInteger(constants.O_NONBLOCK),
    'safe source admission unsupported');
  const fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const st = fstatSync(fd);
    assert.ok(st.isFile() && st.size > 0 && st.size <= maxBytes,
      `not a bounded regular file: ${file}`);
    const bytes = Buffer.alloc(st.size);
    let offset = 0;
    while (offset < bytes.length) {
      const n = readSync(fd, bytes, offset, bytes.length - offset, offset);
      assert.ok(n > 0, `file truncated: ${file}`);
      offset += n;
    }
    assert.equal(readSync(fd, Buffer.alloc(1), 0, 1, offset), 0,
      `file grew during read: ${file}`);
    return bytes;
  } finally { closeSync(fd); }
}
function extractFixture(java, name, expectedBlobSha) {
  // Require precisely one matching @Test method with a single Java text block.
  const anchor = `void ${name}() throws Exception {`;
  const start = java.indexOf(anchor);
  assert.ok(start >= 0 && java.indexOf(anchor, start + anchor.length) < 0,
    `missing/duplicate exact fixture method: ${name}`);
  const opening = 'String source = """\n';
  const begin = java.indexOf(opening, start);
  assert.ok(begin > start && begin < java.indexOf('\n    }', start),
    `missing Java text block: ${name}`);
  const first = begin + opening.length;
  const end = java.indexOf('\n            """;', first);
  assert.ok(end > first && end < java.indexOf('\n    }', start),
    `unterminated Java text block: ${name}`);
  const lines = java.slice(first, end).split('\n');
  assert.ok(lines.every(line => line.startsWith('            ')),
    `text block indentation drift: ${name}`);
  const bytes = Buffer.from(lines.map(line => line.slice(12)).join('\n') + '\n', 'utf8');
  assert.equal(blobSha(bytes), expectedBlobSha, `Git blob fixture drift: ${name}`);
  return bytes;
}
function diagnosticMatches(category, stderr) {
  if (category === 'FUTURE_CALLBACK_NOT_FUTURE')
    return /Future\.flat_map callback must return Future<T>/.test(stderr)
      || /\bFUTURE_CALLBACK_NOT_FUTURE\b/.test(stderr);
  if (category === 'WRAPPER_LAYER_MISMATCH')
    return /\bWRAPPER_LAYER_MISMATCH\b/.test(stderr)
      || /initializer for invalid has type [^\n]+Future[^\n]+but expected/.test(stderr);
  return false;
}
function run(binary, workingDir, source, check) {
  const args = check ? ['--no-prompt', '--check', source] : ['--no-prompt', source];
  const r = spawnSync(binary, args, {
    cwd: workingDir, timeout: 30000, encoding: 'utf8', maxBuffer: 262144,
    env: { PATH: '/usr/bin:/bin', HOME: workingDir, TMPDIR: workingDir,
      LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', CI: 'true', NO_COLOR: '1' }
  });
  return { exit: r.status, signal: r.signal, error: r.error?.code ?? null,
    stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
}
function main(argv) {
  assert.equal(argv.length, 4,
    'usage: node tools/run-future-v2-native-guest.mjs --compiler /abs/target/ores-aot --sha256 64_HEX');
  assert.equal(argv[0], '--compiler');
  assert.equal(argv[2], '--sha256');
  assert.ok(path.isAbsolute(argv[1]), 'absolute compiler path required');
  assert.match(argv[3], /^[a-f0-9]{64}$/);
  const original = readRegular(argv[1], 1024 * 1024 * 1024);
  const magic = original.subarray(0, 4).toString('hex');
  assert.ok(magic === '7f454c46', 'source-local gate requires Linux ELF Native Image, not a mock script/JAR');
  assert.equal(hash('sha256', original), argv[3], 'compiler SHA-256 mismatch');
  const testBytes = readRegular(path.join(root, 'src/test/java/dev/oreslang/FutureChainingV2PinnedGuestTest.java'), 128 * 1024);
  const java = testBytes.toString('utf8');
  assert.deepEqual(Buffer.from(java, 'utf8'), testBytes, 'invalid UTF-8 Java fixture');
  const dir = mkdtempSync(path.join(tmpdir(), 'ores-future-v2-native-'));
  const report = {
    contract: 'future-chaining-explicit-layers-v2',
    backend: 'java-graalvm-native-aot',
    compilerSha256: argv[3],
    pinnedJUnitSourceSha256: hash('sha256', testBytes),
    status: 'NOT_EXECUTED',
    evidenceClass: 'native-guest-execution-only; independently attest build SHA/tree and runner',
    cases: []
  };
  try {
    // All eight source blobs are reconstructed only from the pinned Java test
    // after the workflow verifies its same-repo Git head/tree and checkout.
    for (const [id, blob, kind, expected] of cases) {
      const sourceBytes = extractFixture(java, id, blob);
      const source = path.join(dir, `${id}.ores`);
      writeFileSync(source, sourceBytes, { flag: 'wx', mode: 0o400 });
      const check = run(argv[1], dir, source, true);
      const item = { id, sourceGitBlobSha1: blob,
        checkExit: check.exit, checkError: check.error,
        checkSignal: check.signal, checkStderr: check.stderr.slice(0, 2048), passed: false };
      if (kind === 'reject') {
        item.passed = check.exit !== null && check.exit !== 0 &&
          !check.error && !check.signal && check.stdout === '' &&
          diagnosticMatches(expected, check.stderr);
      } else {
        if (check.exit === 0 && !check.error && !check.signal && check.stdout === '') {
          const result = run(argv[1], dir, source, false);
          item.guestExit = result.exit;
          item.guestSignal = result.signal;
          item.guestError = result.error;
          item.guestStdout = result.stdout.slice(0, 2048);
          item.guestStderr = result.stderr.slice(0, 2048);
          item.passed = result.exit === 0 && !result.error && !result.signal &&
            result.stdout === expected;
        }
      }
      report.cases.push(item);
    }
    report.status = report.cases.length === 8 && report.cases.every(x => x.passed)
      ? 'NATIVE_GUEST_EXECUTION_OBSERVED_NOT_RELEASE_AUTHORIZED' : 'FAILED';
  } finally {
    rmSync(dir, { force: true, recursive: true });
  }
  process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  if (report.status === 'FAILED') process.exitCode = 1;
}
try { main(process.argv.slice(2)); }
catch (error) {
  process.stderr.write(`FUTURE_V2_GUEST_REJECTED: ${error.message}\n`);
  process.exitCode = 1;
}
