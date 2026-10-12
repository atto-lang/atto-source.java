// Proof-of-source benchmark receipt validator, not a benchmark runner.
// Pure validation: never fetch or execute untrusted artifacts.
const sha40=/^[0-9a-f]{40}$/, sha256=/^[0-9a-f]{64}$/;
const workloads=new Set(["inference_pipeline","cpu_numerical","training_feeder","mpa_fleet"]);
const fields=["schema_version","workload","backend","mode","source_sha","compiler_sha","artifact_sha256","fixture_sha256","reference_sha256","budget_sha256","source_mirror_is_public","hardware","measurements","correctness","execution"];
const finiteNonnegative=(n,k)=>{if(typeof n!=="number"||!Number.isFinite(n)||n<0)throw new TypeError(k+" must be nonnegative finite");};
function closed(value,keys,name){
 if(!value||typeof value!=="object"||Array.isArray(value))throw new TypeError(name+" must be object");
 const actual=Object.keys(value).sort().join(","),expected=[...keys].sort().join(",");
 if(actual!==expected)throw new TypeError(name+" must have exactly: "+keys.join(","));
}
export function verifyMlEvidence(receipt){
 closed(receipt,fields,"receipt");
 if(receipt.schema_version!==1)throw new Error("unsupported evidence schema version");
 if(!workloads.has(receipt.workload))throw new Error("unknown workload");
 if(!["java-jit","java-aot-guest","java-hybrid","llvm-native"].includes(receipt.backend))throw new Error("unknown backend");
 if(receipt.mode!=="compiled_ores")throw new Error("HOST_ONLY_NOT_SOURCE_PROOF");
 if(receipt.source_mirror_is_public!==false)throw new Error("unsafe public source mirror or unknown visibility");
 for(const [name,pattern] of [["source_sha",sha40],["compiler_sha",sha40],["artifact_sha256",sha256],["fixture_sha256",sha256],["reference_sha256",sha256],["budget_sha256",sha256]]){
  if(typeof receipt[name]!=="string"||!pattern.test(receipt[name]))throw new Error(name+" must be exact immutable lowercase digest");
 }
 closed(receipt.hardware,["cpu","arch","ram_bytes","gpu","driver","numa","os"],"hardware");
 for(const k of ["cpu","arch","gpu","driver","numa","os"])if(typeof receipt.hardware[k]!=="string"||receipt.hardware[k].length===0)throw new TypeError("hardware."+k);
 finiteNonnegative(receipt.hardware.ram_bytes,"ram_bytes");
 closed(receipt.execution,["ci_url","exit_code","compiled_source","evidence_kind"],"execution");
 if(typeof receipt.execution.ci_url!=="string"||!/^https:\/\/github\.com\/[^/]+\/[^/]+\/actions\/runs\/[1-9][0-9]*$/.test(receipt.execution.ci_url))throw new Error("missing canonical CI run proof");
 if(receipt.execution.exit_code!==0||receipt.execution.compiled_source!==true||receipt.execution.evidence_kind!=="source_execution")throw new Error("no executed compiled-source proof");
 closed(receipt.correctness,["max_abs_error","abs_tolerance","max_rel_error","rel_tolerance","sample_count"],"correctness");
 for(const k of ["max_abs_error","abs_tolerance","max_rel_error","rel_tolerance"])finiteNonnegative(receipt.correctness[k],k);
 if(!Number.isSafeInteger(receipt.correctness.sample_count)||receipt.correctness.sample_count<1)throw new Error("no numerical comparison samples");
 if(receipt.correctness.max_abs_error>receipt.correctness.abs_tolerance||receipt.correctness.max_rel_error>receipt.correctness.rel_tolerance)throw new Error("numerical tolerance exceeded");
 closed(receipt.measurements,["throughput_per_sec","p50_ms","p95_ms","p99_ms","copied_bytes","allocations","peak_rss_bytes","recovery_ms"],"measurements");
 for(const k of Object.keys(receipt.measurements))finiteNonnegative(receipt.measurements[k],k);
 const m=receipt.measurements;
 if(m.p50_ms>m.p95_ms||m.p95_ms>m.p99_ms)throw new Error("latency quantiles unordered");
 return Object.freeze({kind:"structurally_admissible_source_evidence",workload:receipt.workload,backend:receipt.backend});
}
// Do not label the result a passing SLO, independent/reviewed execution, or parity.
// CI URL, artifact identity and source authenticity must be independently verified.
