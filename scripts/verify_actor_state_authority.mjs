#!/usr/bin/env node
"use strict";
/*
 * Bounded explicit-state check for the Oreslang sealed actor-state authority
 * contract. No package dependencies; run: node scripts/verify_actor_state_authority.mjs
 *
 * Abstraction: one owner actor (1), one foreign actor (2), values {0,1}, a
 * stopped/retired phase, no/read/write loan and a runtime-only retirement
 * permit. Every reachable transition is checked (not random sampling).
 * Mutations establish that the checker detects owner-ID-only retirement,
 * read-loan writes, foreign writes, and foreign finalization.
 *
 * This is NOT a whole-program proof of JVM reflection/Unsafe/FFI absence,
 * compiler AST/codegen soundness, or a scheduler implementation. The abstract
 * source/runtime refinement assumptions must be separately discharged.
 */

function successors(s, mutant) {
  const out = [];
  const add = (label, changes) => out.push([label, {...s, ...changes}]);
  if (!s.stopped && !s.retired && s.lane === 0) {
    add("admit.owner", {lane:1});
    add("admit.foreign", {lane:2});
  }
  if (s.lane !== 0 && s.loan === 0) add("release.lane", {lane:0});
  if (s.lane === 1 && s.loan === 0 && !s.stopped && !s.retired) {
    add("grant.read", {loan:1});
    add("grant.write", {loan:2});
  }
  if (s.lane === 1 && s.loan !== 0) add("revoke.loan", {loan:0});
  if (s.lane === 1 && !s.retired && (s.loan === 2 || (mutant === "read-upgrade" && s.loan === 1))) {
    add("write.owner", {value:s.value === 0 ? 1 : 0});
  }
  // A foreign executing actor can try to spoof the owner identity.
  if (mutant === "foreign-write" && s.lane === 2 && !s.retired) {
    add("write.foreign", {value:s.value === 0 ? 1 : 0});
  }
  if (!s.stopped && s.lane === 0 && s.loan === 0) add("stop", {stopped:true});
  if (s.stopped && !s.retired && s.lane === 0 && !s.permit) {
    add("runtime.begin-retirement", {permit:true});
  }
  if (s.permit) add("runtime.end-retirement", {permit:false});
  // A caller might know the legitimate ActorId: that alone is NOT a permit.
  if (s.stopped && !s.retired && s.lane === 0 && s.loan === 0 &&
      (s.permit || mutant === "owner-id-as-permit")) {
    add("retire.owner", {retired:true, permit:false, value:-1});
  }
  if (mutant === "foreign-retirement" && s.stopped && !s.retired &&
      s.lane === 0 && s.permit) {
    add("retire.foreign", {retired:true, permit:false, value:-1});
  }
  return out;
}

function safety(before, event, after) {
  if (after.retired && !before.retired &&
      !(event === "retire.owner" && before.permit && before.stopped &&
        before.lane === 0 && before.loan === 0)) {
    return "state retired without trusted quiescent owner-specific authority";
  }
  if (before.value !== after.value) {
    if (event === "write.owner") {
      if (!(before.lane === 1 && before.loan === 2 && !before.stopped && !before.retired))
        return "state write without owning lane and exclusive live grant";
    } else if (event === "retire.owner") {
      if (!(before.permit && before.stopped && before.lane === 0 && before.loan === 0))
        return "state wipe without quiescent runtime authorization";
    } else {
      return "state changed through unrecognized or foreign transition";
    }
  }
  if (before.retired && (after.value !== -1 || !after.retired))
    return "retired state was resurrected or changed";
  if (after.permit && (!after.stopped || after.retired || after.lane !== 0))
    return "retirement permit active during guest execution";
  if (after.loan !== 0 && (after.lane !== 1 || after.stopped || after.retired))
    return "live state loan outside an owning guest turn";
  return null;
}

function explore(mutant) {
  const initial={lane:0,loan:0,permit:false,stopped:false,retired:false,value:0};
  const encode = s=>JSON.stringify(s);
  const seen=new Set([encode(initial)]);
  const queue=[[initial,[]]];
  let edges=0, retirements=0, writes=0, refused=0;
  for(let cursor=0;cursor<queue.length;cursor++) {
    const [state,trace]=queue[cursor];
    for(const [event,next] of successors(state,mutant)) {
      edges++;
      const violation=safety(state,event,next);
      if(violation)return {ok:false, mutant, violation, trace:[...trace,event], states:seen.size, edges};
      if(event==="retire.owner")retirements++;
      if(event==="write.owner")writes++;
      const key=encode(next);
      if(!seen.has(key)){
        seen.add(key);
        queue.push([next,[...trace,event]]);
        if(seen.size>5000)throw Error("model unexpected state-space growth");
      }
    }
  }
  if(retirements===0||writes===0)throw Error("coverage failure: no authorized write/retirement");
  return {ok:true, mutant, states:seen.size, edges, retirements, writes};
}
const baseline=explore(null);
for (const mutant of ["owner-id-as-permit", "read-upgrade", "foreign-write", "foreign-retirement"]) {
  const test=explore(mutant);
  if (test.ok)throw new Error("undetected authority mutant: "+mutant);
}
console.log("actor-state authority model PASS", JSON.stringify(baseline));
