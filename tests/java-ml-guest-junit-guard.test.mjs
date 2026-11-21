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
  assert.match(java,/f4b77392173631475a0dda6a835aa69361a69557/);
  assert.match(java,/b602f9821817696f16499af1a74cb22d49a0ecab/);
  assert.match(java,/assertEquals\(expected, stdout\.toString/);
});


test("typed actor JUnit contract pins nominal errors and negative-success golden",()=>{
  assert.match(java,/private print_outcome\(Result<int, TrainingError> outcome\): void/);
  for(const error of ["INVALID_INPUT","INVALID_GRADIENT","UNSAFE_CANDIDATE"]){
    assert(java.includes("return Err(TrainingError."+error+"())"));
  }
  assert.match(java,/define enum TrainingError as/);
  assert.match(java,/match problem over/);
  assert.match(java,/nominalTrainingErrorsRequireExactPayloadTypesAndExhaustiveCases/);
  assert.match(java,/self\.eval_affine\(3, 3, 2, -2, 101, 1, 202\)/);
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
  assert.equal(fixtureSha, "f4b77392173631475a0dda6a835aa69361a69557");
  assert.equal(goldenSha, "b602f9821817696f16499af1a74cb22d49a0ecab");
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

test("Java compiled fixture requires internal lifecycle completion rather than external end mail",()=>{
  assert.match(java,/demo_finish_ready/);
  assert.match(java,/final_result\.is_err/);
  assert.match(java,/mail\.value ==/); // the Java gate explicitly prohibits raw after command
});

test("graph epoch authority is separately checked in guest gate",()=>{
  assert.match(java,/STALE_GRAPH/);
  assert.match(java,/graph_id: int = 202/);
  assert.match(java,/wrong_graph/);
});

test("nominal guest positive-control and equal-tag foreign enum denial are mandatory",()=>{
  const positive=section("@Test void actualGuestNominalErrorRoundTripAndExhaustiveResultMatch()",
      "@Test void nominalTrainingErrorsRequireExactPayloadTypesAndExhaustiveCases()");
  assert.match(positive,/assertDoesNotThrow\(\(\) -> verify\(program\)/);
  assert.match(positive,/context\.eval\(executable\)/);
  assert.match(positive,/assertEquals\("-8\\n-10\\n"/);
  assert.match(positive,/LocalTrainingError\.STALE_GRAPH\(\)/);
  const negative=section("@Test void nominalTrainingErrorsRequireExactPayloadTypesAndExhaustiveCases()",
      "@Test void readEffectsDoNotGrantStateWriteAndLoanCannotEscape()");
  assert.match(negative,/ForeignTrainingError\.INVALID_INPUT\(\)/);
  assert.match(negative,/assertRejected\(wrongNominalIdentity\)/);
  assert.match(negative,/assertGuestRejectedBeforeMain\(wrongNominalIdentity\)/);
  assert.match(negative,/Parser\.parse\(wrongNominalIdentity\)/);
  assert.match(negative,/assertRejected\(omittedCase\)/);
});

// Source-only prerequisite guard. The minimal local nominal enum compiler
// slice is now integrated; this does not certify runtime execution or actor-wire enums.
const parser=readFileSync(new URL("../src/main/java/dev/oreslang/parser/Parser.java",import.meta.url),"utf8");
test("ML guest branch integrates a real nominal enum parser prerequisite",()=>{
  assert.match(parser,/private Ast\.EnumDecl parseTaggedEnum\(\)/,
      "nominal enum declarations absent; integrate ADT compiler PR #623 before claiming ML guest conformance");
  assert.match(parser,/match\(ENUM\)/,
      "nominal enum syntax not dispatched by parser; full guest is not admissible");
});
