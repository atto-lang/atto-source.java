// Host-only static audit of the protected source-local integration workflow.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const y=readFileSync(new URL("../.github/workflows/adt-ml-integration-private-ephemeral.yml",import.meta.url),"utf8");
test("only source-local protected push with independent reviewed SHA/tree is allowed",()=>{
  assert.match(y,/on:\n  push:\n    branches:/);
  assert.doesNotMatch(y,/^\s+pull_request:|^\s+workflow_dispatch:|pull_request_target:/m);
  const gate=y.match(/  authorized-compiler:\n    if: ([^\n]+)\n/);
  assert(gate);
  for(const s of [
    "github.repository_id == '1400792274'",
    "github.event.repository.private == true",
    "github.event.repository.fork == false",
    "github.ref_protected == true",
    "github.ref == 'refs/heads/integrate/adt-ml-staging-20261018'",
    "vars.ORESLANG_ADT_ML_PRIVATE_RUNNER == 'true'",
    "vars.ORESLANG_ADT_ML_APPROVED_SHA == github.sha",
    "vars.ORESLANG_ADT_ML_APPROVED_TREE != ''"
  ])assert(gate[1].includes(s),s);
});
test("deny non-reviewed source and validate exact tree before compilation",()=>{
  assert.match(y,/ADT_ML_SOURCE_LOCAL_ADMISSION=DENIED/);
  assert.match(y,/exit 1/);
  assert.match(y,/runs-on: \[self-hosted, linux, x64, oreslang-private-ephemeral\]/);
  const pre=y.indexOf("Check protected exact-head source authorization before checkout");
  const checkout=y.indexOf("Checkout reviewed original private source");
  const sha=y.indexOf("Prove exact reviewed tree and pinned actor fixture");
  const compile=y.indexOf("Compile production and test classes");
  assert(pre>0 && pre<checkout && checkout<sha && sha<compile);
  assert.match(y,/git rev-parse 'HEAD\^\{tree\}'/);
  assert.match(y,/persist-credentials: false/);
});
test("compile and negative guest precede fail-closed positive tests",()=>{
  const a=y.indexOf("Compile production and test classes");
  const b=y.indexOf("Prove negative admission");
  const c=y.indexOf("Require complete ADT and actor compiler integration");
  const d=y.indexOf("Run positive nominal Result");
  const e=y.indexOf("Run complete Java and Graal");
  assert(a>0&&a<b&&b<c&&c<d&&d<e);
  assert.match(y,/NominalActorBridgeFailClosedTest,NominalResultErrorAuditTest,NominalResultTraversalTest test/);
  assert.match(y,/TrainingErrorNominalResultGuestTest,MlActorStateScalarGuestTest test/);
});
test("no unpinned actions or third-party uploads",()=>{
  const actions=[...y.matchAll(/^\s+uses: ([^\s#]+)/gm)].map(x=>x[1]);
  assert.equal(actions.length,2);
  assert(actions.every(x=>/@[a-f0-9]{40}$/.test(x)));
  assert.match(y,/^permissions:\n  contents: read/m);
  assert.doesNotMatch(y,/upload-artifact|actions\/cache|cache:\s*maven/);
});
