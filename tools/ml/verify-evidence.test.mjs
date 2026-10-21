import test from "node:test";
import assert from "node:assert/strict";
import {verifyMlEvidence} from "./verify-evidence.mjs";
const receipt=()=>({
 schema_version:1,workload:"cpu_numerical",backend:"llvm-native",mode:"compiled_ores",
 source_sha:"a".repeat(40),compiler_sha:"b".repeat(40),artifact_sha256:"c".repeat(64),
 fixture_sha256:"d".repeat(64),reference_sha256:"e".repeat(64),budget_sha256:"f".repeat(64),
 source_mirror_is_public:false,
 hardware:{cpu:"Xeon",arch:"x86_64",ram_bytes:32000000000,gpu:"none",driver:"none",numa:"0",os:"linux"},
 execution:{ci_url:"https://github.com/example/private/actions/runs/1234",exit_code:0,compiled_source:true,evidence_kind:"source_execution"},
 correctness:{max_abs_error:0,abs_tolerance:0,max_rel_error:0,rel_tolerance:0,sample_count:100},
 measurements:{throughput_per_sec:10,p50_ms:1,p95_ms:2,p99_ms:3,copied_bytes:0,allocations:1,peak_rss_bytes:100,recovery_ms:0}
});
test("accept structurally well-formed source evidence",()=>assert.equal(verifyMlEvidence(receipt()).kind,"structurally_admissible_source_evidence"));
test("reject mislabeled host microbenchmark",()=>assert.throws(()=>verifyMlEvidence({...receipt(),mode:"host_runtime"}),/HOST_ONLY/));
test("reject public private-source mirror",()=>assert.throws(()=>verifyMlEvidence({...receipt(),source_mirror_is_public:true}),/public source mirror/));
test("reject unexecuted source",()=>assert.throws(()=>verifyMlEvidence({...receipt(),execution:{...receipt().execution,compiled_source:false}}),/no executed/));
test("reject code-error execution",()=>assert.throws(()=>verifyMlEvidence({...receipt(),execution:{...receipt().execution,exit_code:1}}),/no executed/));
test("reject missing digest",()=>assert.throws(()=>verifyMlEvidence({...receipt(),source_sha:"unverified"}),/immutable lowercase digest/));
test("reject numerical drift",()=>assert.throws(()=>verifyMlEvidence({...receipt(),correctness:{...receipt().correctness,max_abs_error:1}}),/tolerance exceeded/));
test("reject nonfinite metric",()=>assert.throws(()=>verifyMlEvidence({...receipt(),measurements:{...receipt().measurements,p99_ms:NaN}}),/nonnegative finite/));
test("reject reversed latency quantiles",()=>assert.throws(()=>verifyMlEvidence({...receipt(),measurements:{...receipt().measurements,p99_ms:0}}),/unordered/));
test("reject unknown receipt fields",()=>assert.throws(()=>verifyMlEvidence({...receipt(),claims_speedup:true}),/exactly/));
test("reject absent sample comparisons",()=>assert.throws(()=>verifyMlEvidence({...receipt(),correctness:{...receipt().correctness,sample_count:0}}),/no numerical/));
test("reject comma-joined spoofed metric keys",()=>{
 const measurements={...receipt().measurements};
 delete measurements.p50_ms;
 delete measurements.p95_ms;
 measurements["p50_ms,p95_ms"]=2;
 assert.throws(()=>verifyMlEvidence({...receipt(),measurements}),/exactly/);
});
test("reject fractional byte counters",()=>assert.throws(()=>verifyMlEvidence({...receipt(),measurements:{...receipt().measurements,copied_bytes:1.5}}),/safe integer/));
test("reject imprecise large counters",()=>assert.throws(()=>verifyMlEvidence({...receipt(),measurements:{...receipt().measurements,allocations:Number.MAX_SAFE_INTEGER+1}}),/safe integer/));
test("reject fractional host RAM",()=>assert.throws(()=>verifyMlEvidence({...receipt(),hardware:{...receipt().hardware,ram_bytes:1.5}}),/positive safe integer/));
test("reject unbounded hardware descriptor",()=>assert.throws(()=>verifyMlEvidence({...receipt(),hardware:{...receipt().hardware,cpu:"x".repeat(257)}}),/too long/));
test("reject impossible zero measured throughput",()=>assert.throws(()=>verifyMlEvidence({...receipt(),measurements:{...receipt().measurements,throughput_per_sec:0}}),/zero measured throughput/));

test("reject receipt getters that can swap evidence after validation",()=>{
 const r=receipt();
 Object.defineProperty(r,"source_sha",{enumerable:true,get(){return "a".repeat(40);}});
 assert.throws(()=>verifyMlEvidence(r),/accessor/);
});
test("reject nested metric getters",()=>{
 const r=receipt();
 Object.defineProperty(r.measurements,"p99_ms",{enumerable:true,get(){return 3;}});
 assert.throws(()=>verifyMlEvidence(r),/accessor/);
});
test("reject nonplain and inherited receipt objects",()=>{
 const r=Object.assign(Object.create({trusted:true}),receipt());
 assert.throws(()=>verifyMlEvidence(r),/plain JSON/);
});
test("reject nonenumerable and symbolic authority metadata",()=>{
 const r=receipt();
 Object.defineProperty(r,"secret",{value:true});
 assert.throws(()=>verifyMlEvidence(r),/exactly/);
 const s=receipt();s[Symbol("hidden")]=true;
 assert.throws(()=>verifyMlEvidence(s),/exactly/);
});
test("reject negative zero latency counters",()=>{
 const r=receipt();r.measurements.p50_ms=-0;
 assert.throws(()=>verifyMlEvidence(r),/nonnegative finite/);
});
test("reject multiline hardware evidence spoofing",()=>{
 const r=receipt();r.hardware.cpu="Xeon\nClaim: Verified";
 assert.throws(()=>verifyMlEvidence(r),/invalid characters/);
});
test("reject noncanonical traversal in claimed CI URL",()=>{
 const r=receipt();r.execution.ci_url="https://github.com/org/../actions/runs/123";
 assert.throws(()=>verifyMlEvidence(r),/canonical CI run proof/);
});
