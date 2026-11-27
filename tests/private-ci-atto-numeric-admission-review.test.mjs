import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL('../' + path, import.meta.url), 'utf8');
const packet = JSON.parse(read('docs/security/reviews/atto-numeric-1736-private-ci-admission.json'));

test('review packet is inert and does not grant a private source export', () => {
  assert.equal(packet.schema, 'atto.private-ci-admission-review.v1');
  assert.equal(packet.status, 'REVIEW_ONLY_NOT_AUTHORIZED');
  assert.equal(packet.disposition, 'DENY_PRIVATE_SOURCE_EXPORT');
  assert.equal(packet.source.repository, 'atto-lang/atto-source.java');
  assert.equal(packet.source.repository_id, 1448697198);
  assert.equal(packet.source.pull_request, 1736);
  assert.match(packet.source.observed_head_sha, /^[a-f0-9]{40}$/);
  assert.match(packet.source.observed_root_tree_sha, /^[a-f0-9]{40}$/);
  assert.equal(packet.source.private, true);
  assert.equal(packet.source.observed_main_protected, false);
  assert.equal(packet.source_head_drift.stale, true);
  assert.notEqual(packet.source_head_drift.observed_new_head_sha,
      packet.source.observed_head_sha,
      'original exact-head snapshot must be invalidated when #1736 changes');
  assert.equal(packet.source_free_evidence.actual_atto_guest_executed, false);
  assert.equal(packet.source_free_evidence.actual_atto_full_guest_aot_executed, false);
  assert.equal(packet.source_free_evidence.private_source_files_transferred, 0);
  assert.ok(packet.candidates.every((candidate) => candidate.approved_for_transfer === false));
});

test('publisher enforces legacy Oreslang source identity rather than this Atto source', () => {
  const legacy = read('scripts/publish-private-ci-source.py');
  const v3 = read('scripts/publish-private-ci-source-v3.py');
  assert.match(legacy, /^SOURCE_REPO = "ores-truffle-oreslang\/oreslang-source\.java"$/m);
  assert.match(legacy, /^SOURCE_REPOSITORY_ID = 1400792274$/m);
  assert.match(v3, /^SOURCE_REPOSITORY_ID = 1400792274$/m);
  assert.match(v3, /legacy\.SOURCE_REPO/);
  assert.equal(packet.legacy_publisher.compatible_with_atto_source_id, false);
  assert.notEqual(packet.source.repository_id, packet.legacy_publisher.source_repository_id);
  assert.equal(packet.legacy_publisher.source_repository, 'ores-truffle-oreslang/oreslang-source.java');
});

test('review packet does not weaken any active source policy or carrier contract', () => {
  const approved = read('.github/private-ci-approved-repositories.txt');
  const entries = approved.split(/\r?\n/)
    .map((line) => line.trim()).filter((line) => line && !line.startsWith('#'));
  assert.deepEqual(entries, [], 'security owner has not independently enabled a carrier');
  const contracts = JSON.parse(read('.github/private-ci-carrier-contracts.json'));
  assert.equal(contracts.schema, 1);
  for (const candidate of packet.candidates) {
    // A carrier may be mentioned for a legacy Oreslang PR, but that
    // independently reviewed grant is never transferable to Atto #1736.
    const contract = contracts.carriers[candidate.repository];
    for (const plan of Object.values(contract?.test_plans ?? {})) {
      assert.equal(
        plan.allowed_prs.includes(packet.source.pull_request), false,
        'no approved carrier test plan may authorize Atto #1736'
      );
    }
  }
  assert.equal(packet.legacy_publisher.v3_atto_test_plan_approved, false);
});

test('reviewed publisher capacity covers tree count but does not override the deny gate', () => {
  const legacy = read('scripts/publish-private-ci-source.py');
  const v3 = read('scripts/publish-private-ci-source-v3.py');
  const policy = read('docs/security/CI_PRIVATE_SOURCE_MIRRORING.md');
  const declared = /^MAX_FILES = (\d+)$/m.exec(legacy);
  assert.ok(declared, 'fixed bounded file cap is missing');
  assert.equal(Number(declared[1]), 4096);
  assert.match(v3, /legacy\s*=\s*importlib\.util\.module_from_spec/);
  assert.equal(packet.source.observed_tree_blob_count, 2906);
  assert.ok(packet.source.observed_tree_blob_count <= Number(declared[1]));
  assert.ok(packet.source.observed_tree_blob_bytes <= packet.legacy_publisher.supported_max_bytes);
  assert.match(policy, /4,096 files \/ 40 MiB/);
  assert.equal(packet.disposition, 'DENY_PRIVATE_SOURCE_EXPORT');
});

