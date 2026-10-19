// Host-only workflow audit; DOES NOT mean any Actions job executed.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const yml=readFileSync(new URL("../.github/workflows/ml-scalar-actor-guest.yml",import.meta.url),"utf8");
test("exact-source checkout precedes all source and JUnit gates",()=>{
  assert.match(yml,/persist-credentials: false/);
  assert.match(yml,/git rev-parse HEAD/);
  const hash=yml.indexOf("Fail closed on scalar fixture/golden drift");
  const node=yml.indexOf("Host-only ADT + actor source-admission regressions");
  const gate=yml.indexOf("Fail closed on incomplete nominal runtime");
  const java=yml.indexOf("Real negative-control and nominal-positive GraalVM JUnit");
  const maven=yml.indexOf("Execute real parser, effect, ownership and scalar actor guest");
  assert(hash>0&&hash<node&&node<gate&&gate<java&&java<maven);
  assert.match(yml,/node scripts\/verify-adt-ml-integration\.mjs/);
});
test("source checks are not called Java/GraalVM certification",()=>{
  assert.match(yml,/Host-only ADT \+ actor source-admission regressions/);
  assert.match(yml,/TrainingErrorNominalResultGuestTest/);
  assert.match(yml,/NominalActorBridgeFailClosedTest/);
  assert.match(yml,/MlActorStateScalarGuestTest/);
  assert.match(yml,/NODE_FINDS_NO_PRIVATE_CROSS_ORG_SOURCE|CROSS_ORG_PRIVATE_SOURCE_EXPORT=NONE|CROSS_ORG_PRIVATE_SOURCE_EXPORT=NONE|PRIVATE_SOURCE_NOT_MIRRORED=TRUE/);
});
test("workflow remains original repository CI with immutable action pins",()=>{
  assert.match(yml,/actions\/checkout@[0-9a-f]{40}/);
  assert.match(yml,/graalvm\/setup-graalvm@[0-9a-f]{40}/);
  assert.doesNotMatch(yml,/^\s+uses:\s+[^\s]+@v[0-9]+/m);
  assert.match(yml,/permissions:\n  contents: read/);
});
