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
  const toolchain=yml.indexOf("Install pinned GraalVM/JDK 25");
  const compile=yml.indexOf("Compile all production and test Java sources");
  const negative=yml.indexOf("Execute parser-positive / runtime-negative admission JUnit");
  const gate=yml.indexOf("Fail closed on incomplete nominal runtime");
  const positive=yml.indexOf("Execute positive nominal ADT GraalVM JUnit");
  const maven=yml.indexOf("Execute real parser, effect, ownership and scalar actor guest");
  assert(hash>0&&hash<node&&node<toolchain&&toolchain<compile&&compile<negative&&negative<gate&&gate<positive&&positive<maven);
  assert.match(yml,/node scripts\/verify-adt-ml-integration\.mjs/);
});
test("source checks are not called Java/GraalVM certification",()=>{
  assert.match(yml,/Host-only ADT \+ actor source-admission regressions/);
  assert.match(yml,/TrainingErrorNominalResultGuestTest/);
  assert.match(yml,/NominalActorBridgeFailClosedTest,NominalResultErrorAuditTest,NominalResultTraversalTest test/);
  assert.match(yml,/DskipTests test-compile/);
  assert.match(yml,/node scripts\/verify-adt-ml-integration\.mjs/);
  assert.match(yml,/MlActorStateScalarGuestTest/);
  assert.match(yml,/NODE_FINDS_NO_PRIVATE_CROSS_ORG_SOURCE|CROSS_ORG_PRIVATE_SOURCE_EXPORT=NONE|CROSS_ORG_PRIVATE_SOURCE_EXPORT=NONE|PRIVATE_SOURCE_NOT_MIRRORED=TRUE/);
});
test("workflow remains original repository CI with immutable action pins",()=>{
  assert.match(yml,/actions\/checkout@[0-9a-f]{40}/);
  assert.match(yml,/graalvm\/setup-graalvm@[0-9a-f]{40}/);
  assert.doesNotMatch(yml,/^\s+uses:\s+[^\s]+@v[0-9]+/m);
  assert.match(yml,/permissions:\n  contents: read/);
});
