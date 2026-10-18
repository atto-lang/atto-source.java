// HOST-ONLY test-file integrity guard; actual JUnit compilation remains mandatory.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
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
  assert.match(java,/4ae673f093a16538da784d15819debbef439b0db/);
  assert.match(java,/9921f755979832f6b20e20923cc94f6f3bf6e05b/);
  assert.match(java,/assertEquals\(expected, stdout\.toString/);
});
