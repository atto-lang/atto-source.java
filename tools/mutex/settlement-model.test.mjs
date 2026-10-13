import test from "node:test";
import assert from "node:assert/strict";

// Bounded abstraction of a SINGLE queued SharedMutex waiter and one holder.
// This does not inspect Java state; it checks the intended publication order.
const initial=()=>({phase:"QUEUED",edge:true,admitted:true,settles:0,visible:false,outcome:null,owner:"holder"});
const actions=["timeout","cancel","holder_release","publish_handoff","waiter_release"];
function transition(s,action){
 const n={...s};
 switch(action){
  case "timeout":
  case "cancel":
   if(s.phase==="QUEUED"){
    n.phase="TERMINAL";n.edge=false;n.admitted=false;n.settles++;n.visible=true;n.outcome=action;
   }
   break;
  case "holder_release":
   if(s.owner!=="holder")break;
   if(s.phase==="QUEUED"){n.phase="CLAIMED";n.owner="transfer";}
   else n.owner="free";
   break;
  case "publish_handoff":
   if(s.phase==="CLAIMED"&&s.owner==="transfer"){
    n.phase="AWARDED";n.edge=false;n.admitted=false;n.settles++;n.visible=true;n.outcome="success";n.owner="waiter";
   }
   break;
  case "waiter_release":
   if(s.phase==="AWARDED"&&s.owner==="waiter"){
    n.phase="RELEASED";n.owner="free";
   }
   break;
 }
 return n;
}
function invariant(s){
 assert.equal(!(s.visible&&s.edge),true,"Future visible while WAITING_ON edge exists");
 assert.equal(!(s.visible&&s.admitted),true,"Future visible before waiter quota settlement");
 assert.ok(s.settles<=1,"quota double-released");
 if(s.visible)assert.equal(s.settles,1,"visible Future must have exactly one settlement");
 if(s.phase==="CLAIMED"){
  assert.equal(s.visible,false,"dequeue cannot publish incomplete physical handoff");
  assert.equal(s.owner,"transfer");
 }
 if(s.outcome==="success"){
  assert.equal(s.edge,false);
  assert.ok(s.owner==="waiter"||s.owner==="free","awarded permit must not be held by old owner");
 }
 if(s.outcome==="cancel"||s.outcome==="timeout"){
  assert.notEqual(s.owner,"waiter","rejected waiter must not own permit");
 }
 if(s.phase==="TERMINAL")assert.equal(s.edge,false);
}
test("enumerate all bounded timeout/cancel/dequeue/publication permutations",()=>{
 const queue=[initial()],seen=new Set(),outcomes=new Set();
 while(queue.length){
  const s=queue.shift(),key=JSON.stringify(s);
  if(seen.has(key))continue;
  seen.add(key);invariant(s);
  if(s.outcome)outcomes.add(s.outcome);
  for(const action of actions)queue.push(transition(s,action));
 }
 assert.ok(seen.size>=7,"expected all physically distinct states");
 assert.deepEqual([...outcomes].sort(),["cancel","success","timeout"]);
});
test("invariant detects the historical publish-before-unlink defect",()=>{
 const bad={...initial(),phase:"TERMINAL",visible:true,outcome:"timeout"};
 assert.throws(()=>invariant(bad),/Future visible while WAITING_ON/);
});

test("invariant detects publication while quota is still charged",()=>{
 const bad={...initial(),phase:"TERMINAL",visible:true,edge:false,admitted:true,outcome:"cancel"};
 assert.throws(()=>invariant(bad),/quota settlement/);
});
