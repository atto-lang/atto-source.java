import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const read = (path) => readFileSync(new URL('../' + path, import.meta.url), 'utf8');
const review = JSON.parse(read('docs/security/reviews/atto-incremental-1744-private-ci-admission.json'));
const plan = JSON.parse(read('docs/security/reviews/atto-incremental-1744-junit-plan-proposal.json'));

test('incremental security packet is inert and denies source export', () => {
  assert.equal(review.schema, 'atto.private-ci-admission-review.v1');
  assert.equal(review.status, 'REVIEW_ONLY_NOT_AUTHORIZED');
  assert.equal(review.disposition, 'DENY_PRIVATE_SOURCE_EXPORT');
  assert.equal(review.source.repository, 'atto-lang/atto-source.java');
  assert.equal(review.source.repository_id, 1448697198);
  assert.equal(review.source.private, true);
  assert.equal(review.source.pull_request, 1744);
  assert.equal(review.source.observed_main_protected, false);
  assert.equal(review.private_source_transferred, false);
  assert.equal(review.active_allowlist_modified, false);
  assert.equal(review.active_carrier_contract_modified, false);
  assert.match(review.source.observed_head_sha, /^[a-f0-9]{40}$/);
  assert.match(review.source.observed_root_tree_sha, /^[a-f0-9]{40}$/);
  assert.ok(review.candidates.every(c => c.private && !c.approved_for_transfer && !c.approved_for_pr1744));
});

test('frozen source snapshot fits size bounds but no security authority follows', () => {
  assert.equal(review.source.observed_tree_truncated, false);
  assert.ok(review.source.observed_regular_blobs <= review.publisher.implemented_max_files);
  assert.ok(review.source.observed_blob_bytes <= review.publisher.implemented_max_total_bytes);
  assert.ok(review.source.observed_largest_blob_bytes <= review.publisher.implemented_max_single_blob_bytes);
  assert.equal(review.source.observed_symlinks, 0);
  assert.equal(review.source.observed_submodules, 0);
  assert.equal(review.publisher.legacy_origin_compatible_with_atto, false);
  assert.equal(review.publisher.authorized_atto_v3_publisher, false);
  assert.notEqual(review.publisher.legacy_source_repository_id, review.source.repository_id);
  assert.equal(review.stale_on_source_head_change, true);
});

test('active allowlist and carrier contracts do not authorize Atto #1744', () => {
  const allowlist = read('.github/private-ci-approved-repositories.txt').split(/\r?\n/)
      .map(line => line.trim()).filter(line => line && !line.startsWith('#'));
  assert.deepEqual(allowlist, [], 'policy remains deny-by-default, independent approval pending');
  const contracts = JSON.parse(read('.github/private-ci-carrier-contracts.json'));
  assert.equal(contracts.schema, 1);
  for (const carrier of Object.values(contracts.carriers)) {
    for (const plan of Object.values(carrier.test_plans || {})) {
      assert.equal(plan.allowed_prs.includes(1744), false);
    }
  }
  assert.equal(review.pending_independent_approvals.length >= 5, true);
});

test('proposed test plan pins exact head, file blobs and offline Maven only', () => {
  assert.equal(plan.status, 'REVIEW_ONLY_NOT_AUTHORIZED');
  assert.equal(plan.candidate_source.head_sha, review.source.observed_head_sha);
  assert.equal(plan.candidate_source.root_tree_sha, review.source.observed_root_tree_sha);
  const target = plan.proposed_target_plan;
  assert.equal(target.source_repo, review.source.repository);
  assert.deepEqual(target.allowed_prs, [1744]);
  assert.equal(target.maven_pom_blob_sha, target.required_source_blobs['pom.xml']);
  assert.match(target.required_source_blobs['src/test/java/dev/oreslang/IncrementalFileFallbackV1Test.java'], /^[a-f0-9]{40}$/);
  assert.equal(Object.keys(target.required_source_blobs).length, 10);
  assert.equal(target.required_junit_testcase_count, 15);
  assert.equal(target.require_nonzero_runner_steps, true);
  assert.equal(target.require_no_failures, true);
  assert.equal(target.require_no_skips, true);
  assert.equal(target.commands.length, 1);
  const argv = target.commands[0].argv;
  assert.equal(argv[0], 'mvn');
  assert.ok(argv.includes('-o') && argv.includes('-C'));
  assert.ok(argv.includes('-Dtest=IncrementalFileFallbackV1Test'));
  assert.deepEqual(argv.slice(-2), ['clean', 'test']);
  assert.equal(plan.source_head_drift.action_if_changed,
      'DENY_AUTO_REPIN_AND_REQUEST_NEW_EXACT_HEAD_REVIEW');
});

test('runner admission does not count zero-step probe as capacity or test evidence', () => {
  const failed = review.source_free_capacity_probe_rerun;
  assert.equal(failed.private_source_transferred, false);
  assert.equal(failed.executed_steps, 0);
  assert.equal(failed.conclusion, 'failure');
  const candidate = review.candidates.find(x => x.repository === 'opto-sync-test/go-java-validation');
  assert.equal(candidate.capacity_proven_now, false);
  const runnable = review.candidates.find(x => x.repository === 'opto-sync/atto-ci');
  assert.equal(runnable.source_free_steps_executed, 8);
  assert.equal(runnable.approved_for_transfer, false);
  assert.equal(runnable.observed_default_branch_is_main, false);
});
