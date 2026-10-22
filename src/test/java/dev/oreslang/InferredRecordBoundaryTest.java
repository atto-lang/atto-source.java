package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

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
    void exactAliasProofPassesThroughEveryCompilerEntryPoint() {
        String source = """
                fnc read(): string {
                    const Person first = infer record{name: "Ada", age: 37};
                    const Person second = infer record{age: 42, name: "Grace"};
                    return second.name;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> OresCompiler.analyze(Parser.parse(source)));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
    }

    @Test
    void structurallyEqualBindingsInDistinctScopesHaveDistinctAliasProofs() {
        String source = """
                fnc first(): string {
                    const Profile profile = infer record{name: "Ada"};
                    return profile.name;
                }
                fnc second(): string {
                    const Profile profile = infer record{name: "Ada"};
                    return profile.name;
                }
                """;
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse(source));
        assertEquals(2, checked.shortInferredAliases().size(),
                "equal-value AST nodes must not collapse lexical alias identities");
        assertThrows(UnsupportedOperationException.class,
                () -> checked.shortInferredAliases().clear(),
                "typechecker proof map must be immutable");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void previouslyDeclaredTypeNeverAcquiresShortAliasAuthority() {
        String source = """
                type Person = {name: string};
                fnc read(): string {
                    const Person person = infer record{name: "Ada"};
                    return person.name;
                }
                """;
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse(source));
        assertTrue(checked.shortInferredAliases().isEmpty(),
                "preexisting type declarations are not short aliases");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void importedTypeNamesCannotBeHijackedByShortOrExplicitInference() {
        rejected("""
                import type Person from "./types";
                fnc read(): string {
                    const Person person = infer record{name: "Ada"};
                    return person.name;
                }
                """, null);
        rejected("""
                import type Person from "./types";
                fnc read(): string {
                    const Person person = infer<Person> record{name: "Ada"};
                    return person.name;
                }
                """, "conflicts with an existing type or namespace");
    }

    @Test
    void linkedImportedClassesStillPropagateShortAliasProofsToOwnership() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/options.ores", """
                pub define class ImportedOptions as
                  pub val int max_input_bytes;
                end
                """);
        sources.put("src/consumer.ores", """
                import class ImportedOptions from './options';
                pub fnc remaining(ImportedOptions options): int {
                    return options.max_input_bytes;
                }
                fnc read(): string {
                    const LocalRow first = infer record{name: "Ada"};
                    const LocalRow second = infer record{name: "Grace"};
                    return second.name;
                }
                """);
        assertEquals(2, assertDoesNotThrow(
                () -> new IncrementalCompiler().compile(sources)).units().size());
    }

    @Test
    void ownershipCannotRegainMutationAcrossShortAliasReuse() {
        String mutableField = """
                fnc bad(): void {
                    const Person first = infer record{age: 37};
                    const Person second = first;
                    second.age = 99;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(mutableField));

        String mutableReferent = """
                fnc bad(): void {
                    const Person first = infer record{age: 37};
                    val Person second = first;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(mutableReferent));
    }

    @Test
    void directAstConstructionRejectsDuplicateFieldsBeforeCodegen() {
        Ast.ObjectField left = new Ast.ObjectField("age", new Ast.LiteralExpr(37));
        Ast.ObjectField right = new Ast.ObjectField("age", new Ast.LiteralExpr(42));
        for (Ast.ObjectKind kind : Ast.ObjectKind.values()) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new Ast.ObjectExpr(java.util.List.of(left, right), kind, java.util.List.of()));
            assertTrue(error.getMessage().contains("duplicate struct field"), error.getMessage());
        }
        Ast.StructFieldSpec declared = new Ast.StructFieldSpec(
                "age", Ast.TypeRef.simple("int"));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ObjectExpr(
                java.util.List.of(left), Ast.ObjectKind.EXPLICIT_STRUCT,
                java.util.List.of(declared, declared)));

        // A single initializer legitimately provides a declared field's value.
        assertDoesNotThrow(() -> new Ast.ObjectExpr(
                java.util.List.of(left), Ast.ObjectKind.EXPLICIT_STRUCT,
                java.util.List.of(declared)));
    }

    @Test
    void allClosedFieldEntryPointsRejectMalformedUnicodeAndOversizeNames() {
        String[] invalid = {
            "embedded" + (char) 0 + "nul",
            "unpaired-high" + (char) 0xD800,
            "unpaired-low" + (char) 0xDC00,
            "x".repeat(1025)
        };
        for (String key : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> Ast.TypeRef.recordType(Map.of(key, Ast.TypeRef.simple("string"))),
                    "record type accepted invalid key");
            assertThrows(IllegalArgumentException.class,
                    () -> Ast.TypeRef.readonlyRecordType(Map.of(key, Ast.TypeRef.simple("string"))),
                    "readonly record type accepted invalid key");
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.ObjectField(key, new Ast.LiteralExpr("value")),
                    "object field accepted invalid key");
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.StructFieldSpec(key, Ast.TypeRef.simple("string")),
                    "struct field specification accepted invalid key");
        }
    }

    @Test
    void allClosedFieldEntryPointsPreserveValidQuotedKeys() {
        for (String key : new String[] { "", "  ", "first name", "emoji🚀", "x".repeat(1024) }) {
            Ast.TypeRef row = assertDoesNotThrow(
                    () -> Ast.TypeRef.recordType(Map.of(key, Ast.TypeRef.simple("string"))));
            assertEquals(Ast.TypeRef.simple("string"), row.recordMembers().get(key));
            assertEquals(key, assertDoesNotThrow(
                    () -> new Ast.ObjectField(key, new Ast.LiteralExpr("value"))).name());
            assertEquals(key, assertDoesNotThrow(
                    () -> new Ast.StructFieldSpec(key, Ast.TypeRef.simple("string"))).name());
        }
    }

    @Test
    void shortNamedRecordIntroducesStructuralAliasForUnknownType() {
        accepted("""
                fnc read(): string {
                    const Person user = infer record{"first name": "Ada", age: 37};
                    const Person second = infer record{age: 42, "first name": "Grace"};
                    return second["first name"];
                }
                """);
    }

    @Test
    void shortNamedStructIntroducesConcreteStructAlias() {
        accepted("""
                fnc read(): string {
                    const Profile profile = infer struct{name: "Ada", active: true};
                    const Profile another = infer struct{active: false, name: "Grace"};
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
        assertFalse(struct.readonlyInferredAggregate());
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
                    const User another = infer record{
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
        assertFalse(((Ast.ObjectExpr) named.initializer()).inferredStruct(),
                "record source must not be reclassified as a concrete struct");
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
    void structAndRecordShapesDoNotSilentlyInteroperate() {
        rejected("""
                fnc read(): string {
                    const T first = infer<T> record{label: "first", count: 1};
                    const T second = infer struct{count: 2, label: "second"};
                    return second.label;
                }
                """, "initializer for");
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
