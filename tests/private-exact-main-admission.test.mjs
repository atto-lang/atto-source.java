import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';

const yaml = readFileSync(new URL('../.github/workflows/private-exact-main-validation.yml', import.meta.url), 'utf8');
const owners = readFileSync(new URL('../.github/CODEOWNERS', import.meta.url), 'utf8');
const preflight = readFileSync(new URL('../.github/workflows/private-ci-mirror-preflight.yml', import.meta.url), 'utf8');
const incidentGovernance = existsSync(new URL(
  '../.github/private-ci-incident-dispositions.json', import.meta.url));

// Git object identity is deliberately security-reviewed: updating any workflow
// command, job, env, action parameter, or YAML field requires updating this pin
// in the same independently reviewed change. Keep semantic assertions as defense
// in depth rather than treating the hash as a substitute for policy review.
function exactGitBlob(text) {
  return createHash('sha1')
    .update('blob ' + Buffer.byteLength(text, 'utf8') + String.fromCharCode(0))
    .update(text, 'utf8').digest('hex');
}

function verify(w, c, incidentControlled = incidentGovernance) {
  assert.equal(exactGitBlob(w), '794850b5ca8b66de3a263953eaf58ec0750f13f1',
    'unreviewed exact-main workflow bytes: update the immutable pin only with independent review');
  // A duplicate YAML root key (especially on/jobs/permissions) can shadow
  // the reviewed block. Fail closed before checking individual sections.
  // This workflow intentionally has one fixed, minimal root shape.
  const rootLines = w.split('\n').filter(line =>
    line.length > 0 && !/^[ \t]/.test(line) && !line.startsWith('#'));
  assert.deepEqual(rootLines, [
    'name: Private exact-main Java/Native validation',
    'on:',
    'permissions:',
    'concurrency:',
    'jobs:',
  ], 'unexpected, duplicate, or replaced root-level YAML field');
  assert.doesNotMatch(w, /^\t/m, 'YAML indentation must not contain tabs');
  const section = (start, end) => {
    const a = w.indexOf(start);
    const b = w.indexOf(end, a + start.length);
    assert.ok(a >= 0 && b > a, 'missing workflow section');
    return w.slice(a, b).trim();
  };
  assert.equal(section('\non:\n', '\npermissions:'), 'on:\n  workflow_dispatch:');
  assert.equal(w.split('\npermissions:\n').length - 1, 1, 'exactly one global permissions block');
  assert.match(w, /^permissions:\n  contents: read$/m, 'workflow token must be read-only');
  assert.doesNotMatch(w, /^[ \t]+(?:permissions|pull_request|pull_request_target|push):/m);
  assert.match(w, /\nconcurrency:\n  group: private-oreslang-isolated-validation\n  cancel-in-progress: false\n/);
  const jobs = w.split('\njobs:\n')[1];
  assert.ok(jobs);
  assert.deepEqual([...jobs.matchAll(/^  ([a-z][a-z0-9-]*):$/gm)].map(m => m[1]),
    ['deny-unapproved', 'validate-exact-main']);
  const deny = jobs.split('  validate-exact-main:\n')[0];
  const allow = jobs.split('  validate-exact-main:\n')[1];
  assert.ok(deny && allow);
  const repo = 'ores-truffle-oreslang/oreslang-source.java';
  const denyClauses = [
    "github.repository != '" + repo + "'",
    'github.event.repository.id != 1400792274',
    'github.event.repository.private != true',
    "github.ref != 'refs/heads/main'",
    'github.ref_protected != true',
    "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER != 'true'",
    "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == ''",
    'vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != github.sha',
    "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE == ''",
  ];
  const allowClauses = [
    "github.repository == '" + repo + "'",
    'github.event.repository.id == 1400792274',
    'github.event.repository.private == true',
    "github.ref == 'refs/heads/main'",
    'github.ref_protected == true',
    "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'",
    "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != ''",
    'vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha',
    "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != ''",
  ];
  const jobIf = v => {
    const fields = [...v.matchAll(/^    if: (.+)$/gm)];
    assert.equal(fields.length, 1, 'exactly one executable if condition');
    return fields[0][1];
  };
  assert.equal(jobIf(deny), '$' + '{{ ' + denyClauses.join(' || ') + ' }}');
  assert.equal(jobIf(allow), '$' + '{{ ' + allowClauses.join(' && ') + ' }}');
  const jobRunner = v => {
    const fields = [...v.matchAll(/^    runs-on: (.+)$/gm)];
    assert.equal(fields.length, 1, 'exactly one runner binding');
    return fields[0][1];
  };
  assert.equal(jobRunner(deny), 'ubuntu-24.04');
  assert.doesNotMatch(deny, /actions\/checkout@/);
  assert.match(deny, /^\s+exit 1$/m);
  assert.equal(jobRunner(allow), '[self-hosted, linux, x64, oreslang-private-ephemeral]');
  // Require every executable step in reviewed order, including pinned actions.
  // The old contract only rejected unreviewed steps before checkout.
  const trustedStepHeaders = [
    'name: Verify exact-main admission before source checkout',
    'uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4',
    'name: Verify source identity and immutable Git tree before tool setup',
    'name: Verify Node.js 22 for private CI admission regression',
    'name: Execute exact-main source-free admission and mutation regression',
    'uses: graalvm/setup-graalvm@27b360dbb857524eaf0b4c4cef364d38e5b4f173 # v1',
    'name: Verify toolchain',
    'name: Focused Future/select and exception-isolation regression suites',
    'name: Complete GraalVM JDK 25 Maven verification',
    'name: Prove fresh exact-main full JUnit suite execution',
    'name: Ten deterministic Future/channel stress replays',
    'name: Native AOT image and compiled guest smoke',
    'name: Native hybrid image and compiled guest smoke',
    'name: Strict polyglot isolate and compiled guest smoke',
  ];
  assert.deepEqual([...allow.matchAll(/^      - ([^\n]+)$/gm)].map(m => m[1]),
    trustedStepHeaders, 'unreviewed executable step or changed step order');
  assert.doesNotMatch(allow, /^        (?:uses|name):/m,
    'nested step action/name cannot shadow an approved step');
  const stepMarker = '\n    steps:\n';
  const stepStart = allow.indexOf(stepMarker);
  const checkoutStep = allow.indexOf('      - uses: actions/checkout@');
  assert.ok(stepStart >= 0 && checkoutStep > stepStart,
    'missing ordered admission/checkout steps');
  const precheckout = allow.slice(stepStart + stepMarker.length, checkoutStep);
  assert.equal(precheckout.trimStart().split('\n')[0].trim(),
    '- name: Verify exact-main admission before source checkout',
    'first executable step must be the trusted admission guard');
  assert.equal([...precheckout.matchAll(/^      - /gm)].length, 1,
    'no executable command or action may precede admitted checkout');
  const requiredPreflight = [
    'set -euo pipefail',
    "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'",
    "test \"$GITHUB_REF_PROTECTED\" = 'true'",
    'test "$GITHUB_SHA" = "$APPROVED_SHA"',
  ];
  for (const line of requiredPreflight) {
    assert.ok(precheckout.split('\n').some(v => v.trim() === line),
      'missing executable pre-checkout guard: ' + line);
  }
  const guard = allow.indexOf('Verify exact-main admission before source checkout');
  const checkout = allow.indexOf('actions/checkout@');
  const source = allow.indexOf('Verify source identity and immutable Git tree');
  const setup = allow.indexOf('graalvm/setup-graalvm@');
  const nodeVersion = allow.indexOf('Verify Node.js 22 for private CI admission regression');
  const nodeTest = allow.indexOf('Execute exact-main source-free admission and mutation regression');
  assert.ok(guard >= 0 && guard < checkout && checkout < source &&
    source < nodeVersion && nodeVersion < nodeTest && nodeTest < setup,
    'trusted identity and security regressions must precede GraalVM tool setup');
  assert.ok(allow.split('\n').some(v => v.trim() === "test \"$(node -p 'process.versions.node.split(\".\")[0]')\" = '22'"),
    'exact Node 22 version gate missing');
  assert.ok(allow.split('\n').some(v => v.trim() === 'node --test tests/private-exact-main-admission.test.mjs'),
    'mandatory source-free regression execution missing');
  for (const line of [
    "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'",
    "test \"$GITHUB_REF_PROTECTED\" = 'true'",
    'test "$GITHUB_SHA" = "$APPROVED_SHA"',
    'test "$(git rev-parse --verify HEAD^{tree})" = "$APPROVED_TREE"',
    'test -z "$(git status --porcelain --untracked-files=all)"',
  ]) assert.ok(allow.split('\n').some(v => v.trim() === line), 'missing ' + line);
  assert.match(allow, /actions\/checkout@11bd71901bbe5b1630ceea73d27597364c9af683/);
  assert.match(allow, /persist-credentials: false/);
  for (const step of [
    '-DskipTests=false -Dmaven.test.skip=false clean verify',
    'Prove fresh exact-main full JUnit suite execution',
    '-Pnative-aot -DskipTests clean package',
    '-Pnative-hybrid -DskipTests clean package',
    '-Pnative-isolate -DskipTests clean package',
  ]) assert.ok(allow.includes(step), 'missing phase ' + step);
  assert.ok(c.split('\n').includes('/tests/private-exact-main-admission.test.mjs @ORESoftware'));
  assert.ok(c.split('\n').includes('/.github/workflows/private-exact-main-validation.yml @ORESoftware'));
  // V3 incident governance adds one independently owned terminal rule.
  // A seven-rule checkout must never be accepted when incident governance
  // exists, and an eight-rule checkout must never pass before it exists.
  const incidentRule = '/.github/private-ci-incident-dispositions.json @ORESoftware';
  const tail = [
    '/.github/CODEOWNERS @ORESoftware',
    '/.github/private-ci-approved-repositories.txt @ORESoftware',
    ...(incidentControlled ? [incidentRule] : []),
    '/.github/private-ci-carrier-contracts.json @ORESoftware',
    '/scripts/check-private-ci-destination.sh @ORESoftware',
    '/scripts/run-in-private-ci-destination.sh @ORESoftware',
    '/scripts/publish-private-ci-source.py @ORESoftware',
    '/scripts/publish-private-ci-source-v3.py @ORESoftware',
  ];
  const codeownerRules = c.trimEnd().split('\n').filter(line =>
    line.trim() && !line.trimStart().startsWith('#'));
  assert.equal(codeownerRules.filter(line =>
    line.startsWith('/.github/private-ci-incident-dispositions.json ')).length,
  incidentControlled ? 1 : 0, 'incident governance ownership mismatch');
  assert.deepEqual(codeownerRules.slice(-tail.length), tail,
    'missing exact terminal private-export security ownership');
}

