package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

/** Parses the exact naked shape contract using the production Java lexer/parser. */
public final class ReadonlyShapeProductionParserMain {
    public static void main(String[] args) {
        String text = """
                fnc read_x(p: {x: int}): int { return p.x; }
                define class A as pub val int x = 42; end
                define class B as
                    pub val int padding = 10;
                    pub val int x = 42;
                end
                pub fnc main(): int {
                    return read_x(new A()) + read_x(new B());
                }
                """;
        Ast.Program program = Parser.parse(text);
        Ast.FunctionDecl function = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl f
                        && f.name().equals("read_x"))
                .map(decl -> (Ast.FunctionDecl) decl)
                .findFirst().orElseThrow(() ->
                        new AssertionError("read_x source callable was not parsed"));
        if (function.parameters().size() != 1)
            throw new AssertionError("missing structural parameter");
        Ast.Param p = function.parameters().getFirst();
        if (!p.name().equals("p") || !p.type().isReadonlyRecordType()
                || !p.type().recordMembers().containsKey("x")
                || !p.type().recordMembers().get("x").name().equals("int")) {
            throw new AssertionError("anonymous {x:int} lost structural field provenance: "
                    + p.type());
        }
        System.out.println("JAVA_REAL_PARSER_ANONYMOUS_ROW_PASS; GUEST NOT_EXECUTED");
    }
}
