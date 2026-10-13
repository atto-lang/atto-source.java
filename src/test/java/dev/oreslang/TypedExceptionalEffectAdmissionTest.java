package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypedExceptionalEffectAdmissionTest {
    @Test
    void exceptionalEffectNamesHaveDedicatedTokens() {
        List<Token.Type> tokens = new Lexer("throw raise panic recover").scan().stream()
                .map(Token::type).toList();
        assertEquals(List.of(Token.Type.THROW, Token.Type.RAISE,
                Token.Type.PANIC, Token.Type.RECOVER, Token.Type.EOF), tokens);
    }

    @Test
    void threeEffectsParseAsDistinctStatementsButNeverAsOrdinaryCalls() {
        for (String effect : List.of("throw", "raise", "panic")) {
            Ast.Program unit = Parser.parse("fnc candidate(): void { " + effect + " new Error(); }");
            Ast.FunctionDecl fn = (Ast.FunctionDecl) unit.modules().getFirst()
                    .declarations().getFirst();
            assertEquals(1, fn.body().size());
            Ast.ExceptionalEffectStmt exceptional = assertInstanceOf(
                    Ast.ExceptionalEffectStmt.class, fn.body().getFirst());
            assertEquals(effect.toUpperCase(java.util.Locale.ROOT), exceptional.kind().name());
            assertInstanceOf(Ast.NewExpr.class, exceptional.error());
        }
    }

    @Test
    void rejectsPrimitivesStringsAndStructurallySimilarErrorLookalikes() {
        for (String effect : List.of("throw", "raise", "panic")) {
            for (String operand : List.of("1", "\"bad\"", "true")) {
                Ast.Program unit = Parser.parse("fnc bad(): void { "
                        + effect + " " + operand + "; }");
                IllegalArgumentException rejected = assertThrows(
                        IllegalArgumentException.class, () -> TypeChecker.checkTypes(unit));
                assertTrue(rejected.getMessage().contains(
                        "requires a nominal std/error.Error value or subclass"), rejected::getMessage);
            }
            Ast.Program duck = Parser.parse("""
                    import class Error from "std/error";
                    define interface ErrorLike as
                      fnc code() => int;
                    end
                    define class Pretender implements ErrorLike as
                      pub code(): int { return 5; }
                    end
                    fnc bad(): void { %s new Pretender(); }
                    """.formatted(effect));
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> checkWithCoreErrorClass(duck));
            assertTrue(failure.getMessage().contains(
                    "requires a nominal std/error.Error value or subclass"), failure::getMessage);
        }
    }

    @Test
    void forgedLocalErrorClassCannotReplaceImportedStdErrorIdentity() {
        Ast.Program fake = Parser.parse("""
                define class Error as
                  pub code(): int { return 0; }
                end
                fnc bad(): void { panic new Error(); }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.checkTypes(fake));
        assertTrue(failure.getMessage().contains(
                "requires a nominal std/error.Error value or subclass"), failure::getMessage);
    }

    @Test
    void exactCoreRootAndNominalSubclassesPassOperandGateButEffectsStayDisabled() {
        for (String effect : List.of("throw", "raise", "panic")) {
            for (String operand : List.of("new Error()", "new UserError()", "error")) {
                Ast.Program unit = Parser.parse("""
                        import class Error from "std/error";
                        define class UserError extends Error as
                          pub code(): int { return 98765; }
                        end
                        fnc check(Error error): void {
                          %s %s;
                        }
                        """.formatted(effect, operand));
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class, () -> checkWithCoreErrorClass(unit));
                assertTrue(failure.getMessage().contains(
                        "has a valid std/error.Error operand but source effect is not yet enabled"),
                        failure::getMessage);
            }
        }
    }

    @Test
    void borrowedErrorIsNotAnOwnedEffectPayload() {
        Ast.Program unit = Parser.parse("""
                import class Error from "std/error";
                fnc reject(Error error): void {
                  panic &error;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checkWithCoreErrorClass(unit));
        assertTrue(failure.getMessage().contains(
                "requires a nominal std/error.Error value or subclass"), failure::getMessage);
    }

    @Test
    void recoverStillFailsAtParserAndMalformedEffectsCannotSlipThrough() {
        IllegalArgumentException recover = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { recover 1; }"));
        assertTrue(recover.getMessage().contains("not yet enabled"), recover::getMessage);
        for (String effect : List.of("throw", "raise", "panic")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc f(): void { " + effect + "; }"));
        }
    }

    @Test
    void rawAstCannotBypassOwnershipOrCapabilityEffectAdmission() {
        for (String keyword : List.of("throw", "raise", "panic")) {
            Ast.Program unit = Parser.parse("fnc bad(): void { " + keyword + " 1; }");
            IllegalArgumentException ownership = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(unit));
            assertTrue(ownership.getMessage().contains("not yet enabled"),
                    ownership::getMessage);
            SecurityException capability = assertThrows(
                    SecurityException.class,
                    () -> dev.oreslang.runtime.CapabilityChecker.check(
                            unit, dev.oreslang.runtime.IsolatePolicy.developer()));
            assertTrue(capability.getMessage().contains("supervisor lowering"),
                    capability::getMessage);
        }
    }

    @Test
    void reservedKeywordsCannotMasqueradeAsOrdinaryIdentifiers() {
        for (String effect : List.of("throw", "raise", "panic", "recover")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc " + effect + "(): int { return 1; }"),
                    effect);
        }
    }

    @Test
    void unrelatedFunctionNamesRemainValid() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc throwable(): int { return 42; }
                fnc recovery(): int { return throwable(); }
                """));
    }

    /** Simulates the compiler's trusted std/error import signature, not a user-defined Error. */
    private static void checkWithCoreErrorClass(Ast.Program program) {
        Ast.Program stub = Parser.parse("""
                pub define class Error as
                  pub code(): int { return 0; }
                end
                """);
        Ast.ClassDecl imported = (Ast.ClassDecl) stub.modules().getFirst()
                .declarations().getFirst();
        TypeChecker.checkTypesWithImportedClasses(
                program, Map.of("Error", imported), Map.of());
    }
}
