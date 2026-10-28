import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const yaml = readFileSync(new URL('../.github/workflows/private-exact-main-validation.yml', import.meta.url), 'utf8');
const owners = readFileSync(new URL('../.github/CODEOWNERS', import.meta.url), 'utf8');

function verify(w, c) {
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
  assert.deepEqual(c.trimEnd().split('\n').slice(-7), [
    '/.github/CODEOWNERS @ORESoftware',
    '/.github/private-ci-approved-repositories.txt @ORESoftware',
    '/.github/private-ci-carrier-contracts.json @ORESoftware',
    '/scripts/check-private-ci-destination.sh @ORESoftware',
    '/scripts/run-in-private-ci-destination.sh @ORESoftware',
    '/scripts/publish-private-ci-source.py @ORESoftware',
    '/scripts/publish-private-ci-source-v3.py @ORESoftware',
  ]);
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
test('CODEOWNERS tampering rejected', () => {
  assert.throws(() => verify(yaml, owners.replace(
    '/tests/private-exact-main-admission.test.mjs @ORESoftware', '')));
});
