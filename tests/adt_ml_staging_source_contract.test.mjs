// Host-only integration inventory; never infer GraalVM execution.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync,existsSync} from "node:fs";
const text=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
test("preserve guest actor effect syntax and state-loan checks",()=>{
  assert.match(text("src/main/java/dev/oreslang/parser/Parser.java"),/state\.write/);
  assert.match(text("src/main/java/dev/oreslang/parser/Parser.java"),/using state/);
  assert.match(text("src/main/java/dev/oreslang/types/TypeChecker.java"),/ActorStateContract/);
  assert.match(text("src/main/java/dev/oreslang/types/OwnershipChecker.java"),/ActorStateContract/);
});
test("ADT-only nominal compiler support classes are staged, not substituted",()=>{
  for(const file of [
    "src/main/java/dev/oreslang/runtime/TaggedMessageCodec.java",
    "src/main/java/dev/oreslang/runtime/MatchContinuationQueue.java",
    "src/main/java/dev/oreslang/types/NominalEnumValueGuards.java",
    "src/main/java/dev/oreslang/types/TaggedMessageContracts.java"
  ])assert(existsSync(new URL("../"+file,import.meta.url)),file+" absent");
});
test("documentation must retain noncertification gates",()=>{
  const s=text("docs/audits/ML_ADT_SAME_ORG_RECONCILIATION_20261018.md");
  assert.match(s,/INTEGRATION=BLOCKED/);
  assert.match(s,/JAVA_GUEST=NOT_EXECUTED/);
  assert.match(s,/DO NOT MERGE/);
  assert.match(s,/17 (?:original )?overlapping/);
});
