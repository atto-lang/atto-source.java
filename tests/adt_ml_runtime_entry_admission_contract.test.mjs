// Source-only bridge safeguard; NOT actual Truffle execution.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const root=readFileSync(new URL("../src/main/java/dev/oreslang/nodes/OresEvalRootNode.java",import.meta.url),"utf8");
const language=readFileSync(new URL("../src/main/java/dev/oreslang/OresLanguage.java",import.meta.url),"utf8");
const compiler=readFileSync(new URL("../src/main/java/dev/oreslang/compiler/OresCompiler.java",import.meta.url),"utf8");
test("Polyglot parse routes through semantic checking before constructing a root",()=>{
  assert.match(language,/OresCompiler\.parseAndTypeCheck\(text\)/);
  assert.match(compiler,/TypeChecker\.checkTypes\(program\)/);
  assert.match(compiler,/OwnershipChecker\.check\(program\)/);
});
test("direct RootNode constructor refuses nominal AST even if compiler was skipped",()=>{
  const ctor=root.slice(root.indexOf("public OresEvalRootNode(OresLanguage language, Ast.Program program, String codeUnitId)"));
  assert.match(ctor,/Objects\.requireNonNull\(program/);
  assert.match(ctor,/declaration instanceof Ast\.EnumDecl/);
  assert.match(ctor,/declaration instanceof Ast\.PatternDecl/);
  const refuse=ctor.indexOf("nominal enum/pattern runtime lowering is not yet integrated");
  const assignment=ctor.indexOf("this.program = program");
  assert(refuse>0&&refuse<assignment,"must reject before publishing executable AST");
});
test("runtime guard does not replace actor capability checks",()=>{
  assert.match(root,/CapabilityChecker\.check\(program, context\.isolatePolicy\(\), context\.permissionCheckMode\(\)\)/);
  assert.match(root,/ActorStateContract/);
});

test("actual Polyglot negative fixture does not permit main side effects",()=>{
  const java=readFileSync(new URL("../src/test/java/dev/oreslang/NominalActorBridgeFailClosedTest.java",import.meta.url),"utf8");
  assert.match(java,/realPolyglotSourceMustRejectEnumBeforeAnyMainSideEffects/);
  assert.match(java,/assertThrows\(PolyglotException\.class, \(\) -> context\.eval\(source\)\)/);
  assert.match(java,/assertEquals\("", output\.toString\(StandardCharsets\.UTF_8\)\)/);
});
