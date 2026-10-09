package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Admission tests for the lexical type alias introduced by infer<T>. */
final class NamedInferredTypeTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejected(String source, String messageFragment) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(messageFragment), failure.getMessage());
    }

    @Test
    void namedInferredStructRemainsClosedReadonlyAndReusableAsAType() {
        String source = """
                fnc read(): string {
                  const T account = infer<T> struct {
                    id: "acct_42",
                    active: true
                  };
                  const T moved = account;
                  return moved.id;
                }
                """;
        Ast.Program parsed = Parser.parse(source);
        Ast.FunctionDecl fn = parsed.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        Ast.BindingStmt inferred = (Ast.BindingStmt) fn.body().getFirst();
        assertEquals("T", inferred.inferredTypeName());
        assertInstanceOf(Ast.ObjectExpr.class, inferred.initializer());
        accepted(source);
    }

    @Test
    void namedTupleKeepsPositionalTypesAndReusesAlias() {
        accepted("""
                fnc read(): int {
                  const T account = infer<T> tuple ("foo", 5);
                  const T moved = account;
                  return moved[1];
                }
                """);
    }

    @Test
    void directConstantTupleIndexHasTheExactSlotType() {
        accepted("""
                fnc read(): int {
                  const account = tuple ("foo", 5);
                  return account[1];
                }
                """);
    }

    @Test
    void tupleOutOfBoundsLiteralIndexIsRejected() {
        rejected("""
                fnc read(): int {
                  const T account = infer<T> tuple ("foo", 5);
                  return account[2];
                }
                """, "outside arity");
    }

    @Test
    void inferredStructCannotGainWriteAccessThroughAlias() {
        rejected("""
                fnc bad(): void {
                  const T account = infer<T> struct { id: "acct_42" };
                  const T moved = account;
                  moved.id = "changed";
                  return;
                }
                """, "readonly");
    }

    @Test
    void mismatchedTypeNamesAndMutableDeclarationsFailClosed() {
        rejected("""
                fnc bad(): void {
                  const U value = infer<T> struct { id: "x" };
                  return;
                }
                """, "binding type must match infer<T>");

        rejected("""
                fnc bad(): void {
                  val T value = infer<T> struct { id: "x" };
                  return;
                }
                """, "immutable const");

        rejected("""
                fnc bad(): void {
                  const mut T value = infer<T> struct { id: "x" };
                  return;
                }
                """, "immutable const");
    }

    @Test
    void duplicateAndShadowedTypeNamesAreRejected() {
        rejected("""
                fnc bad(): void {
                  const T first = infer<T> struct { id: "x" };
                  const T second = infer<T> tuple (1, 2);
                  return;
                }
                """, "duplicate or shadowed inferred type");

        rejected("""
                fnc bad(): void {
                  const T first = infer<T> struct { id: "x" };
                  {
                    const T second = infer<T> struct { id: "y" };
                  }
                  return;
                }
                """, "duplicate or shadowed inferred type");
    }

    @Test
    void builtinAndGenericParameterNamesCannotBeCaptured() {
        rejected("""
                fnc bad(): void {
                  const int value = infer<int> struct { id: "x" };
                  return;
                }
                """, "conflicts with an existing type");

        rejected("""
                fnc bad<T>(T input): void {
                  const T value = infer<T> struct { id: "x" };
                  return;
                }
                """, "conflicts with an existing type");
    }

    @Test
    void declarationNeverTurnsTypeNameIntoRuntimeValue() {
        rejected("""
                fnc bad(): void {
                  const T account = infer<T> struct { id: "x" };
                  const copy = T;
                  return;
                }
                """, "unknown name 'T'");
    }

    @Test
    void unsupportedInferenceTargetsAreRejectedRatherThanDynamic() {
        rejected("""
                fnc bad(): void {
                  const T account = infer<T> foo();
                  return;
                }
                """, "requires a struct");

        rejected("""
                fnc bad(): void {
                  const T account = infer<T> tuple ();
                  return;
                }
                """, "requires at least one element");
    }
}
