// Source-only lexical guard. This does not compile or execute Atto guest code.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const runtime = read('stdlib/std/assert.atto');
const checks = read('stdlib/std/test_assert.atto');
const registry = read('src/main/java/dev/oreslang/stdlib/CoreLibrarySources.java');

test('runtime assertions throw typed guest errors, never native failure', () => {
  assert.match(runtime, /class AssertionError extends Error/);
  assert.equal(runtime.split('throw new AssertionError(').length - 1, 5);
  for (const forbidden of ['rt fail(', 'abort(', 'panic(', 'raise(']) {
    assert.equal(runtime.includes(forbidden), false, forbidden);
  }
});

test('expected-first homogeneous Eq and qualified functions', () => {
  for (const fragment of [
    'define module assert as',
    'pub fnc true(bool condition): void',
    'pub fnc false(bool condition): void',
    'pub fnc equal<T: Eq>(T expected, T actual): void',
    'pub fnc not_equal<T: Eq>(T expected, T actual): void',
    'pub fnc fail(String message): void',
    'expected eq actual'
  ]) assert.ok(runtime.includes(fragment), fragment);
  assert.equal(runtime.includes('(Any expected, Any actual)'), false);
});

test('nonthrowing test assertions preserve TestOutcome and safe range bounds', () => {
  assert.ok(checks.includes("import class TestOutcome from 'ores.std/testing'"));
  assert.ok(checks.includes("import fnc pass, fail from 'ores.std/testing'"));
  assert.equal(checks.includes('define pub class TestOutcome'), false);
  for (const fragment of [
    'lower_inclusive > upper_inclusive',
    'actual < lower_inclusive',
    'actual > upper_inclusive',
    'return fail(message)'
  ]) assert.ok(checks.includes(fragment), fragment);
  assert.equal(checks.includes('rt fail('), false);
});

test('closed source registry retains legacy module', () => {
  for (const path of ['"std/assert.atto"', '"std/test_assert.atto"', '"assert.atto"']) {
    assert.ok(registry.includes(path), path);
  }
});

test('authored guest cases explicitly remain unexecuted', () => {
  for (const name of ['pass', 'fail-unequal', 'reject-mixed-eq', 'test-outcome']) {
    assert.ok(read('tests/fixtures/standard-assert-v1/' + name + '.atto').includes('NOT_EXECUTED'));
  }
  const positive = read('tests/fixtures/standard-assert-v1/pass.atto');
  assert.ok(positive.includes('import module assert from pkg "std.assert"'));
  for (const member of ['assert.true(', 'assert.false(', 'assert.equal(', 'assert.not_equal(']) {
    assert.ok(positive.includes(member), member);
  }
});
