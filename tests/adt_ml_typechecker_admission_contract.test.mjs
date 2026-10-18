// Source-only acceptance of a deliberate non-executable nominal bridge.
// These checks DO NOT compile Java, execute Oreslang or prove sound typing.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const source=readFileSync(new URL("../src/main/java/dev/oreslang/types/TypeChecker.java",import.meta.url),"utf8");
test("nominal declarations are validated before actor/effect admission",()=>{
  const registry=source.indexOf("TaggedMessageContracts.Registry tagged = TaggedMessageContracts.forTypes(program)");
  const actors=source.indexOf("ActorStateContract.verify(program)");
  const effects=source.indexOf("DeclaredEffectVerifier.verify(program)");
  const reject=source.indexOf("nominal enum/pattern declarations are not yet executable");
  const validate=source.indexOf("checker.validate(program)");
  assert(registry>0&&registry<actors&&actors<effects&&effects<reject&&reject<validate);
  assert.match(source,/checker\.taggedMessages = tagged;/);
});
test("nominal enum and pattern names reserve the lexical namespace",()=>{
  assert.match(source,/instanceof Ast\.EnumDecl enumeration\) localNames\.add\(enumeration\.name\(\)\)/);
  assert.match(source,/instanceof Ast\.PatternDecl pattern\) localNames\.add\(pattern\.name\(\)\)/);
  assert.match(source,/imported name.*conflicts with a local or builtin name/);
});
test("unsupported runtime paths reject before ordinary method typing",()=>{
  assert.match(source,/if \(!tagged\.enums\(\)\.isEmpty\(\) \|\| !tagged\.patterns\(\)\.isEmpty\(\)\)/);
  assert.match(source,/throw new IllegalArgumentException\(/);
  assert.match(source,/exhaustive match and runtime lowering first/);
  assert.match(source,/ActorStateContract\.verify\(program\)/);
  assert.match(source,/DeclaredEffectVerifier\.verify\(program\)/);
});

test("JUnit regression includes parser-positive semantic rejection and legacy positive control",()=>{
  const java=readFileSync(new URL("../src/test/java/dev/oreslang/NominalActorBridgeFailClosedTest.java",import.meta.url),"utf8");
  assert.match(java,/Parser\.parse\(ENUM\)/);
  assert.match(java,/TypeChecker\.check\(parsed\)/);
  assert.match(java,/not yet executable/);
  assert.match(java,/enumTagAndCaseCollisionStillRejectBeforeExecutableAdmission/);
  assert.match(java,/ordinaryNonNominalProgramsRemainAdmissible/);
  assert.match(java,/assertDoesNotThrow\(\(\) -> TypeChecker\.check\(parsed\)\)/);
});
