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
  assert.match(java,/26a3cb34b126185d56f5d2c7ff370cab953b0b4b/);
  assert.match(java,/867f4ca71a20b58b1f35e239d4bef160f7ef5342/);
  assert.match(java,/assertEquals\(expected, stdout\.toString/);
});


test("typed actor JUnit contract pins four Results and negative-success golden",()=>{
  assert.match(java,/assertTrue\(text\.contains\("private print_outcome\(Result<int, String> outcome\): void"\)\)/);
  for(const error of ["INVALID_INPUT","INVALID_GRADIENT","UNSAFE_CANDIDATE"]){
    assert(java.includes('return Err(\\\"'+error+'\\\")'));
  }
  assert.match(java,/self\.eval_affine\(3, 3, 2, -2\)/);
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
  assert.equal(fixtureSha, "26a3cb34b126185d56f5d2c7ff370cab953b0b4b");
  assert.equal(goldenSha, "867f4ca71a20b58b1f35e239d4bef160f7ef5342");
  assert.match(java, new RegExp(fixtureSha));
  assert.match(java, new RegExp(goldenSha));
  assert.match(workflow, new RegExp(fixtureSha));
  assert.match(workflow, new RegExp(goldenSha));
  assert.equal(workflow.split('      - "tests/golden/ml-actor-state-scalar.stdout"').length - 1, 1, "one golden path filter only");
  assert.match(workflow, /-Dtest=MlActorStateScalarGuestTest test/);
  assert.match(workflow, /-Dores\\.runtime\\.carriers=native clean verify/);
  assert.doesNotMatch(workflow, /uses: (?:actions\\/checkout|graalvm\\/setup-graalvm)@v[0-9]/);
});
