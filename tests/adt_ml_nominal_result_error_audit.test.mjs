// Host-only verification of the narrow nominal Result error-constructor preflight.
// Real compilation/JUnit and exhaustive matching are separate gates.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const read=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const tc=read("src/main/java/dev/oreslang/types/TypeChecker.java");
const helper=read("src/main/java/dev/oreslang/types/NominalResultErrorAudit.java");
const junit=read("src/test/java/dev/oreslang/NominalResultErrorAuditTest.java");
test("nominal preflight runs after registry and before actor state admission",()=>{
  const registry=tc.indexOf("TaggedMessageContracts.forTypes(program)");
  const audit=tc.indexOf("NominalResultErrorAudit.verify(program, tagged)");
  const state=tc.indexOf("ActorStateContract.verify(program)");
  const effect=tc.indexOf("DeclaredEffectVerifier.verify(program)");
  const deny=tc.indexOf("nominal enum/pattern declarations are not yet executable");
  assert(registry>=0&&registry<audit&&audit<state&&state<effect&&effect<deny);
});
test("constructor checks nominal enum origin, case existence, arity and type args",()=>{
  assert.match(helper,/expectedOwner\.equals\(owner\.name\(\)\)/);
  assert.match(helper,/registry\.requireCase\(/);
  assert.match(helper,/variant\.sourcePayload\(\) == null \? 0 : 1/);
  assert.match(helper,/ctor\.typeArgumentsPresent\(\)/);
  assert.match(helper,/call\.typeArgumentsPresent\(\)/);
});
test("scope is type-only and executable nominal source remains denied",()=>{
  assert.match(helper,/cannot prove general expression or variable typing/);
  assert.match(tc,/nominal enum\/pattern declarations are not yet executable/);
  assert.match(junit,/typeOnlyAcceptanceDoesNotUnlockRuntime/);
  assert.match(junit,/not yet executable/);
});
test("JUnit has explicit positive and negative nominal cases",()=>{
  for(const name of [
    "acceptsDirectNominalErrorAtTypeOnlyStage",
    "rejectsStringInNominalErrorPosition",
    "distinguishesIdenticallyTaggedForeignEnum",
    "checksUnknownCasesAndPayloadArity",
    "checksTypedResultBindingsAndBranches"
  ])assert(junit.includes(name),name);
});
