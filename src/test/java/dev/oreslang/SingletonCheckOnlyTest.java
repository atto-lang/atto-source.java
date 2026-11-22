package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.SingletonCheckOnly;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SingletonCheckOnlyTest {
    private static final String IMMUTABLE = """
            define pub singleton Settings as
                const int answer = 42;
                const bool ready = true;
                const string label = "safe";
            end
            """;

    private static IllegalArgumentException reject(String source, String code) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.checkSingletonDeclarationsOnly(source));
        assertTrue(failure.getMessage().contains(code), failure.getMessage());
        return failure;
    }

    @Test
    void immutablePrimitiveSnapshotProducesOnlyCheckOnlyAst() {
        List<Ast.SingletonDecl> declarations =
                OresCompiler.checkSingletonDeclarationsOnly(IMMUTABLE);
        assertEquals(1, declarations.size());
        assertEquals("Settings", declarations.getFirst().name());
        assertEquals(Ast.Visibility.PUBLIC, declarations.getFirst().visibility());
        assertEquals(3, declarations.getFirst().fields().size());
        assertTrue(declarations.getFirst().line() > 0);
    }

    @Test
    void moduleNestedAndVisibilitySpellingsParseInCheckOnlyMode() {
        for (String declaration : List.of(
                "define singleton Settings as const int answer = 42; end",
                "define pub singleton Settings as const int answer = 42; end",
                "pub define singleton Settings as const int answer = 42; end")) {
            assertEquals(1, OresCompiler.checkSingletonDeclarationsOnly(declaration).size());
            assertEquals(1, OresCompiler.checkSingletonDeclarationsOnly(
                    "define module app as\n" + declaration + "\nend").size());
        }
    }

    @Test
    void productionCompilationStillFailsClosed() {
        for (String declaration : List.of(
                "define singleton Settings as end",
                "define pub singleton Settings as end",
                "pub define singleton Settings as end",
                IMMUTABLE)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(declaration));
            assertTrue(failure.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                    failure.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(declaration));
        }
    }

    @Test
    void manuallySuppliedAstCannotBypassRuntimeGate() {
        Ast.SingletonDecl forged = new Ast.SingletonDecl("Settings",
                Ast.Visibility.PRIVATE, List.of(), 1, 1);
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("app", List.of(forged))));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program));
        assertTrue(failure.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                failure.getMessage());

        IllegalArgumentException ownershipFailure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(ownershipFailure.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                ownershipFailure.getMessage());
    }

    @Test
    void singletonSelfEscapeHasPreciseDiagnosticAndLocation() {
        for (String initializer : List.of("self", "Settings.answer",
                "consume(Settings)", "1 + Settings.answer")) {
            IllegalArgumentException failure = reject(
                    "define singleton Settings as\n"
                            + " const int answer = " + initializer + ";\nend",
                    "ORES-SINGLETON-SELF-ESCAPE");
            assertTrue(failure.getMessage().contains("2:"), failure.getMessage());
        }
    }

    @Test
    void mutableOrUnprovedAliasesAreRejected() {
        for (String field : List.of(
                "let int answer = 42;",
                "val int answer = 42;",
                "const mut int answer = 42;",
                "const User answer = 42;",
                "const List<int> answer = 42;")) {
            reject("define singleton Settings as\n" + field + "\nend",
                    "ORES-SINGLETON-MUTABLE-ALIAS");
        }
    }

    @Test
    void dynamicInitializerIsNotMistakenForDeeplyImmutable() {
        reject("define singleton Settings as\n"
                        + " const int answer = get_answer();\nend",
                "ORES-SINGLETON-INIT-EFFECT");
        reject("define singleton Settings as\n"
                        + " const bool ready = 42;\nend",
                "ORES-SINGLETON-INIT-EFFECT");
    }

    @Test
    void duplicateIdentityAndFieldAreRejected() {
        reject("define singleton Settings as end\n"
                        + "define singleton Settings as end",
                "ORES-SINGLETON-DUPLICATE");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.checkSingletonDeclarationsOnly(
                        "define singleton Settings as\n"
                        + " const int answer = 42;\n const int answer = 43;\nend"));
        assertTrue(failure.getMessage().contains("duplicate singleton field"),
                failure.getMessage());
    }

    @Test
    void unsupportedMixedDeclarationAndImportCannotBypassChecker() {
        reject("define singleton Settings as end\nfnc main():int{return 42;}",
                "ORES-SINGLETON-CHECKONLY");
        reject("import module common from \"common.ores\";\n"
                        + "define singleton Settings as end",
                "ORES-SINGLETON-CHECKONLY");
    }


    @Test
    void manuallyConstructedAstCannotBypassFieldIdentityOrBudget() {
        Ast.SingletonDecl parsed =
                OresCompiler.checkSingletonDeclarationsOnly(IMMUTABLE).getFirst();
        Ast.SingletonField first = parsed.fields().getFirst();
        Ast.SingletonDecl duplicate = new Ast.SingletonDecl("Settings",
                Ast.Visibility.PRIVATE, List.of(first, first), 1, 1);
        Ast.Program repeated = new Ast.Program(List.of(
                new Ast.ModuleDecl("app", List.of(duplicate))));
        IllegalArgumentException duplicateError = assertThrows(
                IllegalArgumentException.class, () -> SingletonCheckOnly.check(repeated));
        assertTrue(duplicateError.getMessage().contains("ORES-SINGLETON-DUPLICATE"),
                duplicateError.getMessage());

        Ast.SingletonDecl oversized = new Ast.SingletonDecl("Settings",
                Ast.Visibility.PRIVATE, java.util.Collections.nCopies(65, first), 1, 1);
        Ast.Program tooMany = new Ast.Program(List.of(
                new Ast.ModuleDecl("app", List.of(oversized))));
        IllegalArgumentException limit = assertThrows(
                IllegalArgumentException.class, () -> SingletonCheckOnly.check(tooMany));
        assertTrue(limit.getMessage().contains("ORES-SINGLETON-FIELD-BUDGET"),
                limit.getMessage());
    }

    @Test
    void forgedNonfiniteDoubleCannotBePublishedAsImmutableConstant() {
        Ast.SingletonDecl parsed = OresCompiler.checkSingletonDeclarationsOnly(
                "define singleton Settings as const double pi = 3.14; end").getFirst();
        Ast.SingletonField position = parsed.fields().getFirst();
        Ast.FieldDecl previous = position.declaration();
        for (double nonfinite : new double[]{Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN}) {
            Ast.FieldDecl unsafe = new Ast.FieldDecl(previous.name(), previous.visibility(),
                    previous.bindingKind(), previous.type(), previous.annotations(),
                    new Ast.LiteralExpr(nonfinite), previous.mutableReferent());
            Ast.SingletonDecl forged = new Ast.SingletonDecl(parsed.name(),
                    parsed.visibility(), List.of(new Ast.SingletonField(
                            unsafe, position.line(), position.column())),
                    parsed.line(), parsed.column());
            Ast.Program program = new Ast.Program(List.of(
                    new Ast.ModuleDecl("app", List.of(forged))));
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class, () -> SingletonCheckOnly.check(program));
            assertTrue(denied.getMessage().contains("ORES-SINGLETON-INIT-EFFECT"),
                    denied.getMessage());
        }
    }

    @Test
    void contextualIdentifierAndCommentsRemainLegalInNormalParser() {
        assertDoesNotThrow(() -> Parser.parse("""
                // define singleton Dummy as end
                fnc singleton(): int { return 42; }
                fnc main(): int { return singleton(); }
                """));
    }
}
