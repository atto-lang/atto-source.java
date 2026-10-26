package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitExportSyntaxTest {

    private static Ast.ModuleDecl root(Ast.Program program) {
        return program.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void topOfFileExportDoesNotCloseImportPrelude() {
        Ast.Program program = Parser.parse("""
                export class X;
                import type T from './dep';

                define class X as
                end
                """);

        assertEquals(1, program.imports().size());
        Ast.ExportDecl export = root(program).exports().getFirst();
        assertEquals(Ast.ExportScope.PUBLIC, export.scope());
        assertEquals(Ast.ExportKind.CLASS, export.kind());
        assertEquals("X", export.items().getFirst().localName());
    }

    @Test
    void parsesGroupedAliasesAndOneTrailingComma() {
        Ast.Program program = Parser.parse("""
                export class {X, Y as PublicY,};

                define class X as
                end

                define class Y as
                end
                """);

        Ast.ExportDecl export = root(program).exports().getFirst();
        assertEquals(2, export.items().size());
        assertEquals(new Ast.ExportItem("X", null, false, false), export.items().get(0));
        assertEquals(new Ast.ExportItem("Y", "PublicY", false, false), export.items().get(1));
    }

    @Test
    void preservesSymbolSelfIdentityWithoutTreatingItAsBindingRename() {
        Ast.Program program = Parser.parse("""
                export symbol #ready as #ready;

                define class Placeholder as
                end
                """);

        Ast.ExportDecl export = root(program).exports().getFirst();
        assertEquals(Ast.ExportKind.SYMBOL, export.kind());
        assertEquals(
                new Ast.ExportItem("ready", "ready", true, true),
                export.items().getFirst());
    }

    @Test
    void parsesFileAndPackageScopesButDoesNotClaimSemanticSupport() {
        Ast.Program program = Parser.parse("""
                export[file] type Local;
                export(package) class Box;

                define type Local = int;
                define class Box as
                end
                """);

        List<Ast.ExportDecl> exports = root(program).exports();
        assertEquals(Ast.ExportScope.FILE, exports.get(0).scope());
        assertEquals(Ast.ExportScope.PACKAGE, exports.get(1).scope());
    }

    @Test
    void malformedAndReservedExportFormsFailClosed() {
        List<String> rejected = List.of(
                "export class X\ndefine class X as end",
                "export class {}; define class X as end",
                "export *; define class X as end",
                "export from './dep' {X}; define class X as end",
                "export dep.X; define class X as end",
                "export Foo<int>; define class Foo as end",
                "export foo(int); fnc foo(int x): int { return x; }",
                "export {class X}; define class X as end",
                "export string ready as 'ready'; define class X as end",
                "export const ready as 'ready'; const string ready = 'ready';",
                "export symbol ready as #ready; define class X as end",
                "export symbol #ready as #other; define class X as end",
                "export symbol #ready as Ready; define class X as end"
        );

        for (String source : rejected) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
