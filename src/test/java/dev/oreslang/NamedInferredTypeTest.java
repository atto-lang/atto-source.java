package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
                  block {
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

        rejected("""
                fnc bad(): void {
                  const Iterator value = infer<Iterator> struct { id: "x" };
                  return;
                }
                """, "conflicts with an existing type");

        rejected("""
                fnc bad(): void {
                  const MutexGuard value = infer<MutexGuard> tuple (1, 2);
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

    @Test
    void laterBindingCannotBeUsedAsAForwardType() {
        rejected("""
                fnc bad(): void {
                  const T tooEarly = infer struct { id: "before" };
                  const T declared = infer<T> struct { id: "after" };
                  return;
                }
                """, "before initialization");
    }

    @Test
    void aliasCannotBeUsedAfterDefiningBlockEnds() {
        rejected("""
                fnc bad(): void {
                  block {
                    const T internal = infer<T> struct { id: "private" };
                  }
                  const T escaped = infer struct { id: "leak" };
                  return;
                }
                """, "out of scope");
    }

    @Test
    void aliasIsVisibleToNestedLexicalBlocks() {
        accepted("""
                fnc read(): string {
                  const T account = infer<T> struct { id: "acct_42" };
                  block {
                    const T moved = account;
                    return moved.id;
                  }
                }
                """);
    }

    @Test
    void inferredStructAndTupleRunWithoutRuntimeTypeObjects() {
        String source = """
                pub fnc main(): void {
                  const T account = infer<T> struct {
                    id: "acct_42",
                    active: true
                  };
                  const T moved = account;
                  stdio.println(moved.id);
                  const U pair = infer<U> tuple ("foo", 5);
                  stdio.println(pair[1]);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "named-inferred-types.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("acct_42"), text);
        assertTrue(text.contains("5"), text);
    }

    @Test
    void separateFunctionsCanInferTheSameLexicalName() {
        accepted("""
                fnc first(): string {
                  const T item = infer<T> struct { id: "alpha" };
                  return item.id;
                }
                fnc second(): int {
                  const T item = infer<T> tuple ("beta", 2);
                  return item[1];
                }
                """);
    }

    @Test
    void missingFieldAndWrongTypeDoNotSatisfyInferredAlias() {
        rejected("""
                fnc bad(): void {
                  const T first = infer<T> struct { id: "acct", active: true };
                  const T wrong = infer struct { id: "missing-active" };
                  return;
                }
                """, "initializer for");
        rejected("""
                fnc bad(): void {
                  const T first = infer<T> struct { id: "acct", active: true };
                  const T wrong = infer struct { id: "acct", active: 42 };
                  return;
                }
                """, "initializer for");
    }

    @Test
    void tupleArityAndSlotOrderMustMatch() {
        rejected("""
                fnc bad(): void {
                  const T pair = infer<T> tuple ("foo", 5);
                  const T wrong = tuple ("foo", 5, true);
                  return;
                }
                """, "initializer for");
        rejected("""
                fnc bad(): void {
                  const T pair = infer<T> tuple ("foo", 5);
                  const T wrong = tuple (5, "foo");
                  return;
                }
                """, "initializer for");
    }

    @Test
    void reusedInferredStringFieldsAndTupleSlotsWidenLiterals() {
        accepted("""
                fnc read(): string {
                  const T account = infer<T> struct { id: "original" };
                  const T other = infer struct { id: "different" };
                  return other.id;
                }
                """);
        accepted("""
                fnc read(): string {
                  const T pair = infer<T> tuple ("original", 5);
                  const T other = tuple ("different", 5);
                  return other[0];
                }
                """);
    }

    @Test
    void namedTupleKeepsPositionalFieldTypesIncludingNestedTuples() {
        accepted("""
                fnc read(): bool {
                  const T values = infer<T> tuple ("first", 9, true, "last");
                  return values[2];
                }
                """);
        accepted("""
                fnc read(): string {
                  const T values = infer<T> tuple ("first", 9, true, "last");
                  return values[3];
                }
                """);
        accepted("""
                fnc read(): int {
                  const T values = infer<T> tuple ("title", tuple (false, 44));
                  return values[1][1];
                }
                """);
    }

    @Test
    void duplicateStructFieldsCannotBeHiddenByInference() {
        rejected("""
                fnc bad(): void {
                  const T item = infer<T> struct { id: "first", id: "second" };
                  return;
                }
                """, "duplicate struct field");
    }

    @Test
    void sourceTypeAliasesCannotBeShadowedByInferredNames() {
        rejected("""
                type T = int;
                fnc bad(): void {
                  const T item = infer<T> struct { id: "value" };
                  return;
                }
                """, "conflicts with an existing type");
    }

    @Test
    void siblingBlocksCannotSeeEachOthersInferredType() {
        rejected("""
                fnc bad(): void {
                  block {
                    const T first = infer<T> struct { id: "inside" };
                  }
                  block {
                    const T second = infer struct { id: "outside" };
                  }
                  return;
                }
                """, "out of scope");
    }

    @Test
    void negativeAndExcessiveTupleIndexesAreRejected() {
        rejected("""
                fnc bad(): int {
                  const T pair = infer<T> tuple ("foo", 5);
                  return pair[-1];
                }
                """, "outside arity");
        rejected("""
                fnc bad(): int {
                  const T pair = infer<T> tuple ("foo", 5);
                  return pair[999];
                }
                """, "outside arity");
    }

    @Test
    void inferredTypeAliasesDoNotAcceptGenericArguments() {
        rejected("""
                fnc bad(): void {
                  const T item = infer<T> struct { id: "acct" };
                  const T<int> wrong = item;
                  return;
                }
                """, "does not accept generic arguments");
    }

    @Test
    void inferredReadonlyNestedStructCannotBeModifiedThroughAlias() {
        rejected("""
                fnc bad(): void {
                  const T item = infer<T> struct {
                    nested: infer struct { id: "first" }
                  };
                  const T copy = item;
                  copy.nested.id = "changed";
                  return;
                }
                """, "readonly");
    }

    @Test
    void inferredStructFieldPermutationsRetainFieldTypeAssociations() {
        String[] keys = { "id", "active", "count" };
        String[] values = { "\"acct_42\"", "true", "5" };
        for (int first = 0; first < keys.length; first++) {
            for (int second = 0; second < keys.length; second++) {
                if (first == second) continue;
                int last = 3 - first - second;
                String source = "fnc read(): int {"
                        + " const T row = infer<T> struct { "
                        + keys[first] + ": " + values[first] + ", "
                        + keys[second] + ": " + values[second] + ", "
                        + keys[last] + ": " + values[last] + " }; "
                        + "return row.count; }";
                assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)), source);
            }
        }
    }

    @Test
    void twoIndependentNamedInferredTypesExecuteInSameScope() {
        String source = """
                pub fnc main(): void {
                  const Account account = infer<Account> struct {
                    count: 7, id: "acct_42", active: true
                  };
                  const Pair pair = infer<Pair> tuple ("first", 17, "last");
                  const Account copied = account;
                  stdio.println(copied.id);
                  stdio.println(copied.count);
                  stdio.println(pair[0]);
                  stdio.println(pair[1]);
                  stdio.println(pair[2]);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, source, "inference-runtime.ores")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
        });
        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertArrayEquals(new String[] { "acct_42", "7", "first", "17", "last" }, lines);
    }
}
