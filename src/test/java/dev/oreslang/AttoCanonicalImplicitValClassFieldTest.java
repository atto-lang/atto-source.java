package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical AttoLang v2 class slots, without silently migrating legacy .ores. */
final class AttoCanonicalImplicitValClassFieldTest {
    private static Ast.ClassDecl firstClass(Ast.Program program) {
        return assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test
    void canonicalBareTypeFirstFieldsAreFixedIndependentOfReferentMutability() {
        Ast.ClassDecl klass = firstClass(Parser.parseStrictAtto("""
                define class Counter as
                  int id: 7
                  string label = "seven"
                  mut Array<int> values: [1, 2]
                  let int writable = 0;
                end
                """));
        assertEquals(4, klass.fields().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(Ast.BindingKind.VAL, klass.fields().get(i).bindingKind());
        }
        assertFalse(klass.fields().get(0).mutableReferent());
        assertFalse(klass.fields().get(1).mutableReferent());
        assertTrue(klass.fields().get(2).mutableReferent(),
                "mut requests referent mutation, not rebinding");
        assertEquals(Ast.BindingKind.LET, klass.fields().get(3).bindingKind());
    }

    @Test
    void canonicalImplicitValRejectsMethodRebindingButExplicitLetAllowsIt() {
        String source = """
                define class Counter as
                  %s
                  change(mut self)(): void {
                    self.count = 9;
                  }
                end
                """;
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(
                        source.formatted("int count: 1")));
        assertTrue(rejection.getMessage().contains("E_IMMUTABLE"), rejection::getMessage);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                source.formatted("let int count = 1;")));
    }

    @Test
    void versionedAttoAndStrictAttoAgreeOnImplicitVal() {
        String source = """
                define class Counter as
                  int count: 1
                end
                """;
        for (int version : new int[] {1, 2}) {
            assertEquals(Ast.BindingKind.VAL,
                    firstClass(Parser.parseVersionedAtto(source, version))
                            .fields().getFirst().bindingKind());
        }
        assertEquals(Ast.BindingKind.VAL,
                firstClass(Parser.parseStrictAtto(source))
                        .fields().getFirst().bindingKind());
    }

    @Test
    void historicalUnversionedParserStillPreservesLegacyLet() {
        String source = """
                define class Counter as
                  int count: 1
                  final int stable: 42
                end
                """;
        Ast.ClassDecl legacy = firstClass(Parser.parse(source));
        assertEquals(Ast.BindingKind.LET, legacy.fields().get(0).bindingKind());
        assertEquals(Ast.BindingKind.VAL, legacy.fields().get(1).bindingKind());
        assertEquals(Ast.BindingKind.VAL,
                firstClass(Parser.parseStrictAtto(source))
                        .fields().get(0).bindingKind());
    }

    @Test
    void canonicalPackageScopedVisibilityCannotWidenToPublic() {
        for (String declaration : new String[] {
                "pub[pkg] int secret = 42;",
                "pub [pkg] int secret = 42;"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(
                            "define class C as\n  " + declaration + "\nend\n"));
            assertTrue(error.getMessage().contains("E_PKG_VISIBILITY_UNSUPPORTED"),
                    error::getMessage);
        }
        assertDoesNotThrow(() -> Parser.parseStrictAtto(
                "define class C as\n  pub int published = 42;\nend\n"));
    }

    @Test
    void constantClassFieldsRejectRuntimeCallsAndOverflowBeforeEmission() {
        for (String initializer : new String[] {
                "runtime_number()",
                "9223372036854775807 + 1"
        }) {
            String program = """
                    fnc runtime_number(): int { return 42; }
                    define class C as
                      const int value = %s;
                    end
                    """.formatted(initializer);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(program));
            assertTrue(error.getMessage().contains("E_CONST"), error::getMessage);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                define class C as
                  const int answer = 40 + 2;
                end
                """));
    }

    @Test
    void nameFirstFieldsRemainInTheirHistoricalFixedSlotProfile() {
        String source = """
                define class Counter as
                  label: string = "unchanged"
                end
                """;
        assertEquals(Ast.BindingKind.VAL,
                firstClass(Parser.parseStrictAtto(source))
                        .fields().getFirst().bindingKind());
        assertEquals(Ast.BindingKind.VAL,
                firstClass(Parser.parse(source))
                        .fields().getFirst().bindingKind());
    }
}
