// Host-only source regression for the first shared ADT + actor parser splice.
// It does NOT compile Java or execute an Oreslang guest.
import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const read=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const ast=read("src/main/java/dev/oreslang/ast/Ast.java");
const lexer=read("src/main/java/dev/oreslang/parser/Lexer.java");
const token=read("src/main/java/dev/oreslang/parser/Token.java");
const parser=read("src/main/java/dev/oreslang/parser/Parser.java");
test("closed nominal enum and pattern AST nodes are represented and permitted",()=>{
  assert.match(ast,/sealed interface Decl permits[^\n]*EnumDecl, PatternDecl/);
  assert.match(ast,/record EnumCase\(String name, TypeRef payload, int tag\)/);
  assert.match(ast,/record EnumDecl\(String name, List<EnumCase> cases\) implements Decl/);
  assert.match(ast,/record PatternDecl\(String name, List<String> alternatives\) implements Decl/);
});
test("ADT syntax tokens are accepted alongside critical state loan keywords",()=>{
  assert.match(token,/DEFINE, CLASS, ENUM, PATTERN, MODULE/);
  for(const keyword of ["CRITICAL","USING","WITH","SERIAL"])
    assert(token.includes(keyword),keyword+" missing");
  assert.match(lexer,/KEYWORDS\.put\("enum", ENUM\)/);
  assert.match(lexer,/KEYWORDS\.put\("pattern", PATTERN\)/);
  for(const keyword of ["critical","using","with","serial"])
    assert(lexer.includes('KEYWORDS.put("'+keyword+'"'),keyword+" missing");
});
test("nominal parser preserves strict case tags, exhaustiveness syntax and actor state",()=>{
  assert.match(parser,/if \(match\(ENUM\)\)/);
  assert.match(parser,/parseTaggedEnum\(\)/);
  assert.match(parser,/private Ast\.EnumDecl parseTaggedEnum\(\)/);
  assert.match(parser,/private Ast\.PatternDecl parseNamedPattern\(\)/);
  assert.match(parser,/duplicate enum case/);
  assert.match(parser,/enum wire tags must increase monotonically/);
  assert.match(parser,/cannot implicitly increment enum tag after INT32_MAX/);
  assert.match(parser,/malformed integer separator/);
  assert.match(parser,/state\.write/);
  assert.match(parser,/using state/);
  assert.match(parser,/critical/);
});
