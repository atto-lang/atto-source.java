// Compiler preflight; exit 78 means NOT INTEGRATED, not a green skip.
import {readFileSync} from "node:fs";
const file=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const missing=[
  ["AST nominal enum", "src/main/java/dev/oreslang/ast/Ast.java",/EnumDecl/],
  ["parser enum declaration", "src/main/java/dev/oreslang/parser/Parser.java",/parseTaggedEnum\(\)/],
  ["parser enum dispatch", "src/main/java/dev/oreslang/parser/Parser.java",/match\(ENUM\)/],
  ["type checker enum rules", "src/main/java/dev/oreslang/types/TypeChecker.java",/Ast\.EnumDecl/],
  ["actor effect syntax", "src/main/java/dev/oreslang/parser/Parser.java",/state\.write/],
  ["actor ownership", "src/main/java/dev/oreslang/types/OwnershipChecker.java",/ActorStateContract/]
].filter(([,p,re])=>!re.test(file(p))).map(x=>x[0]);
if(missing.length){console.error("ML_ADT_INTEGRATION=BLOCKED: "+missing.join(", "));process.exitCode=78}
else console.log("ML_ADT_COMPILER_SOURCE_CHECK=PASS (HOST_ONLY; GUEST_NOT_EXECUTED)");
