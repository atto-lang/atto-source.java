import test from "node:test";
import assert from "node:assert/strict";
import {validateTensor,requireVerifiedBackend} from "./validate.mjs";
const t=()=>({dtype:"f32",shape:[2,3],strides:[3,1],byte_length:24,layout:"row_major",device:"cpu",ownership:"moved",lifetime:"actor_incarnation"});
test("valid tensor",()=>assert.equal(validateTensor(t(),{acrossActor:true}).requiredBytes,24));
test("reject actor borrow escape",()=>assert.throws(()=>validateTensor({...t(),ownership:"borrowed_local"},{acrossActor:true}),/borrowed alias/));
test("reject short buffer",()=>assert.throws(()=>validateTensor({...t(),byte_length:23}),/byte bounds/));
test("reject declared layout mismatch",()=>assert.throws(()=>validateTensor({...t(),strides:[1,2]}),/noncontiguous/));
test("reject large stride",()=>assert.throws(()=>validateTensor({...t(),strides:[Number.MAX_SAFE_INTEGER+1,1]}),/safe integer/));
test("reject unknown field",()=>assert.throws(()=>validateTensor({...t(),nativePointer:2}),/unknown/));
test("reject unadmitted GPU",()=>assert.throws(()=>validateTensor({...t(),device:"gpu"}),/UNSUPPORTED_BACKEND/));
test("fail closed without backend evidence",()=>assert.throws(()=>requireVerifiedBackend({},"ml_actor"),/UNSUPPORTED_BACKEND/));
test("zero extent has zero length",()=>assert.equal(validateTensor({...t(),shape:[0,3],byte_length:0}).requiredBytes,0));
test("reject inherited backend authority",()=>assert.throws(()=>requireVerifiedBackend(Object.create({ml_actor:"verified"}),"ml_actor"),/UNSUPPORTED_BACKEND/));
test("accept explicit trusted backend evidence tag",()=>assert.doesNotThrow(()=>requireVerifiedBackend({ml_actor:"verified"},"ml_actor")));
test("reject prototype property dtype",()=>assert.throws(()=>validateTensor({...t(),dtype:"constructor"}),/unsupported dtype/));
test("reject strided mutable broadcast alias",()=>assert.throws(()=>validateTensor({...t(),layout:"strided",strides:[0,0],byte_length:24}),/overlapping mutable strides/));
test("allow frozen read-only aliased strided tensor",()=>assert.equal(validateTensor({...t(),layout:"strided",strides:[0,0],byte_length:4,ownership:"frozen_shared"},{acrossActor:true}).requiredBytes,4));
test("allow provably injective small mutable strided view",()=>assert.equal(validateTensor({...t(),layout:"strided",strides:[6,2],byte_length:44}).requiredBytes,44));
test("fail closed on large mutable strided layout proof",()=>assert.throws(()=>validateTensor({...t(),layout:"strided",shape:[100,100],strides:[100,1],byte_length:40000}),/UNSUPPORTED_LAYOUT/));
test("reject comma-spoofed tensor shape/stride fields",()=>{
 const forged=t();
 delete forged.shape;
 delete forged.strides;
 forged["shape,strides"]=[2,3];
 assert.throws(()=>validateTensor(forged),/unknown or missing tensor fields/);
});

test("reject accessor tensor shape even when getter returns valid data",()=>{
 const forged=t();
 Object.defineProperty(forged,"shape",{enumerable:true,get(){return [2,3];}});
 assert.throws(()=>validateTensor(forged),/accessor/);
});
test("reject inherited custom tensor prototype",()=>{
 const forged=Object.assign(Object.create({unsafe:true}),t());
 assert.throws(()=>validateTensor(forged),/plain JSON/);
});
test("reject hidden non-enumerable tensor extras",()=>{
 const forged=t();
 Object.defineProperty(forged,"secret",{value:3});
 assert.throws(()=>validateTensor(forged),/unknown or missing/);
});
test("reject sparse shape and strided vectors",()=>{
 const shape=[2,3]; delete shape[0];
 assert.throws(()=>validateTensor({...t(),shape}),/sparse/);
 const strides=[3,1]; delete strides[1];
 assert.throws(()=>validateTensor({...t(),strides}),/sparse/);
});
test("reject accessor in tensor stride vector",()=>{
 const strides=[3,1];
 Object.defineProperty(strides,"0",{enumerable:true,get(){return 3;}});
 assert.throws(()=>validateTensor({...t(),strides}),/accessor/);
});
test("default wire path rejects borrowed and actor-turn values",()=>{
 assert.throws(()=>validateTensor({...t(),ownership:"borrowed_local"}),/borrowed alias/);
 assert.throws(()=>validateTensor({...t(),lifetime:"actor_turn"}),/actor turn lease/);
});
test("local-only explicit mode admits turn lifetime",()=>{
 assert.equal(validateTensor({...t(),lifetime:"actor_turn"},{acrossActor:false}).requiredBytes,24);
});
test("reject backend evidence getter",()=>{
 const p={};
 Object.defineProperty(p,"ml_actor",{get(){return "verified";}});
 assert.throws(()=>requireVerifiedBackend(p,"ml_actor"),/UNSUPPORTED_BACKEND/);
});
test("reject backend feature prototype selector",()=>{
 assert.throws(()=>requireVerifiedBackend({constructor:"verified"},"constructor"),/UNSUPPORTED_BACKEND/);
});