test('inert numerical Maven plan pins exact tree components but cannot grant execution', () => {
  const proposal = JSON.parse(read('docs/security/reviews/atto-numeric-1736-junit-plan-proposal.json'));
  assert.equal(proposal.schema, 'atto.numeric-ci-test-plan-proposal.v1');
  assert.equal(proposal.status, 'REVIEW_ONLY_NOT_AUTHORIZED');
  assert.equal(proposal.source_head_drift.stale, true);
  assert.equal(proposal.source_head_drift.observed_new_head_sha,
      packet.source_head_drift.observed_new_head_sha);
  assert.notEqual(proposal.candidate_source.head_sha,
      proposal.source_head_drift.observed_new_head_sha,
      'never silently move an immutable test plan onto a later PR head');
  assert.deepEqual(proposal.candidate_source, {
    repo: packet.source.repository,
    pr: packet.source.pull_request,
    head_sha: packet.source.observed_head_sha,
    root_tree_sha: packet.source.observed_root_tree_sha,
  });
  const plan = proposal.target_plan;
  assert.equal(plan.source_repo, packet.source.repository);
  assert.deepEqual(plan.allowed_prs, [1736]);
  assert.equal(plan.maven_pom_blob_sha, packet.source.pom_blob_sha);
  assert.equal(plan.required_source_blobs['pom.xml'], plan.maven_pom_blob_sha);
  assert.ok(Object.keys(plan.required_source_blobs).length >= 16);
  assert.equal(plan.commands.length, 1);
  const argv = plan.commands[0].argv;
  assert.equal(argv[0], 'mvn');
  assert.ok(argv.includes('-o'), 'offline Maven required');
  assert.ok(argv.includes('-C'), 'strict checksums required');
  assert.ok(argv.some((arg) => arg.startsWith('-Dtest=NumericValueOrderingTest,')));
  assert.equal(plan.commands[0].cwd, '.');
  assert.ok(plan.commands[0].timeout_seconds <= 1800);
  assert.equal(
    plan.source_repo === 'ores-truffle-oreslang/oreslang-source.java', false,
    'the currently imported Oreslang V3 publisher MUST reject this inert Atto plan'
  );
  const active = JSON.parse(read('.github/private-ci-carrier-contracts.json'));
  for (const carrier of Object.values(active.carriers)) {
    for (const approved of Object.values(carrier.test_plans)) {
      assert.ok(!approved.allowed_prs.includes(1736));
    }
  }
});

test('exact-tree drift adds receipt verifier files outside the inert approved-plan candidate', () => {
  const proposal = JSON.parse(read('docs/security/reviews/atto-numeric-1736-junit-plan-proposal.json'));
  const drift = packet.source_head_drift;
  assert.deepEqual(drift, proposal.source_head_drift);
  assert.equal(drift.stale, true);
  assert.equal(drift.observed_new_head_sha, 'c908c6c824c2dbb5caed3023b2c5e3e975a05834');
  assert.equal(drift.observed_new_root_tree_sha, '103e305a8157e1cabce214b888df68e0777844cf');
  assert.notEqual(drift.observed_new_root_tree_sha, packet.source.observed_root_tree_sha);
  assert.equal(drift.observed_new_regular_files, 2908);
  assert.equal(drift.observed_new_regular_blob_bytes, 20915041);
  assert.equal(drift.observed_new_executable_files, 17);
  assert.equal(drift.observed_new_tree_truncated, false);
  assert.equal(drift.observed_new_symlinks, 0);
  assert.equal(drift.observed_new_submodules, 0);
  assert.equal(drift.inert_plan_required_blobs_still_match, 16);
  const files = drift.new_files_missing_from_inert_plan;
  assert.deepEqual(Object.keys(files).sort(), [
    'scripts/verify_numeric_junit_receipts.py',
    'tests/test_verify_numeric_junit_receipts.py',
  ]);
  assert.equal(files['scripts/verify_numeric_junit_receipts.py'],
    'b3361baf6f0f9e8b1c8789d708f3a67621de2a7e');
  assert.equal(files['tests/test_verify_numeric_junit_receipts.py'],
    'f4550cd398cc7ad24c270c72d5ce79189e48e7e1');
  for (const [path, sha] of Object.entries(files)) {
    assert.match(sha, /^[0-9a-f]{40}$/);
    assert.equal(proposal.target_plan.required_source_blobs[path], undefined);
  }
  assert.equal(packet.disposition, 'DENY_PRIVATE_SOURCE_EXPORT');
  assert.equal(proposal.status, 'REVIEW_ONLY_NOT_AUTHORIZED');
});
