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
    void shortNamedRecordIntroducesStructuralAliasForUnknownType() {
        accepted("""
                fnc read(): string {
                    const Person user = infer record{"first name": "Ada", age: 37};
                    const Person second = infer struct{age: 42, "first name": "Grace"};
                    return second["first name"];
                }
                """);
    }

    @Test
    void shortNamedStructAlsoIntroducesReadonlyAlias() {
        accepted("""
                fnc read(): string {
                    const Profile profile = infer struct{name: "Ada", active: true};
                    const Profile another = infer record{active: false, name: "Grace"};
                    return another.name;
                }
                """);
    }

    @Test
    void preexistingTypeAliasIsNotSilentlyRedeclared() {
        accepted("""
                type Person = {name: string, age: int};
                fnc read(): string {
                    const Person user = infer record{age: 37, name: "Ada"};
                    return user.name;
                }
                """);
    }

    @Test
    void shortAliasCannotBeRepeatedOrCapturedByLaterExplicitAlias() {
        accepted("""
                fnc good(): string {
                    const Person p = infer record{name: "Ada"};
                    const Person q = infer record{name: "Grace"};
                    return q.name;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const T before = infer record{name: "first"};
                    const T later = infer<T> record{name: "second"};
                    return;
                }
                """, "before initialization");
    }

    @Test
    void shortAliasDoesNotEscapeItsLexicalBlock() {
        rejected("""
                fnc bad(): void {
                    block {
                        const Inner value = infer record{name: "Ada"};
                    }
                    const Inner outside = tuple("Grace", 3);
                    return;
                }
                """, null);
    }

    @Test
    void genericAndBuiltinTypesAreNeverShortAliases() {
        rejected("""
                fnc bad<T>(T source): void {
                    const T value = infer record{name: "Ada"};
                    return;
                }
                """, "initializer for");
        rejected("""
                fnc bad(): void {
                    const String value = infer record{name: "Ada"};
                    return;
                }
                """, "initializer for");
    }

    @Test
    void inferredStructAndRecordKeepDifferentAstSourceKinds() {
        String source = """
                fnc read(): string {
                    const RecordValue a = infer record{name: "Ada"};
                    const StructValue b = infer struct{name: "Grace"};
                    return a.name;
                }
                """;
        Ast.Program tree = Parser.parse(source);
        Ast.FunctionDecl function = tree.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast).findFirst().orElseThrow();
        Ast.ObjectExpr record = (Ast.ObjectExpr) ((Ast.BindingStmt) function.body().get(0)).initializer();
        Ast.ObjectExpr struct = (Ast.ObjectExpr) ((Ast.BindingStmt) function.body().get(1)).initializer();
        assertEquals(Ast.ObjectKind.INFERRED_RECORD, record.kind());
        assertEquals(Ast.ObjectKind.INFERRED_STRUCT, struct.kind());
        assertTrue(record.readonlyInferredAggregate());
        assertTrue(struct.readonlyInferredAggregate());
        assertFalse(struct.inferredRecord());
        accepted(source);
    }

    @Test
    void emptyAndWhitespaceStaticFieldNamesAreLegal() {
        accepted("""
                fnc get(): string {
                    const T record = infer<T> record{
                        "": "empty",
                        "   ": "spaces",
                        "emoji🚀": "rocket"
                    };
                    return record[""];
                }
                """);
        accepted("""
                fnc get({ "": string, " ": int } value): string {
                    return value[""];
                }
                """);
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
        assertTrue(((Ast.ObjectExpr) named.initializer()).inferredRecord(),
                "record source identity must survive parsing");
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
