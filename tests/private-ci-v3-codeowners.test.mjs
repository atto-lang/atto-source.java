import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Source-free regression only: this does not establish enforced branch protection.
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REQUIRED = [
  '/.github/CODEOWNERS', '/.github/private-ci-approved-repositories.txt',
  '/.github/private-ci-carrier-contracts.json',
  '/.github/workflows/private-ci-mirror-preflight.yml',
  '/scripts/check-private-ci-destination.sh',
  '/scripts/run-in-private-ci-destination.sh',
  '/scripts/test-private-ci-destination.sh',
  '/scripts/publish-private-ci-source.py',
  '/scripts/publish-private-ci-source-v3.py',
  '/scripts/test-publish-private-ci-source.py',
  '/scripts/test-publish-private-ci-source-v3.py',
  '/tools/verify-exact-source-tree.sh',
  '/tools/test-verify-exact-source-tree.sh', '/pom.xml',
  '/tests/private-ci-v3-codeowners.test.mjs',
];
function parseRules(contents) {
  const rules = [];
  for (const line of contents.split(/\r?\n/u).map(x => x.trim())) {
    if (!line || line.startsWith('#')) continue;
    const tokens = line.split(/\s+/u);
    // Reject unknown CODEOWNERS syntax instead of assuming it cannot override.
    if (tokens.length < 2 || !/^(?:\*|\/[A-Za-z0-9._/-]+)$/u.test(tokens[0])
        || tokens[0].includes('//') || tokens[0].includes('/../')
        || tokens[0].includes('/./')
        || tokens.slice(1).some(x => !/^@[A-Za-z0-9-]+$/u.test(x))) {
      throw new Error(`unmodeled or malformed CODEOWNERS rule: ${line}`);
    }
    rules.push({ pattern: tokens[0], owners: tokens.slice(1) });
  }
  if (!rules.length) throw new Error('CODEOWNERS has no enforceable rules');
  return rules;
}
function matches(pattern, pathname) {
  return pattern === '*' || (pattern.endsWith('/')
    ? pathname.startsWith(pattern) : pathname === pattern);
}
function assertV3Owners(contents) {
  const rules = parseRules(contents);
  for (const filename of REQUIRED) {
    let owners;
    for (const rule of rules) if (matches(rule.pattern, filename)) owners = rule.owners;
    assert.deepEqual(owners, ['@ORESoftware'],
      `${filename} must have terminal independent review ownership`);
  }
}
const good = '* @ORESoftware\n/.github/ @ORESoftware\n/scripts/ @ORESoftware\n/tools/ @ORESoftware\n/pom.xml @ORESoftware\n';
test('checked-in critical V3 files retain final security-owner rules', () => {
  assertV3Owners(readFileSync(path.join(ROOT, '.github/CODEOWNERS'), 'utf8'));
});
test('reviewed ancestor directories protect critical files', () => assertV3Owners(good));
test('last matching publisher override is rejected', () => {
  assert.throws(() => assertV3Owners(good + '/scripts/publish-private-ci-source-v3.py @unreviewed\n'),
    /terminal independent review/u);
});
test('last matching workflow override is rejected', () => {
  assert.throws(() => assertV3Owners(good + '/.github/workflows/private-ci-mirror-preflight.yml @unreviewed\n'),
    /terminal independent review/u);
});
test('exact-tree verifier without codeowner is rejected', () => {
  assert.throws(() => assertV3Owners(good.replace('* @ORESoftware\n', '').replace('/tools/ @ORESoftware\n', '')),
    /terminal independent review/u);
});
test('unknown CODEOWNERS globs cannot silently bypass the audit', () => {
  assert.throws(() => assertV3Owners(good + '/scripts/** @unreviewed\n'), /unmodeled/u);
});
test('multiple owners or unreviewed final owner are rejected', () => {
  assert.throws(() => assertV3Owners(good + '/pom.xml @ORESoftware @unreviewed\n'),
    /terminal independent review/u);
});
test('empty CODEOWNERS has no approval authority', () => {
  assert.throws(() => assertV3Owners('# only comments\n'), /no enforceable/u);
});
