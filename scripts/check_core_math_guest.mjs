// Validate *captured* real Java/Graal guest stdout against the reviewed
// independent signed-i64 corpus. This script does not authenticate a CI run.
import {readFileSync,writeFileSync} from "node:fs";
import {createHash} from "node:crypto";
import {pathToFileURL} from "node:url";

export const EXPECTED_GIT_BLOB = "58d2b64a137ede4700593f855e1f0504ff744f90";
export const EXPECTED_GUEST_GIT_BLOB = "2d12b8632ad77c8dee34235ecfacfca6a0fa553f";
const I64_MIN=-(1n<<63n),I64_MAX=(1n<<63n)-1n;
const DECIMAL=/^(?:0|-?[1-9][0-9]*)$/;
const IDS=/^[a-z][a-z0-9-]{1,80}$/;
const ARITIES={min_int:2,max_int:2,clamp_int:3};

function requireCondition(condition,message) {
  if(!condition) throw Error("unverified guest core math: "+message);
}
function parseInt64(value) {
  requireCondition(typeof value==="string"&&DECIMAL.test(value),"invalid i64 literal");
  const x=BigInt(value);
  requireCondition(x>=I64_MIN&&x<=I64_MAX,"i64 out of bounds");
  return x;
}
function oracle(name,a) {
  if(name==="min_int") return a[0]<a[1]?a[0]:a[1];
  if(name==="max_int") return a[0]>a[1]?a[0]:a[1];
  const [v,low,high]=a;
  return low>high?low:v<low?low:v>high?high:v;
}
export function validateGuestSource(sourceBytes) {
  requireCondition(Buffer.isBuffer(sourceBytes),"guest source must be raw bytes");
  const sourceBlob=createHash("sha1")
    .update(Buffer.from("blob "+sourceBytes.length+"\0"))
    .update(sourceBytes).digest("hex");
  requireCondition(sourceBlob===EXPECTED_GUEST_GIT_BLOB,
    "guest source does not match reviewed Git blob");
  return sourceBlob;
}
export function loadContract(raw) {
  requireCondition(Buffer.isBuffer(raw),"expected raw contract bytes");
  const hash=createHash("sha1").update(Buffer.from("blob "+raw.length+"\0")).update(raw).digest("hex");
  requireCondition(hash===EXPECTED_GIT_BLOB,"source contract blob drift");
  const manifest=JSON.parse(raw.toString("utf8"));
  requireCondition(manifest.schemaVersion===1 &&
    manifest.contract==="ores-core-math-signed-i64-v1" &&
    manifest.integerDomain==="signed-i64" &&
    Array.isArray(manifest.cases)&&manifest.cases.length===24,
    "unexpected signed-i64 core contract");
  requireCondition(Object.keys(manifest).sort().join(",")===[
    "cases","contract","integerDomain","schemaVersion","sourceOfTruth","statement"
  ].sort().join(","),"unexpected or missing contract fields");
  const cases=new Map();
  for(const c of manifest.cases){
    requireCondition(c&&typeof c==="object"&&!Array.isArray(c),
      "invalid contract case");
    requireCondition(Object.keys(c).sort().join(",")==
      ["id","function","args","expected"].sort().join(","),"unknown case fields");
    requireCondition(typeof c.id==="string"&&IDS.test(c.id)&&!cases.has(c.id),
      "duplicate or malformed case id");
    requireCondition(Object.hasOwn(ARITIES,c.function) &&
      Array.isArray(c.args)&&c.args.length===ARITIES[c.function],
      "unknown function or wrong arity");
    const values=c.args.map(parseInt64), expected=parseInt64(c.expected);
    requireCondition(oracle(c.function,values)===expected,
      "independent mathematical oracle mismatch "+c.id);
    cases.set(c.id,c.expected);
  }
  return cases;
}
export function validateCapturedGuestLog(log,cases) {
  requireCondition(typeof log==="string","missing captured guest stdout");
  const seen=new Map();
  let finishCount=0;
  for(const line of log.replace(/\r\n/g,"\n").split("\n")) {
    if(line==="CORE-MATH-DONE") {finishCount++;continue;}
    if(!line.startsWith("CORE-MATH-CASE"))continue;
    const match=/^CORE-MATH-CASE\|([a-z][a-z0-9-]{1,80})\|((?:0|-?[1-9][0-9]*))$/.exec(line);
    requireCondition(match!==null,"malformed guest case line");
    const [,id,value]=match;
    requireCondition(cases.has(id)&&!seen.has(id),"unexpected or repeated guest result");
    const actual=parseInt64(value);
    requireCondition(actual===parseInt64(cases.get(id)),
      "compiled guest differs from independent oracle: "+id);
    seen.set(id,value);
  }
  requireCondition(finishCount===1,"missing or duplicate guest completion marker");
  requireCondition(seen.size===cases.size,
    "missing guest cases "+(cases.size-seen.size));
  return {contractGitBlob:EXPECTED_GIT_BLOB,executedCases:seen.size,
    results:[...seen].map(([id,value])=>({id,observed:value})),
    evidenceKind:"captured-guest-stdout-not-authenticated-run"};
}
function main(args) {
  requireCondition(args.length===4,
    "usage: node check_core_math_guest.mjs <raw-log> <contract-json> <guest-source.ores> <receipt-json>");
  const [logPath,contractPath,guestPath,outPath]=args;
  const sourceBlob=validateGuestSource(readFileSync(guestPath));
  const cases=loadContract(readFileSync(contractPath));
  const receipt={
    ...validateCapturedGuestLog(readFileSync(logPath,"utf8"),cases),
    guestSourceGitBlob:sourceBlob
  };
  writeFileSync(outPath,JSON.stringify(receipt,null,2)+"\n");
  process.stdout.write("JAVA-GRAAL-CORE-MATH-OK "+receipt.executedCases+"\n");
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href){
  try{main(process.argv.slice(2));}
  catch(e){console.error(e.message);process.exitCode=1;}
}
