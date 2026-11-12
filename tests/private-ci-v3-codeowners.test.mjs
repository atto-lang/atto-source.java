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
  '/.github/workflows/source-free-private-ci-gates.yml',
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

/**
 * Until main is protected and source-export approval is independently verified,
 * the checked-in allowlist must be deny-all. A proposed carrier is not approval.
 * Promotion requires an explicit, reviewed policy + test change.
 */
function activeCarrierLines(policy) {
  assert.equal(typeof policy, 'string');
  assert.ok(Buffer.byteLength(policy, 'utf8') <= 16384, 'policy oversized');
  assert.doesNotMatch(policy, /[^\x0a\x20-\x7e]/u, 'noncanonical policy bytes');
  return policy.split('\n').filter(line => line !== '' && !line.startsWith('#'));
}
test('main private CI allowlist remains deny-all pending governance proof', () => {
  const contents = readFileSync(path.join(ROOT, '.github/private-ci-approved-repositories.txt'), 'utf8');
  assert.deepEqual(activeCarrierLines(contents), [], 'active carriers need separate approval');
});
test('proposed carrier comment cannot be interpreted as approval', () => {
  assert.deepEqual(activeCarrierLines('# proposed\n# beamscale/bmscl-compiler 1383937120\n'), []);
  assert.deepEqual(activeCarrierLines('# proposed\nbeamscale/bmscl-compiler 1383937120\n'),
    ['beamscale/bmscl-compiler 1383937120']);
});
test('malformed or misleading policy bytes do not pass allowlist test', () => {
  for (const bad of ['# comment\r\n', '# comment\u0000\n', '# comment\u00e9\n']) {
    assert.throws(() => activeCarrierLines(bad), /noncanonical/u);
  }
  assert.deepEqual(activeCarrierLines(' # indented pseudo-comment\n'), [' # indented pseudo-comment']);
});

/** The source-free policy test must prove the workflow's checkout identity. */
function assertPinnedPrivateCiWorkflow(contents) {
  const exactHead = '${{ github.event.pull_request.head.sha || github.sha }}';
  assert.match(contents,
    /- uses: actions\/checkout@[a-f0-9]{40}\n\s+with:\n\s+ref: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}\n\s+persist-credentials: false/u,
    'immutable checkout action and exact PR-head ref required');
  assert.ok(contents.includes('EXPECTED_HEAD: ' + exactHead),
    'same exact PR-head expression must feed the runtime assertion');
  assert.ok(contents.includes('test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"'),
    'the runtime must verify checked-out HEAD matches PR-head SHA');
  assert.equal(contents.split(".github/workflows/private-ci-mirror-preflight.yml").length - 1, 2,
    'workflow changes must trigger both pull_request and push checks');
}
test('private CI preflight checks the exact PR source commit', () => {
  const workflow = readFileSync(path.join(ROOT,
    '.github/workflows/private-ci-mirror-preflight.yml'), 'utf8');
  assertPinnedPrivateCiWorkflow(workflow);
});
test('synthetic-merge or branch checkout cannot impersonate exact-head execution', () => {
  const workflow = readFileSync(path.join(ROOT,
    '.github/workflows/private-ci-mirror-preflight.yml'), 'utf8');
  for (const bad of [
    workflow.replace('ref: ${{ github.event.pull_request.head.sha || github.sha }}', 'ref: main'),
    workflow.replace('test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"', 'echo skipped'),
    workflow.replace('persist-credentials: false', 'persist-credentials: true'),
    workflow.replace('actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09', 'actions/checkout@main'),
  ]) assert.throws(() => assertPinnedPrivateCiWorkflow(bad));
});

test('source-free deny-all workflow uses exact head and strict policy bytes', () => {
  const policyGate = readFileSync(path.join(ROOT,
    '.github/workflows/source-free-private-ci-gates.yml'), 'utf8');
  assert.match(policyGate,
    /- uses: actions\/checkout@[a-f0-9]{40}[^\n]*\n\s+with:\n\s+ref: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}\n\s+persist-credentials: false/u);
  assert.ok(policyGate.includes('EXPECTED_HEAD: ${{ github.event.pull_request.head.sha || github.sha }}'));
  assert.ok(policyGate.includes('test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"'));
  assert.ok(policyGate.includes('raw = path.read_bytes()'));
  assert.ok(policyGate.includes("raw.split(b'\\n')"),
    'policy parser must split actual newline bytes, never literal backslash+n');
  assert.ok(!policyGate.includes("raw.split(b'\\\\n')"),
    'escape-level regression would make entire policy appear as a single comment');
  assert.ok(policyGate.includes("line.startswith(b'#')"));
  assert.ok(!policyGate.includes("line.lstrip().startswith('#')"),
    'never treat indented pseudo-comments as signed policy comments');
  assert.ok(policyGate.includes('DENIED: noncanonical private-source destination policy bytes'));
});


