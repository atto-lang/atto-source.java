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