test('manual-only trusted exact-main admission', () => verify(yaml, owners));
test('unsafe admission mutations rejected', () => {
  for (const [from, to] of [
    ['  workflow_dispatch:', '  pull_request:\n  workflow_dispatch:'],
    ['  contents: read', '  contents: write'],
    ['github.ref_protected == true', 'github.ref_protected == false'],
    ['github.event.repository.id == 1400792274', 'github.event.repository.id == 1'],
    ['vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha', 'true'],
    ['runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]', 'runs-on: ubuntu-24.04'],
    ['actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683', 'actions/checkout@main'],
    ['persist-credentials: false', 'persist-credentials: true'],
    ['test "$(git rev-parse --verify HEAD^{tree})" = "$APPROVED_TREE"', 'echo unchecked'],
    ['-DskipTests=false -Dmaven.test.skip=false clean verify', '-DskipTests=true package'],
    ['    steps:\n      - name: Verify exact-main admission before source checkout',
     '    steps:\n      - name: Unreviewed preflight\n        run: echo preflight-bypassed\n      - name: Verify exact-main admission before source checkout'],
    ['          test "$GITHUB_SHA" = "$APPROVED_SHA"',
     '          # test "$GITHUB_SHA" = "$APPROVED_SHA"'],
    ['      - name: Verify toolchain',
     '      - name: Unreviewed post-checkout step\n        run: echo unexpected\n      - name: Verify toolchain'],
    ['      - name: Complete GraalVM JDK 25 Maven verification',
     '      - run: echo unexpected\n      - name: Complete GraalVM JDK 25 Maven verification'],
    ['      - name: Native hybrid image and compiled guest smoke',
     '      - name: Native hybrid image and compiled guest smoke\n      - name: Unreviewed trailing step\n        run: echo unexpected'],
    ['      - uses: graalvm/setup-graalvm@27b360dbb857524eaf0b4c4cef364d38e5b4f173 # v1',
     '      - uses: graalvm/setup-graalvm@27b360dbb857524eaf0b4c4cef364d38e5b4f173 # v1\n        uses: actions/checkout@main'],
    ['      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683',
     '      - run: echo unreviewed-before-checkout\n      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683'],
    ["test \"$(node -p 'process.versions.node.split(\".\")[0]')\" = '22'", 'echo no-node-version-gate'],
    ['node --test tests/private-exact-main-admission.test.mjs', 'echo bypassed-node-test'],
    ["vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != '' }}",
     "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != '' }}\n    if: true"],
    ['    runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]',
     '    runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]\n    runs-on: ubuntu-24.04'],
  ]) {
    assert.ok(yaml.includes(from), 'missing mutation anchor');
    assert.throws(() => verify(yaml.replace(from, to), owners), undefined, 'unsafe mutation accepted');
  }
});
test('duplicate and unreviewed root-level YAML fields rejected', () => {
  for (const extra of [
    '\non: [push]\n',
    '\npermissions: {contents: write}\n',
    '\njobs: {rogue: {runs-on: ubuntu-24.04, steps: [{run: echo bypass}]}}\n',
    '\nconcurrency: {group: outsider, cancel-in-progress: true}\n',
    '\ndefaults: {run: {shell: bash}}\n',
    '\n---\n',
  ]) assert.throws(() => verify(yaml + extra, owners), undefined,
    'unreviewed YAML root extension accepted: ' + extra.trim());
  assert.throws(() => verify(yaml.replace('\non:\n  workflow_dispatch:',
    '\non: [push]'), owners));
  assert.throws(() => verify(yaml.replace('\npermissions:\n  contents: read',
    '\npermissions: {contents: write}'), owners));
  assert.throws(() => verify(yaml.replace('\nconcurrency:',
    '\n\tconcurrency:'), owners));
});

