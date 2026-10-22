import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {loadContract,validateCapturedGuestLog} from "../scripts/check_core_math_guest.mjs";
const file=new URL("../formal/typed-scalar/core-math-i64-v1.json",import.meta.url);
const cases=loadContract(readFileSync(file));
const valid=[...cases].map(([id,value])=>"CORE-MATH-CASE|"+id+"|"+value)
  .concat(["CORE-MATH-DONE",""]).join("\n");

test("fake stdout parser fixture (NOT guest execution) accepts full exact match",()=>{
  const result=validateCapturedGuestLog("[INFO] Maven banner\n"+valid,cases);
  assert.equal(result.executedCases,24);
});
test("missing, duplicated, broken, unknown or incorrect guest outputs fail",()=>{
  const lines=valid.split("\n");
  const mutations=[
    lines.slice(1).join("\n"),
    valid+"CORE-MATH-DONE\n",
    valid.replace("CORE-MATH-DONE",""),
    valid.replace("CORE-MATH-CASE","CORE-MATH-CASE|oops|"),
    valid.replace("CORE-MATH-CASE|", "CORE-MATH-CASE|unknown-id|0\nCORE-MATH-CASE|"),
    valid.replace(lines[0],lines[0]+"\n"+lines[0]),
    valid.replace(lines[0],lines[0].replace(/-?[0-9]+$/, "999")),
    valid.replace(lines[0],lines[0]+"garbage"),
    "",
  ];
  for(const bad of mutations)assert.throws(()=>validateCapturedGuestLog(bad,cases));
});
test("contract fixture is byte-locked and fails on mutation",()=>{
  const raw=readFileSync(file);
  for(const different of [Buffer.from(raw.toString()+" "),raw.subarray(1),
    Buffer.from(raw.toString().replace('"schemaVersion": 1','"schemaVersion": 2'))]){
    assert.throws(()=>loadContract(different));
  }
});
