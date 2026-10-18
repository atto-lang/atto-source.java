// Pure, source-only prerequisite detector. NOT compiler or guest certification.
// Fail closed when a nominal error stub makes superficial AST/parser checks green.
export function inspectCompilerSources(read) {
  const checks=[
    ["nominal AST declaration", "src/main/java/dev/oreslang/ast/Ast.java", /record EnumDecl\(String name/],
    ["enum parser dispatch", "src/main/java/dev/oreslang/parser/Parser.java", /match\(ENUM\)/],
    ["enum constructor parser", "src/main/java/dev/oreslang/parser/Parser.java", /private Ast\.EnumDecl parseTaggedEnum\(\)/],
    ["actor critical/using syntax", "src/main/java/dev/oreslang/parser/Parser.java", /state\.write[\s\S]*using state|using state[\s\S]*state\.write/],
    ["nominal registry admission", "src/main/java/dev/oreslang/types/TypeChecker.java", /TaggedMessageContracts\.forTypes\(program\)/],
    ["nominal case type-resolution", "src/main/java/dev/oreslang/types/TypeChecker.java", /taggedMessages\.requireCase\(/],
    ["actor state loan validation", "src/main/java/dev/oreslang/types/TypeChecker.java", /ActorStateContract\.verify\(program\)/],
    ["actor effect validation", "src/main/java/dev/oreslang/types/TypeChecker.java", /DeclaredEffectVerifier\.verify\(program\)/],
    ["actor ownership admission", "src/main/java/dev/oreslang/types/OwnershipChecker.java", /ActorStateContract/],
    ["nominal ownership admission", "src/main/java/dev/oreslang/types/OwnershipChecker.java", /TaggedMessageContracts/],
    ["nominal runtime lowering", "src/main/java/dev/oreslang/nodes/OresEvalRootNode.java", /TaggedMessageContracts/],
    ["actor runtime state admission", "src/main/java/dev/oreslang/nodes/OresEvalRootNode.java", /ActorStateContract/]
  ];
  const missing=[];
  for(const [name,path,re] of checks){
    let content;
    try{content=read(path)}catch(_){missing.push(name+" (unreadable)");continue}
    if(!re.test(content))missing.push(name);
  }
  try{
    const tc=read("src/main/java/dev/oreslang/types/TypeChecker.java");
    if(/nominal enum\/pattern declarations are not yet executable/.test(tc))
      missing.push("temporary fail-closed nominal execution gate still active");
  }catch(_){/* Already marked as missing. */}
  return Object.freeze({sourceOnly:true,ready:missing.length===0,missing:Object.freeze(missing)});
}