/**
 * Both the PR and push gates must run when protected source-export policy,
 * test plans, exact-tree enforcement, or the workflows themselves change.
 * This is a source-free trigger contract, NOT proof of runner admission.
 */
function sourceFreeTriggerPaths(contents, event) {
  const section = contents.match(new RegExp(
    '^  ' + event + ':\\n    paths:\\n((?:      - \x27[^\x27\\n]+\x27\\n)+)', 'mu'));
  assert.ok(section, 'missing ' + event + ' security gate paths');
  return section[1].split('\n').filter(Boolean).map(line => {
    const match = line.match(/^      - '([^']+)'$/u);
    assert.ok(match, 'malformed ' + event + ' path filter');
    return match[1];
  });
}
function assertSourceFreeCoverage(contents) {
  const prPaths = sourceFreeTriggerPaths(contents, 'pull_request');
  const pushPaths = sourceFreeTriggerPaths(contents, 'push');
  assert.deepEqual(pushPaths, prPaths, 'push must cover every PR policy change');
  assert.equal(new Set(prPaths).size, prPaths.length, 'duplicate policy path');
  // Exact allowlisting detects deletions, exclusions, globs and unexpected
  // sources that would make a security-critical change skip the required gate.
  assert.deepEqual(prPaths, [
    'scripts/check-private-ci-destination.sh',
    'scripts/ci_private_mirror_preflight.py',
    'tests/test_ci_private_mirror_preflight.py',
    'scripts/test-private-ci-destination.sh',
    'scripts/test-private-ci-codeowners-contract.sh',
    'scripts/run-in-private-ci-destination.sh',
    'scripts/publish-private-ci-source.py',
    'scripts/publish-private-ci-source-v3.py',
    'scripts/test-publish-private-ci-source.py',
    'scripts/test-publish-private-ci-source-v3.py',
    '.github/private-ci-approved-repositories.txt',
    '.github/private-ci-carrier-contracts.json',
    '.github/CODEOWNERS',
    '.github/workflows/private-ci-mirror-preflight.yml',
    '.github/workflows/source-free-private-ci-gates.yml',
    'docs/security/CI_PRIVATE_SOURCE_MIRRORING.md',
    'tools/verify-exact-source-tree.sh',
    'tools/test-verify-exact-source-tree.sh',
    'tests/private-ci-v3-codeowners.test.mjs',
  ], 'every security control must trigger the source-free PR and push gates');
  assert.match(contents, /^permissions:\n  contents: read$/mu,
    'gate must not receive write permissions');
  assert.ok(contents.includes('node --test tests/private-ci-v3-codeowners.test.mjs'),
    'gate must execute policy-negative tests');
  assert.ok(contents.includes('bash tools/test-verify-exact-source-tree.sh'),
    'gate must execute source-free full-tree mismatch tests');
}
test('source-free CI gate defends PR and push on all protected inputs', () => {
  const yaml = readFileSync(path.join(ROOT,
    '.github/workflows/source-free-private-ci-gates.yml'), 'utf8');
  assertSourceFreeCoverage(yaml);
});
test('source-free workflow mutation attempts cannot silently drop gates', () => {
  const yaml = readFileSync(path.join(ROOT,
    '.github/workflows/source-free-private-ci-gates.yml'), 'utf8');
  for (const broken of [
    yaml.replace('  push:\n    paths:\n', '  no_push:\n    paths:\n'),
    yaml.replace("      - '.github/workflows/source-free-private-ci-gates.yml'\n", ''),
    yaml.replace("      - 'scripts/ci_private_mirror_preflight.py'\n", ''),
    yaml.replace("      - 'scripts/test-publish-private-ci-source-v3.py'\n", ''),
    yaml.replace("      - 'tests/private-ci-v3-codeowners.test.mjs'\n",
      "      - 'tests/private-ci-v3-codeowners.test.mjs'\n      - '!tests/private-ci-v3-codeowners.test.mjs'\n"),
    yaml.replace('bash tools/test-verify-exact-source-tree.sh', 'echo skipped'),
    yaml.replace('node --test tests/private-ci-v3-codeowners.test.mjs', 'echo skipped'),
    yaml.replace('  contents: read\n', '  contents: write\n'),
  ]) assert.throws(() => assertSourceFreeCoverage(broken));
});


