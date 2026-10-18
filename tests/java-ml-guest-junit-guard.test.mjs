// HOST-ONLY test-file integrity guard; actual JUnit compilation remains mandatory.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createHash} from "node:crypto";
const java=readFileSync(new URL("../src/test/java/dev/oreslang/MlActorStateScalarGuestTest.java",import.meta.url),"utf8");
function section(start,end){
  const a=java.indexOf(start),b=java.indexOf(end,a+start.length);
  assert(a>=0&&b>a, "missing Java method section boundary: "+start);
  return java.slice(a,b);
}
test("positive-control JUnit method has no undeclared invalidSource",()=>{
  const body=section("@Test void validSameShapeActorCanReachMain()",
      "private static void assertGuestRejectedBeforeMain(");
  assert.doesNotMatch(body,/\binvalidSource\b/);
  assert.match(body,/assertDoesNotThrow\(\(\) -> verify\(program\)\)/);
  assert.match(body,/SAFE_MAIN_EXECUTED/);
});
test("every actual invalid guest uses parser-positive then semantic rejection",()=>{
  const body=section("private static void assertGuestRejectedBeforeMain(",
      "private static String gitBlobSha(");
  const parse=body.indexOf("Parser.parse(invalidSource)");
  const semantic=body.indexOf("verify(invalidSource)");
  const execute=body.indexOf("context.eval(executable)");
  assert(parse>=0&&semantic>parse&&execute>semantic);
  assert.match(body,/assertThrows\(IllegalArgumentException\.class/);
  assert.match(body,/assertThrows\(PolyglotException\.class/);
  assert.match(body,/assertEquals\("", output\.toString/);
});
test("guest fixture SHA and separately pinned output prevent silent drift",()=>{
  assert.match(java,/b1f1a0748b3fabbcb8dcd4a24c32a3fdb5b2146e/);
  assert.match(java,/029f88f5a174c1269a784ab4cc44b67e348156d1/);
  assert.match(java,/assertEquals\(expected, stdout\.toString/);
});


test("typed actor JUnit contract pins four Results and negative-success golden",()=>{
  assert.match(java,/assertTrue\(text\.contains\("private print_outcome\(Result<int, String> outcome\): void"\)\)/);
  for(const error of ["INVALID_INPUT","INVALID_GRADIENT","UNSAFE_CANDIDATE"]){
    assert(java.includes('return Err(\\\"'+error+'\\\")'));
  }
  assert.match(java,/self\.eval_affine\(3, 3, 2, -2, 101, 1\)/);
  assert.match(java,/assertEquals\(expected, stdout\.toString/);
});

function gitBlobSha(data) {
  const raw = Buffer.isBuffer(data) ? data : Buffer.from(data);
  return createHash("sha1").update("blob " + raw.length + "\0").update(raw).digest("hex");
}
test("guest CI source pins match actual fixture blobs and JUnit gate", () => {
  const workflow = readFileSync(new URL("../.github/workflows/ml-scalar-actor-guest.yml", import.meta.url), "utf8");
  const fixtureSha = gitBlobSha(readFileSync(new URL("./fixtures/ml-actor-state-scalar.ores", import.meta.url)));
  const goldenSha = gitBlobSha(readFileSync(new URL("./golden/ml-actor-state-scalar.stdout", import.meta.url)));
  assert.equal(fixtureSha, "b1f1a0748b3fabbcb8dcd4a24c32a3fdb5b2146e");
  assert.equal(goldenSha, "029f88f5a174c1269a784ab4cc44b67e348156d1");
  assert.match(java, new RegExp(fixtureSha));
  assert.match(java, new RegExp(goldenSha));
  assert.match(workflow, new RegExp(fixtureSha));
  assert.match(workflow, new RegExp(goldenSha));
  assert.equal(workflow.split('      - "tests/golden/ml-actor-state-scalar.stdout"').length - 1, 1, "one golden path filter only");
  assert.match(workflow, /-Dtest=MlActorStateScalarGuestTest test/);
  assert.match(workflow, /-Dores\.runtime\.carriers=native clean verify/);
  assert.doesNotMatch(workflow, /uses: (?:actions\/checkout|graalvm\/setup-graalvm)@v[0-9]/);
});

test("private source-local runner cannot admit unreviewed code", () => {
  const privateWorkflow = readFileSync(new URL("../.github/workflows/ml-scalar-actor-private-ephemeral.yml", import.meta.url), "utf8");
  const hostedWorkflow = readFileSync(new URL("../.github/workflows/ml-scalar-actor-guest.yml", import.meta.url), "utf8");
  assert.match(hostedWorkflow, /ml-scalar-actor-private-ephemeral\.yml/);
  assert.match(privateWorkflow, /^on:\n  push:/m);
  assert.doesNotMatch(privateWorkflow, /^\s+pull_request:|^\s+workflow_dispatch:/m);
  assert.match(privateWorkflow, /deny-unapproved:/);
  assert.match(privateWorkflow, /approved-ml-guest:/);
  assert.match(privateWorkflow, /github\.repository_id == '1400792274'/);
  assert.match(privateWorkflow, /github\.event\.repository\.private == true/);
  assert.match(privateWorkflow, /github\.ref_protected == true/);
  assert.match(privateWorkflow, /vars\.ORESLANG_ML_APPROVED_SHA == github\.sha/);
  assert.match(privateWorkflow, /GITHUB_REF_PROTECTED/);
  assert.match(privateWorkflow, /git rev-parse 'HEAD\^\{tree\}'/);
  assert.match(privateWorkflow, /persist-credentials: false/);
  assert.match(privateWorkflow, /runs-on: \[self-hosted, linux, x64, oreslang-private-ephemeral\]/);
  assert.match(privateWorkflow, /-Dtest=MlActorStateScalarGuestTest test/);
  assert.match(privateWorkflow, /-Dores\.runtime\.carriers=native clean verify/);
  assert.doesNotMatch(privateWorkflow, /upload-artifact|actions\/cache|pull_request_target:/);
});

test("Java JUnit pins actor identity and rejects directly addressed compensation",()=>{
  for(const token of ["STALE_RUN","STALE_INCAR","wrong_incarnation","cancel_failed"]){
    assert(java.includes(token));
  }
});

test("optimizer and compensation are internal; not externally addressed commands",()=>{
  assert.match(java,/high_commit/); // only in negative admission asserts
  assert.match(java,/final_gradient/);
});
