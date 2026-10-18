// Source-only prerequisites. Exit code 78 must NOT be counted as pass/skip.
// A source-preflight PASS still requires actual exact-SHA JDK25/GraalVM JUnit.
import {readFileSync} from "node:fs";
import {inspectCompilerSources} from "./adt-ml-compiler-admission.mjs";
const read=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const result=inspectCompilerSources(read);
if(!result.ready){
  console.error("ML_ADT_INTEGRATION=BLOCKED; GUEST_NOT_EXECUTED");
  for(const prerequisite of result.missing)console.error(" - "+prerequisite);
  process.exitCode=78;
}else {
  console.log("ML_ADT_SOURCE_PREREQUISITES=PASS; GUEST_NOT_EXECUTED; NOT_CERTIFIED");
}