test('publisher final receipt requires authorization after both ref readbacks', () => {
  const source = readFileSync(path.join(ROOT,
    'scripts/publish-private-ci-source-v3.py'), 'utf8');
  const section = source.slice(source.indexOf(
    '        # Do not trust create responses alone. Re-read both live refs'));
  assert.ok(section.length > 0, 'missing final handoff block');
  const authorizationReadback = section.indexOf(
    '            repository, authorization, commit_sha, contract["repository_id"]\n'
    + '        )');
  const finalGuard = section.indexOf('\n        auth()\n', authorizationReadback);
  const rollback = section.indexOf('\n    except BaseException as exc:', authorizationReadback);
  assert.ok(authorizationReadback >= 0 && finalGuard > authorizationReadback
    && rollback > finalGuard,
    'revoked authorization must be rejected before successful handoff return');
});

test('carrier main cannot fall back to review-only authority', () => {
  const publisher = readFileSync(path.join(ROOT,
    'scripts/publish-private-ci-source-v3.py'), 'utf8');
  assert.doesNotMatch(publisher, /def review_attested_carrier_main\(/u);
  assert.doesNotMatch(publisher, /def latest_carrier_review\(/u);
  assert.doesNotMatch(publisher, /"review_pr"/u,
    'an unprotected carrier PR must not be a contract authority mode');
  const start = publisher.indexOf('def checked_carrier_main(');
  const end = publisher.indexOf('\n\ndef checked_destination(', start);
  assert.ok(start >= 0 && end > start, 'carrier admission function missing');
  const body = publisher.slice(start, end);
  assert.match(body, /if branch\.get\("protected"\) is not True:\n\s+raise Denied\("carrier main is not protected; review-only fallback denied"\)/u);
  assert.match(body, /legacy\._require_strict_source_protection\(protection\)/u,
    'destination protection must be as strict as origin protection');
  assert.match(body, /require_carrier_security_codeowners\(codeowners\)/u);
});

test('weakening carrier protected-main check fails regression', () => {
  const publisher = readFileSync(path.join(ROOT,
    'scripts/publish-private-ci-source-v3.py'), 'utf8');
  const start = publisher.indexOf('def checked_carrier_main(');
  const end = publisher.indexOf('\n\ndef checked_destination(', start);
  assert.ok(start >= 0 && end > start);
  const original = publisher.slice(start, end);
  const guard = 'if branch.get("protected") is not True:\n'
    + '        raise Denied("carrier main is not protected; review-only fallback denied")';
  const assertCarrierGate = (body) => {
    assert.ok(body.includes(guard), 'unprotected main must be rejected before any further lookup');
    assert.ok(body.includes('legacy._require_strict_source_protection(protection)'));
  };
  assertCarrierGate(original);
  for (const broken of [
    original.replace(guard, 'if False:\n        pass'),
    original.replace('legacy._require_strict_source_protection(protection)', 'pass'),
    original.replace('raise Denied("carrier main is not protected; review-only fallback denied")',
      'return contract["base_sha"]'),
  ]) assert.throws(() => assertCarrierGate(broken));
});

test('per-write destination protection is re-attested without relying on SHA alone', () => {
  const publisher = readFileSync(path.join(ROOT,
    'scripts/publish-private-ci-source-v3.py'), 'utf8');
  const start = publisher.indexOf('def authorize_write(');
  const end = publisher.indexOf('\n\ndef trusted_github_cli(', start);
  assert.ok(start >= 0 && end > start, 'per-write publisher guard is missing');
  const body = publisher.slice(start, end);
  const check = (candidate) => {
    assert.match(candidate,
      /checked_carrier_main\(repository, contract\) != contract\["base_sha"\]/u,
      'write admission must revalidate live carrier protection, not only main SHA');
    assert.match(candidate, /legacy\.require_resolved_blocking_incidents\(\)/u);
    assert.match(candidate, /verify_source_pr\(source_pr, source_commit, authority_sha\)/u);
    assert.match(candidate, /revalidate_source_governance\(/u);
    assert.doesNotMatch(candidate,
      /branch = legacy\.gh_api\(f"repos\/\{repository\}\/branches\/main"\)/u,
      'a bare branch-SHA lookup is insufficient after protection revocation');
  };
  check(body);
  for (const changed of [
    body.replace('checked_carrier_main(repository, contract)', 'contract["base_sha"]'),
    body.replace('legacy.require_resolved_blocking_incidents()', 'pass'),
    body.replace('revalidate_source_governance(', 'pass('),
    body.replace('verify_source_pr(source_pr, source_commit, authority_sha)', '123'),
  ]) assert.throws(() => check(changed));
});
