import { mkdirSync, mkdtempSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const verifier = resolve(process.argv[2] ?? 'scripts/VerifyNativeGuestReports.java');
const suites = [
  {
    name: 'dev.oreslang.SourceActorStepWorkflowExecutionTest',
    cases: [
      'executesThreeIndependentStepActorsInSequence',
      'executesTwoStepActorsAndJoinsTheirOutputs',
      'rejectsInvalidStepInputBeforeStartingDownstreamActors',
      'executesCoordinatorActorWithGuardedForkJoinAndDuplicateReceipt'
    ]
  },
  {
    name: 'dev.oreslang.SourceActorDemoWorkflowExecutionTest',
    cases: [
      'executesAcceptedOrderForkAndJoin',
      'rejectsInvalidOrderBeforeDownstreamWork',
      'executesMemoryFixtureCaptureVerificationAndIndex',
      'quarantinesUntrustedEvidenceWithoutIndex',
      'executesReviewFixtureToPreviewOnly',
      'staysWaitingWithoutApprovalFixture',
      'boundsRetryToTwoDistinctActors',
      'stopsBeforeWorkOnCancellation',
      'rejectsForeignDuplicateAndOutOfOrderMessages',
      'executesSagaSuccessWithNoCompensation',
      'compensatesKnownNoEffectRejectionInReverseOrder',
      'quarantinesAmbiguousSagaOutcomeWithoutCompensation',
      'doesNotReleaseInventoryAfterUnconfirmedPaymentVoid',
      'rejectsDuplicateAndForeignEffectReceiptsAndBlocksUnknownCancellation'
    ]
  }
];

function xml(suite) {
  return `<testsuite name="${suite.name}" tests="${suite.cases.length}" errors="0" failures="0" skipped="0" time="0.1">` +
    suite.cases.map(name => `<testcase classname="${suite.name}" name="${name}" time="0.01"/>`).join('') +
    '</testsuite>';
}

const cases = [
  ['valid', s => s, true],
  ['missing counter', s => s.replace('errors="0"', ''), false],
  ['counter spoof', s => s.replace('failures="0"', 'failures="1" fakefailures="0"'), false],
  ['wrong suite', s => s.replace('name="dev.', 'name="wrong.'), false],
  ['partial cases', s => s.replace(/<testcase[^>]+\/>/, ''), false],
  ['duplicate cases', s => s.replace(
    'name="executesTwoStepActorsAndJoinsTheirOutputs"',
    'name="executesThreeIndependentStepActorsInSequence"'), false],
  ['substituted testcase identity', s => s.replace(
    'name="executesThreeIndependentStepActorsInSequence"',
    'name="unexpectedButUniqueCase"'), false],
  ['wrong class', s => s.replace('classname="dev.', 'classname="wrong.'), false],
  ['skipped child', s => s.replace('/>', '><skipped/></testcase>'), false],
  ['failure child', s => s.replace('/>', '><failure/></testcase>'), false],
  ['error child', s => s.replace('/>', '><error/></testcase>'), false],
  ['malformed', s => s.slice(0, -1), false],
  ['doctype', s => '<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/hostname">]>' + s, false],
  ['wrong total', s => s.replace('tests="4"', 'tests="3"'), false],
  ['nested cases', s => s.replace(/(<testcase[^>]+\/>)/, '<wrapper>$1</wrapper>'), false],
  ['missing report', s => s, false],
  ['symlink report', s => s, false],
  ['symlink report directory', s => s, false],
  ['external entity payload', s =>
    '<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/hostname">]>' +
    s.replace('</testsuite>', '<system-out>&x;</system-out></testsuite>'), false],
  ['testcase status spoof', s => s.replace('<testcase ', '<testcase status="skipped" '), false],
  ['unexpected extra report', s => s, false],
  ['missing demo report', s => s, false],
  ['oversized report', s => s + ' '.repeat(8 * 1024 * 1024), false],
  ['demo suite partial', s => s, false],
  ['nested suite', s => s.replace('</testsuite>', '<testsuite name="injected"/></testsuite>'), false],
  ['missing suite time', s => s.replace(' time="0.1"', ''), false],
  ['negative suite time', s => s.replace('time="0.1"', 'time="-0.1"'), false],
  ['invalid suite time', s => s.replace('time="0.1"', 'time="NaN"'), false],
  ['missing testcase time', s => s.replace(' time="0.01"', ''), false],
  ['infinite testcase time', s => s.replace('time="0.01"', 'time="Infinity"'), false],
  ['oversized testcase time', s => s.replace('time="0.01"', 'time="1e999"'), false]
];

for (const [label, mutate, expected] of cases) {
  const base = mkdtempSync(join(tmpdir(), 'native-guest-reports-'));
  const reportDir = join(base, 'reports');
  mkdirSync(reportDir);
  let verifierDir = reportDir;
  try {
    const paths = suites.map(suite => join(reportDir, `TEST-${suite.name}.xml`));
    suites.forEach((suite, i) => writeFileSync(
      paths[i], i ? (label === 'demo suite partial' ? xml(suite).replace(/<testcase[^>]+\/>/, '') : xml(suite)) : mutate(xml(suite))));
    if (label === 'missing report') rmSync(paths[0]);
    if (label === 'missing demo report') rmSync(paths[1]);
    if (label === 'symlink report') {
      rmSync(paths[0]);
      symlinkSync(paths[1], paths[0]);
    }
    if (label === 'symlink report directory') {
      verifierDir = join(base, 'reports-link');
      symlinkSync(reportDir, verifierDir, 'dir');
    }
    if (label === 'unexpected extra report') {
      writeFileSync(join(reportDir, 'TEST-other.xml'), xml(suites[0]));
    }
    const result = spawnSync('java', [verifier, verifierDir],
      {encoding: 'utf8', timeout: 15000});
    assert.equal(result.error, undefined, label);
    assert.equal(result.status === 0, expected, `${label}: ${result.stderr}`);
    console.log(`PASS ${label}`);
  } finally {
    rmSync(base, {recursive: true, force: true});
  }
}
console.log(`REPORT_VALIDATOR_TESTS_PASS cases=${cases.length}`);
