// Isolated host-only meta-regressions for the source admission gate.
import test from "node:test";
import assert from "node:assert/strict";
import {inspectCompilerSources as inspect} from "../scripts/adt-ml-compiler-admission.mjs";
import {readFileSync} from "node:fs";
const read=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const placeholders={
  "src/main/java/dev/oreslang/ast/Ast.java":"record EnumDecl(String name",
  "src/main/java/dev/oreslang/parser/Parser.java":"match(ENUM)\nprivate Ast.EnumDecl parseTaggedEnum()\nstate.write using state",
  "src/main/java/dev/oreslang/types/TypeChecker.java":"TaggedMessageContracts.forTypes(program)\ntaggedMessages.requireCase(\nActorStateContract.verify(program)\nDeclaredEffectVerifier.verify(program)",
  "src/main/java/dev/oreslang/types/OwnershipChecker.java":"ActorStateContract TaggedMessageContracts",
  "src/main/java/dev/oreslang/nodes/OresEvalRootNode.java":"ActorStateContract TaggedMessageContracts"
};
test("synthetic nominal + actor source markers do not claim guest execution",()=>{
  const r=inspect(p=>placeholders[p]);
  assert.equal(r.ready,true);
  assert.equal(r.sourceOnly,true);
});
test("nominal type-name reference alone cannot pass unimplemented typechecking",()=>{
  const r=inspect(p=>p.endsWith("TypeChecker.java")?"Ast.EnumDecl":placeholders[p]);
  assert.equal(r.ready,false);
  assert(r.missing.includes("nominal case type-resolution"));
});
test("an explicit temporary denial always dominates superficially complete markers",()=>{
  const r=inspect(p=>p.endsWith("TypeChecker.java")
     ?placeholders[p]+"\nnominal enum/pattern declarations are not yet executable"
     :placeholders[p]);
  assert.equal(r.ready,false);
  assert(r.missing.some(m=>m.includes("temporary fail-closed")));
});
test("missing ownership/runtime paths are never ignored",()=>{
  for(const bad of [
    "src/main/java/dev/oreslang/types/OwnershipChecker.java",
    "src/main/java/dev/oreslang/nodes/OresEvalRootNode.java"
  ]){
    const r=inspect(p=>{if(p===bad)throw Error("unavailable");return placeholders[p]});
    assert.equal(r.ready,false);
    assert(r.missing.some(x=>x.includes("unreadable")));
  }
});
test("current exact source remains intentionally uncertified",()=>{
  const result=inspect(read);
  assert.equal(result.ready,false);
  assert(result.missing.some(m=>m.includes("nominal runtime lowering")||
     m.includes("temporary fail-closed")));
});
