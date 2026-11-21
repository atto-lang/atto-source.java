// HOST-ONLY source integrity guard. Real JUnit/Graal execution remains mandatory.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createHash} from "node:crypto";

const java=readFileSync(
  new URL("../src/test/java/dev/oreslang/MlActorStateScalarGuestTest.java",import.meta.url),
  "utf8");
const fixture=readFileSync(
  new URL("./fixtures/ml-actor-state-scalar.ores",import.meta.url));
const golden=readFileSync(
  new URL("./golden/ml-actor-state-scalar.stdout",import.meta.url));
const parser=readFileSync(
  new URL("../src/main/java/dev/oreslang/parser/Parser.java",import.meta.url),
  "utf8");
const checker=readFileSync(
  new URL("../src/main/java/dev/oreslang/types/TypeChecker.java",import.meta.url),
  "utf8");

function section(start,end){
  const a=java.indexOf(start),b=java.indexOf(end,a+start.length);
  assert(a>=0&&b>a,"missing Java method section boundary: "+start);
  return java.slice(a,b);
}
function gitBlobSha(data){
  const raw=Buffer.isBuffer(data)?data:Buffer.from(data);
  return createHash("sha1")
    .update("blob "+raw.length+"\0")
    .update(raw)
    .digest("hex");
}

test("hardened ML fixture and golden are pinned by real Git blob identity",()=>{
  assert.equal(gitBlobSha(fixture),"ea1bf33853d6fb1e8c526c5a35456b4115592ab7");
  assert.equal(gitBlobSha(golden),"b602f9821817696f16499af1a74cb22d49a0ecab");
  assert.match(java,/ea1bf33853d6fb1e8c526c5a35456b4115592ab7/);
  assert.match(java,/b602f9821817696f16499af1a74cb22d49a0ecab/);
});

test("minimal nominal enum compiler prerequisite is real, not source-only spelling",()=>{
  assert.match(parser,/private Ast\.EnumDecl parseTaggedEnum\(\)/);
  assert.match(parser,/match\(ENUM\)/);
  assert.match(checker,/private Ast\.EnumDecl findEnum\(String name\)/);
  assert.match(checker,/allMatch\(enumCase -> covered\.contains\(enumCase\.name\(\)\)\)/);
  assert.match(java,/actualGuestNominalErrorRoundTripAndExhaustiveResultMatch/);
  assert.match(java,/ForeignTrainingError\.INVALID_INPUT\(\)/);
  assert.match(java,/assertRejected\(omittedCase\)/);
});

test("optimizer commit uses detached snapshot and atomic publication",()=>{
  const text=fixture.toString("utf8");
  const start=text.indexOf("    private commit(");
  const end=text.indexOf("    // A controlled recovery path",start);
  assert(start>=0&&end>start);
  const commit=text.slice(start,end);
  assert.match(commit,/with state\.read, state\.write, alloc/);
  assert.match(commit,/state_snapshot\(/);
  assert.match(commit,/snapshot_version\(snapshot\)/);
  assert.match(commit,/snapshot_int\(snapshot, "weight"\)/);
  assert.match(commit,/snapshot_bool\(snapshot, "seen_first"\)/);
  assert.match(commit,/prepare_scalar_publication\(/);
  assert.match(commit,/publish_scalar_publication\(s, publication\)/);
  assert.doesNotMatch(commit,/s\.weight\s*=/);
  assert.doesNotMatch(commit,/s\.version\s*=/);
});

test("typed actor gate preserves nominal errors and negative-success distinction",()=>{
  for(const error of [
    "STALE_RUN","STALE_GRAPH","STALE_INCAR","STALE_VERSION","STALE_STEP",
    "INVALID_INPUT","DUPLICATE_FORWARD","INVALID_MICROBATCH",
    "INVALID_GRADIENT","ORPHAN_GRADIENT","DUPLICATE_GRADIENT",
    "INCOMPLETE_FANIN","COUNTER_EXHAUSTED","UNSAFE_CANDIDATE",
    "VALID_CANDIDATE_MUST_COMMIT"
  ]){
    assert(fixture.toString("utf8").includes(error));
  }
  assert.match(java,/private print_outcome\(Result<int, TrainingError> outcome\): void/);
  assert.match(java,/assertEquals\("-8\\n-10\\n"/);
  assert.match(fixture.toString("utf8"),/self\.eval_affine\(3, 3, 2, -2, 101, 1, 202\)/);
});

test("actual invalid guest helper requires parser-positive semantic rejection before eval",()=>{
  const body=section(
    "private static void assertGuestRejectedBeforeMain(",
    "private static String gitBlobSha(");
  const parse=body.indexOf("Parser.parse(invalidSource)");
  const semantic=body.indexOf("verify(invalidSource)");
  const execute=body.indexOf("context.eval(executable)");
  assert(parse>=0&&semantic>parse&&execute>semantic);
  assert.match(body,/assertThrows\(IllegalArgumentException\.class/);
  assert.match(body,/assertThrows\(PolyglotException\.class/);
  assert.match(body,/assertEquals\("", output\.toString/);
});

test("canonical actor lifecycle barriers remain part of the ML acceptance fixture",()=>{
  const text=fixture.toString("utf8");
  assert.match(text,/await worker\.get_ready\(\);/);
  assert.match(text,/await worker\.get_done\(\);/);
  assert.doesNotMatch(text,/await worker\.ready;/);
  assert.doesNotMatch(text,/await worker\.done;/);
});

test("blind publication overwrite has a dedicated compiler negative",()=>{
  assert.match(java,/optimizerPublicationCannotOverwriteUncapturedState/);
  assert.match(java,/"secret", 0/);
  assert.match(java,/state_snapshot\(s, "version", "weight"\)/);
});