test('incident governance transition preserves exact security ownership', () => {
  const incidentRule = '/.github/private-ci-incident-dispositions.json @ORESoftware';
  const next = owners.replace(
    '/.github/private-ci-carrier-contracts.json @ORESoftware',
    incidentRule + '\n/.github/private-ci-carrier-contracts.json @ORESoftware');
  assert.notEqual(next, owners, 'missing insertion anchor');
  // Current standalone PR uses seven final rules; the integrated V3 uses eight.
  assert.doesNotThrow(() => verify(yaml, owners, false));
  assert.doesNotThrow(() => verify(yaml, next, true));
  assert.throws(() => verify(yaml, owners, true),
    undefined, 'incident governance must not accept seven old rules');
  assert.throws(() => verify(yaml, next, false),
    undefined, 'pre-incident governance must not accept an unexpected rule');
  assert.throws(() => verify(yaml, next.replace(incidentRule, ''), true));
  assert.throws(() => verify(yaml, next + '\n* @another-owner\n', true));
});

test('full workflow command, env, with and job settings tampering rejected', () => {
  for (const [from, to] of [
    ["      fetch-depth: 1", "      fetch-depth: 0"],
    ["    timeout-minutes: 180", "    timeout-minutes: 999"],
    ["        shell: bash", "        shell: pwsh"],
    ["          set -euo pipefail", "          set +e"],
    ["          test \"$GITHUB_SHA\" = \"$APPROVED_SHA\"", "          echo unchecked"],
    ["      - name: Verify toolchain", "      - name: Verify toolchain\n        continue-on-error: true"],
    ["      - name: Verify toolchain", "      - name: Verify toolchain\n        env:\n          UNREVIEWED: enabled"],
    ["      - name: Verify toolchain", "      - name: Verify toolchain\n        working-directory: /tmp"],
    ["      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4", "      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4\n        with:\n          repository: unreviewed/repo"],
    ["      - name: Native AOT image and compiled guest smoke", "      - name: Native AOT image and compiled guest smoke\n        run: echo skipped"],
    ["      - name: Complete GraalVM JDK 25 Maven verification", "      - name: Complete GraalVM JDK 25 Maven verification\n        if: false"],
    ["    env:\n      APPROVED_SHA:", "    env:\n      UNREVIEWED: true\n      APPROVED_SHA:"],
    ["      - name: Prove fresh exact-main full JUnit suite execution", "      - name: Prove fresh exact-main full JUnit suite execution\n        timeout-minutes: 1"],
    ["    timeout-minutes: 180", "    timeout-minutes: 180\n    strategy:\n      matrix: {runner: [unreviewed]}"],
  ]) {
    assert.ok(yaml.includes(from), 'missing reviewed workflow mutation anchor: ' + from);
    assert.throws(() => verify(yaml.replace(from, to), owners), undefined,
      'unreviewed executable configuration accepted');
  }
});

