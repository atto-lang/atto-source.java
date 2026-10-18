package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler admission and negative gates for closed, readonly inferred records. */
final class InferredRecordBoundaryTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejected(String source, String fragment) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        if (fragment != null) {
            assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
        }
    }

    @Test
    void namedRecordCarriesLexicalStructuralAliasAndQuotedKeys() {
        String source = """
                fnc label(): string {
                    const User user = infer<User> record{
                        "first name": "Ada",
                        "last-name": "Lovelace",
                        "email@work": "ada@example.com",
                        age: 37
                    };
                    const User another = infer struct{
                        age: 42,
                        "email@work": "grace@example.com",
                        "last-name": "Hopper",
                        "first name": "Grace"
                    };
                    return another["first name"];
                }
                """;
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl fn = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast).findFirst().orElseThrow();
        Ast.BindingStmt named = (Ast.BindingStmt) fn.body().getFirst();
        assertEquals("User", named.inferredTypeName());
        assertTrue(((Ast.ObjectExpr) named.initializer()).inferredStruct());
        accepted(source);
    }

    @Test
    void anonymousRecordIsStructuralAndFieldOrderIndependent() {
        accepted("""
                fnc consume({"first name": string, age: int} value): string {
                    return value["first name"];
                }
                fnc read(): string {
                    const item = infer record{age: 37, "first name": "Ada"};
                    return consume(item);
                }
                """);
    }

    @Test
    void structAndRecordReadonlyShapesInteroperate() {
        accepted("""
                fnc read(): string {
                    const T first = infer<T> record{label: "first", count: 1};
                    const T second = infer struct{count: 2, label: "second"};
                    return second.label;
                }
                """);
    }

    @Test
    void inferredRecordCannotGainWritesThroughAReadonlyAlias() {
        rejected("""
                fnc bad(): void {
                    const T x = infer<T> record{score: 1};
                    x.score = 4;
                    return;
                }
                """, null);
    }

    @Test
    void wrongFieldTypesAndMissingFieldsReject() {
        rejected("""
                fnc bad(): void {
                    const T x = infer<T> record{score: 1, name: "Ada"};
                    const T y = infer record{name: "Ada", score: false};
                    return;
                }
                """, "initializer for");
        rejected("""
                fnc bad(): void {
                    const T x = infer<T> record{score: 1, name: "Ada"};
                    const T y = infer record{name: "Ada"};
                    return;
                }
                """, "initializer for");
    }

    @Test
    void duplicateAndComputedFieldNamesReject() {
        rejected("""
                fnc bad(): void {
                    const T x = infer<T> record{age: 37, age: 42};
                    return;
                }
                """, "duplicate struct field");
        rejected("""
                fnc bad(): void {
                    const name = "age";
                    const x = infer record{[name]: 37};
                    return;
                }
                """, null);
    }

    @Test
    void mismatchedNamesAndMutableBindingReject() {
        rejected("""
                fnc bad(): void {
                    const Other x = infer<User> record{name: "Ada"};
                    return;
                }
                """, "binding type must match");
        rejected("""
                fnc bad(): void {
                    val T x = infer<T> record{name: "Ada"};
                    return;
                }
                """, "immutable const");
    }

    @Test
    void builtinAndLexicallyDuplicateTypeNamesReject() {
        rejected("""
                fnc bad(): void {
                    const string x = infer<string> record{name: "Ada"};
                    return;
                }
                """, "conflicts with an existing type");
        rejected("""
                fnc bad(): void {
                    const T a = infer<T> record{name: "Ada"};
                    const T b = infer<T> record{name: "Grace"};
                    return;
                }
                """, "duplicate or shadowed inferred type");
    }
}
