// Source-shape guards only: these tests do not execute AttoLang guest code.
// Full compiler, actor runtime, storage and security acceptance remain pending.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
const policy = readFileSync(root + "stdlib/actor_event_sourcing.atto", "utf8");
const families = readFileSync(root + "stdlib/actors/durable_actors.atto", "utf8");
const doc = readFileSync(root + "docs/EVENT_SOURCING_SUPERACTORS_STDLIB_V1.md", "utf8");

test("the actor hierarchy is a stdlib super-actor family, not a new keyword", () => {
  for (const name of ["DurableActor", "EventSourcedActor", "SnapshotActor", "ProjectionActor", "DurableOutboxActor"]) {
    assert.match(families, new RegExp("define abstract actor " + name + "\\b"));
  }
  assert.match(families, /from DurableActor<Command>/);
  assert.doesNotMatch(families, /\bstate\s*\{/);
  assert.match(families, /pure decide\(/);
  assert.match(families, /pure apply_event\(/);
});

test("no direct I/O, privilege, or side effects are claimed by library policy", () => {
  assert.match(doc, /NOT IMPLEMENTED/);
  assert.match(doc, /NOT EXECUTABLE YET/);
  assert.match(doc, /provider receipt/);
  assert.match(doc, /OUTCOME_UNKNOWN/);
  assert.match(doc, /exactly once/);
  assert.doesNotMatch(policy, /\bjdbc\b|\bSqlitePersistenceStore\b|\bgrant_epoch\(/);
});

test("the gate is one-write-at-a-time, no reset-on-timeout, with matched identity", () => {
  assert.match(policy, /self\.phase_code != 1/);
  assert.match(policy, /self\.phase_code = 3;/);
  assert.match(policy, /operation_id != self\.pending_operation_id/);
  assert.match(policy, /payload_fingerprint != self\.pending_fingerprint/);
  assert.match(policy, /verified_epoch != self\.activation_epoch/);
  assert.match(policy, /first_version != self\.pending_first/);
  assert.match(policy, /last_version != self\.pending_last/);
  assert.doesNotMatch(policy, /\bretry\(/);
});

test("replay requires contiguous versions and bounded work", () => {
  assert.match(policy, /event_version != self\.applied_version \+ 1/);
  assert.match(policy, /self\.applied_version == self\.head_version/);
  assert.match(policy, /max_events > 10000/);
  assert.match(doc, /journal gap/);
});

test("explicit pending guest implementation gates are documented", () => {
  for (const id of ["ES01", "ES02", "ES03", "ES04", "ES05", "ES06", "ES07", "ES08", "ES09", "ES10", "ES11", "ES12", "ES13"]) {
    assert.match(doc, new RegExp(id + ":"));
  }
  assert.match(doc, /source-compiled guest/);
  assert.match(doc, /Java\/Graal, LLVM, \.NET, Wasm, Forge/);
});
