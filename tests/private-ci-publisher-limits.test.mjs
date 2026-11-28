import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// SOURCE-ONLY review/test of an existing V3 publisher bound. This is never
// permission to export private source, and does not interact with GitHub.
const publisher = readFileSync(
  new URL('../scripts/publish-private-ci-source.py', import.meta.url), 'utf8');
const policyDoc = readFileSync(
  new URL('../docs/security/CI_PRIVATE_SOURCE_MIRRORING.md', import.meta.url), 'utf8');

function constant(name) {
  const matches = [...publisher.matchAll(
    new RegExp('^' + name + ' = ([^\\r\\n#]+)$', 'gm'))];
  assert.equal(matches.length, 1, 'expected one Python constant ' + name);
  return matches[0][1].trim();
}

test('published limits and reviewed documentation agree', () => {
  assert.equal(constant('MAX_FILES'), '4096');
  assert.equal(constant('MAX_BYTES'), '40 * 1024 * 1024');
  assert.equal(constant('MAX_SINGLE'), '10 * 1024 * 1024');
  assert.equal(constant('MAX_DIRECTORY_ENTRIES'), '4096');
  assert.equal(constant('MAX_DIRECTORIES'), '1024');
  assert.match(policyDoc,
    /bounds the export to 4,096 files \/ 40 MiB total \/ 10 MiB\s+per file/u);
  assert.doesNotMatch(policyDoc, /bounds the export to 1,024 files/u);
});

test('observed numerical tree fits bounds without implying authorization', () => {
  // 2026-11-21 pinned candidate, not a live-origin Git-tree check.
  const observed = { files: 2906, bytes: 20_904_978 };
  assert.ok(observed.files <= 4096);
  assert.ok(observed.bytes <= 40 * 1024 * 1024);
  assert.match(policyDoc, /Size eligibility is not export authorization/u);
  assert.match(policyDoc, /independent security-owner review/u);
});

test('the legacy publisher stays non-operational', () => {
  assert.match(publisher, /legacy private-source publisher .*?disabled/gsu);
  assert.match(publisher, /use run-in-private-ci-destination\.sh \(V3\)/u);
});
