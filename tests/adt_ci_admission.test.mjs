import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
// Host-only policy checks; never claim compiler/runner execution from these.
const s=readFileSync(new URL("../.github/workflows/adt-exact-head.yml",import.meta.url),"utf8");
function section(name) {
  const m=s.match(new RegExp("(?=^  "+name+":$)","m"));
  assert(m,"missing "+name);
  const start=m.index;
  const rest=s.slice(start+1);
  const n=rest.search(/\n  [a-z][a-z-]+:\n/);
  return n<0?rest:rest.slice(0,n);
}
test("only the protected original-source branch can trigger",()=>{
  assert.match(s,/on:\n  push:\n    branches:/);
  assert.doesNotMatch(s,/^\s+(?:pull_request|workflow_dispatch|pull_request_target):/m);
  assert.match(s,/github\.repository_id == '1400792274'/);
  assert.match(s,/github\.event\.repository\.private == true/);
  assert.match(s,/github\.ref_protected == true/);
});
test("all three compiler gates validate reviewed exact SHA/tree",()=>{
  for(const n of ["adt-portable","adt-targeted","adt-full"]){
    const v=section(n);
    assert.match(v,/vars\.ORESLANG_ADT_APPROVED_SHA == github\.sha/);
    assert.match(v,/vars\.ORESLANG_ADT_APPROVED_TREE != ''/);
    assert.match(v,/runs-on: \[self-hosted, linux, x64, oreslang-private-ephemeral\]/);
    assert.match(v,/persist-credentials: false/);
    assert.match(v,/git rev-parse 'HEAD\^\{tree\}'/);
    assert.match(v,/git status --porcelain --untracked-files=all/);
  }
});
test("all actions are exact pins; no cache or artifact uploads",()=>{
  const refs=[...s.matchAll(/^\s+- uses: ([^ #]+)/gm)].map(m=>m[1]);
  assert(refs.length===6,"expected three checkout and three toolchain actions");
  assert(refs.every(r=>/@[0-9a-f]{40}$/.test(r)));
  assert.doesNotMatch(s,/^\s+cache:|upload-artifact|actions\/cache|github-token:/m);
  assert.match(s,/^permissions:\n  contents: read/m);
});
test("deny unapproved execution and retain compiler gate",()=>{
  assert.match(section("deny-unapproved"),/ADT_SOURCE_ADMISSION=DENIED/);
  assert.match(s,/TrainingErrorNominalResultGuestTest/);
  assert.match(s,/node --test tests\/adt_ci_admission\.test\.mjs/);
});