function verifyPreflight(p) {
  // Fail closed on any preflight workflow change, including a missed path
  // trigger, run-script modification, or checkout authorization change.
  assert.equal(exactGitBlob(p), 'c1826995c200dfd5ff6e480d64d69d3f67c36f72',
    'unreviewed preflight workflow bytes');
  assert.match(p, /^permissions:\n  contents: read$/m);
  assert.match(p, /^          node --test tests\/private-exact-main-admission\.test\.mjs$/m);
  assert.match(p, /^      - uses: actions\/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09$/m);
  assert.match(p, /^          python3 scripts\/test-publish-private-ci-source-v3\.py$/m);
  const eventBlock = p.slice(p.indexOf('\non:\n') + 1, p.indexOf('\npermissions:\n'));
  assert.ok(eventBlock.startsWith('on:\n  pull_request:\n'));
  assert.equal(eventBlock.split('  pull_request:\n').length - 1, 1);
  assert.equal(eventBlock.split('  push:\n').length - 1, 1);
  const [pr, push] = eventBlock.split('  push:\n');
  for (const block of [pr, push]) {
    assert.match(block, /\n    paths:\n/);
    const paths = [...block.matchAll(/^      - '([^']+)'$/gm)].map(m => m[1]);
    assert.equal(paths.length, new Set(paths).size, 'duplicated private CI trigger');
    for (const critical of [
      '.github/CODEOWNERS',
      '.github/private-ci-incident-dispositions.json',
      '.github/workflows/private-ci-mirror-preflight.yml',
      '.github/workflows/private-exact-main-validation.yml',
      'tests/private-exact-main-admission.test.mjs',
    ]) assert.equal(paths.filter(path => path === critical).length, 1,
      'missing or duplicated trigger path: ' + critical);
  }
}

test('private CI preflight triggers both PR and push on all sensitive edits', () => {
  verifyPreflight(preflight);
  for (const [from, to] of [
    ["      - '.github/workflows/private-ci-mirror-preflight.yml'", ''],
    ["      - '.github/workflows/private-exact-main-validation.yml'", ''],
    ["      - 'tests/private-exact-main-admission.test.mjs'", ''],
    ["      - '.github/private-ci-incident-dispositions.json'", ''],
    ['  push:\n    paths:', '  push:\n    branches: [main]\n    paths:'],
    ['          node --test tests/private-exact-main-admission.test.mjs',
     '          echo no-admission-test'],
    ['          bash scripts/test-private-ci-codeowners-contract.sh',
     '          echo no-owner-check'],
  ]) {
    assert.ok(preflight.includes(from), 'missing preflight mutation anchor');
    assert.throws(() => verifyPreflight(preflight.replace(from, to)),
      undefined, 'unsafe preflight mutation accepted');
  }
});

test('CODEOWNERS tampering rejected', () => {
  assert.throws(() => verify(yaml, owners.replace(
    '/tests/private-exact-main-admission.test.mjs @ORESoftware', '')));
});
