// Host-only admission checks; neither runner nor Java evidence.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const y=readFileSync(new URL("../.github/workflows/ml-scalar-actor-guest.yml",import.meta.url),"utf8");
const gates=y.match(/  pinned-java-graal-guest:\n    if: (.+)\n/);
test("private Java checkout requires same-repo private PR and reviewed SHA/tree",()=>{
  assert(gates);
  for(const check of [
    "github.repository == 'ores-truffle-oreslang/oreslang-source.java'",
    "github.repository_id == '1400792274'",
    "github.event.repository.private == true",
    "github.event.repository.fork == false",
    "github.event.pull_request.head.repo.id == github.repository_id",
    "github.event.pull_request.head.repo.full_name == github.repository",
    "github.event.pull_request.head.repo.private == true",
    "github.event.pull_request.head.repo.fork == false",
    "vars.ORESLANG_ML_APPROVED_SHA == github.event.pull_request.head.sha",
    "vars.ORESLANG_ML_APPROVED_TREE != ''"
  ])assert(gates[1].includes(check),check);
});
test("unapproved jobs explicitly fail and manual/target triggers are absent",()=>{
  assert.match(y,/deny-unapproved-private-source:/);
  assert.match(y,/ML_PRIVATE_SOURCE_ADMISSION=DENIED/);
  assert.match(y,/exit 1/);
  assert.doesNotMatch(y,/^  workflow_dispatch:/m);
  assert.doesNotMatch(y,/pull_request_target:/);
});
test("precheckout admission and postcheckout Git tree identity checks are ordered",()=>{
  const auth=y.indexOf("Verify original private repository and independently approved head before checkout");
  const checkout=y.indexOf("Checkout exact source PR head, never a cross-org carrier");
  const verify=y.indexOf("Verify exact source revision and clean checkout");
  assert(auth>0 && checkout>auth && verify>checkout);
  assert.match(y,/test "\$PR_HEAD_SHA" = "\$APPROVED_SHA"/);
  assert.match(y,/git rev-parse 'HEAD\^\{tree\}'/);
  assert.match(y,/test "\$\(git rev-parse 'HEAD\^\{tree\}'\)" = "\$EXPECTED_TREE"/);
  assert.match(y,/persist-credentials: false/);
  assert.match(y,/clean: true/);
});
test("immutable actions, least privilege, no shared cache or uploaded artifacts",()=>{
  const refs=[...y.matchAll(/^\s+uses: ([^\s#]+)/gm)].map(m=>m[1]);
  assert.equal(refs.length,2);
  assert(refs.every(x=>/@[0-9a-f]{40}$/.test(x)));
  assert.match(y,/^permissions:\n  contents: read/m);
  assert.doesNotMatch(y,/upload-artifact|actions\/cache|cache: maven/);
});
