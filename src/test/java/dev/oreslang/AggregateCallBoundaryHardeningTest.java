package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for aggregate values crossing return, destructure and call boundaries. */
final class AggregateCallBoundaryHardeningTest {
    private static void accepts(String source) {
        var typed = TypeChecker.check(Parser.parse(source));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void tupleReturnedByFunctionCanBeSpreadIntoFixedArityCall() {
        accepts("""
                fnc pair(): Tuple[int, int] { return ~tuple(19, 23); }
                fnc add(int left, int right): int { return left + right; }
                fnc use(): int { return add(...pair()); }
                """);
    }

    @Test
    void tupleResultCanBeDestructuredAndForwarded() {
        accepts("""
                fnc pair(): [int, string] { return [7, "ok"]; }
                fnc join(int number, string label): string { return label; }
                fnc use(): string {
                  const [number, label] = pair();
                  return join(number, label);
                }
                """);
    }

    @Test
    void recordResultCanBeDestructuredAndForwardedByExplicitFields() {
        accepts("""
                fnc pair(): {left: int, right: int} {
                  return record{left: 19, right: 23};
                }
                fnc add(int left, int right): int { return left + right; }
                fnc use(): int {
                  const {left, right} = pair();
                  return add(left, right);
                }
                """);
    }

    @Test
    void tupleReturnSpreadStillEnforcesArity() {
        rejects("""
                fnc pair(): Tuple[int, int] { return ~tuple(19, 23); }
                fnc unary(int only): int { return only; }
                fnc bad(): int { return unary(...pair()); }
                """);
    }

    @Test
    void tupleReturnSpreadStillEnforcesTypes() {
        rejects("""
                fnc pair(): Tuple[int, string] { return ~tuple(19, "wrong"); }
                fnc add(int left, int right): int { return left + right; }
                fnc bad(): int { return add(...pair()); }
                """);
    }

    @Test
    void destructuringReturnedRecordCannotInventAField() {
        rejects("""
                fnc pair(): {left: int, right: int} {
                  return record{left: 19, right: 23};
                }
                fnc bad(): int {
                  const {left, missing} = pair();
                  return left;
                }
                """);
    }

    @Test
    void runtimeLengthListCannotMasqueradeAsTupleAtCallBoundary() {
        rejects("""
                fnc numbers(): List<int> { return [19, 23]; }
                fnc add(int left, int right): int { return left + right; }
                fnc bad(): int { return add(...numbers()); }
                """);
    }

    @Test
    void returnedTupleSpreadExecutesUsingDeclarationOrder() throws Exception {
        assertEquals("42", run("""
                fnc pair(): Tuple[int, int] { return ~tuple(19, 23); }
                fnc add(int left, int right): int { return left + right; }
                pub routine main(): void {
                  stdio.stdout.write(add(...pair()));
                  return;
                }
                """));
    }

    @Test
    void returnedRecordDestructureExecutesAndForwardsFields() throws Exception {
        assertEquals("42", run("""
                fnc pair(): {left: int, right: int} {
                  return record{left: 19, right: 23};
                }
                fnc add(int left, int right): int { return left + right; }
                pub routine main(): void {
                  const {left, right} = pair();
                  stdio.stdout.write(add(left, right));
                  return;
                }
                """));
    }

    @Test
    void multipleReturnedTupleSpreadsEvaluateOnceInLexicalOrder() throws Exception {
        assertEquals("AB1234", run("""
                fnc first(): Tuple[int, int] {
                  stdio.stdout.write("A");
                  return ~tuple(1, 2);
                }
                fnc second(): Tuple[int, int] {
                  stdio.stdout.write("B");
                  return ~tuple(3, 4);
                }
                fnc digits(int a, int b, int c, int d): int {
                  return a * 1000 + b * 100 + c * 10 + d;
                }
                pub routine main(): void {
                  stdio.stdout.write(digits(...first(), ...second()));
                  return;
                }
                """));
    }

    private static String run(String source) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        org.graalvm.polyglot.Source compiled = org.graalvm.polyglot.Source
                .newBuilder(OresLanguage.ID, source, "aggregate-call-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (org.graalvm.polyglot.Context context = org.graalvm.polyglot.Context
                .newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(compiled);
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void structuralRecordIsAcceptedByStructuralRecordParameter() {
        accepts("""
                fnc read({left: int, right: int} item): int {
                  return item.left + item.right;
                }
                fnc use(): int {
                  return read(record{left: 19, right: 23});
                }
                """);
    }

    @Test
    void inferredStructDoesNotSilentlyBecomeReadonlyStructuralRecord() {
        rejects("""
                fnc read({left: int, right: int} item): int {
                  return item.left + item.right;
                }
                fnc bad(): int {
                  const item = infer struct{left: 19, right: 23};
                  return read(item);
                }
                """);
    }

    @Test
    void returnedRecordCannotMasqueradeAsFixedPositionalTupleSpread() {
        rejects("""
                fnc pair(): {left: int, right: int} {
                  return record{left: 19, right: 23};
                }
                fnc add(int left, int right): int { return left + right; }
                fnc bad(): int { return add(...pair()); }
                """);
    }

}
