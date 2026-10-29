import { mkdtempSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const verifier = resolve(process.argv[2] ?? 'scripts/VerifyNativeGuestReports.java');
const names = ['dev.oreslang.SourceActorStepWorkflowExecutionTest', 'dev.oreslang.SourceActorDemoWorkflowExecutionTest'];
const counts = [4, 14];
function xml(suite, count) {
  return `<testsuite name="${suite}" tests="${count}" errors="0" failures="0" skipped="0">` +
    Array.from({length: count}, (_, i) => `<testcase classname="${suite}" name="case${i}"/>`).join('') + '</testsuite>';
}
const cases = [
  ['valid', s => s, true],
  ['missing counter', s => s.replace('errors="0"', ''), false],
  ['counter spoof', s => s.replace('failures="0"', 'failures="1" fakefailures="0"'), false],
  ['wrong suite', s => s.replace('name="dev.', 'name="wrong.'), false],
  ['partial cases', s => s.replace(/<testcase[^>]+\/>/, ''), false],
  ['duplicate cases', s => s.replace('name="case1"', 'name="case0"'), false],
  ['wrong class', s => s.replace('classname="dev.', 'classname="wrong.'), false],
  ['skipped child', s => s.replace('/>', '><skipped/></testcase>'), false],
  ['failure child', s => s.replace('/>', '><failure/></testcase>'), false],
  ['error child', s => s.replace('/>', '><error/></testcase>'), false],
  ['malformed', s => s.slice(0, -1), false],
  ['doctype', s => '<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/hostname">]>' + s, false],
  ['wrong total', s => s.replace('tests="4"', 'tests="3"'), false],
  ['nested cases', s => s.replace(/(<testcase[^>]+\/>)/, '<wrapper>$1</wrapper>'), false],
  ['missing report', s => s, false],
  ['symlink report', s => s, false]
];
for (const [label, mutate, expected] of cases) {
  const dir = mkdtempSync(join(tmpdir(), 'native-guest-reports-'));
  try {
    const paths = names.map(n => join(dir, `TEST-${n}.xml`));
    names.forEach((n, i) => writeFileSync(paths[i], i ? xml(n, counts[i]) : mutate(xml(n, counts[i]))));
    if (label === 'missing report') rmSync(paths[0]);
    if (label === 'symlink report') { rmSync(paths[0]); symlinkSync(paths[1], paths[0]); }
    const result = spawnSync('java', [verifier, dir], {encoding: 'utf8', timeout: 15000});
    assert.equal(result.error, undefined, label);
    assert.equal(result.status === 0, expected, `${label}: ${result.stderr}`);
    console.log(`PASS ${label}`);
  } finally { rmSync(dir, {recursive: true, force: true}); }
}
console.log(`REPORT_VALIDATOR_TESTS_PASS cases=${cases.length}`);
