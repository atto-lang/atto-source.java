package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.List;
import java.util.Map;

/**
 * JDK-only parser and AST admission smoke. No Maven, JUnit or GraalVM needed.
 * This is a subset of full compiler conformance, never a substitute for it.
 */
public final class RecordParserSmoke {
    private RecordParserSmoke() { }

    public static void main(String[] args) {
        runAll();
        System.out.println("RECORD_PARSER_SMOKE_PASS");
    }

    public static void runAll() {
        Ast.FunctionDecl function = firstFunction("""
                fnc get(): string {
                    const Person ada = record{"first name": "Ada", age: 37};
                    const Person grace = infer struct{age: 42, "first name": "Grace"};
                    const Contact contact = infer<Contact> record{"emoji🚀": "ok"};
                    return grace["first name"];
                }
                """);

        Ast.BindingStmt ada = (Ast.BindingStmt) function.body().get(0);
        Ast.BindingStmt grace = (Ast.BindingStmt) function.body().get(1);
        Ast.BindingStmt contact = (Ast.BindingStmt) function.body().get(2);
        check(ada.inferredTypeName() == null, "short form must not fabricate explicit infer<T>");
        check(grace.inferredTypeName() == null, "short struct has no explicit inferred alias");
        check("Contact".equals(contact.inferredTypeName()), "explicit infer<Contact> name");
        check(((Ast.ObjectExpr) ada.initializer()).kind() == Ast.ObjectKind.RECORD,
                "canonical record AST identity");
        check(((Ast.ObjectExpr) grace.initializer()).kind() == Ast.ObjectKind.INFERRED_STRUCT,
                "short struct AST identity");
        check(((Ast.ObjectExpr) ada.initializer()).plainRecord(),
                "canonical record must not carry infer alias intent");
        Ast.ObjectExpr c = (Ast.ObjectExpr) contact.initializer();
        check(c.inferredRecord() && c.readonlyInferredAggregate(), "named record readonly source kind");
        check(((Ast.ObjectExpr) grace.initializer()).inferredStruct(), "struct origin must remain distinct");
        check(!((Ast.ObjectExpr) grace.initializer()).readonlyInferredAggregate(),
                "inferred struct must not inherit record readonly capability");
        check(c.fields().getFirst().name().equals("emoji🚀"), "Unicode quoted key");

        Ast.ObjectField first = new Ast.ObjectField("age", new Ast.LiteralExpr(1));
        Ast.ObjectField second = new Ast.ObjectField("age", new Ast.LiteralExpr(2));
        for (Ast.ObjectKind kind : Ast.ObjectKind.values()) {
            denied(() -> new Ast.ObjectExpr(List.of(first, second), kind, List.of()),
                    "duplicate object fields " + kind);
        }
        Ast.StructFieldSpec declared = new Ast.StructFieldSpec("age", Ast.TypeRef.simple("int"));
        denied(() -> new Ast.ObjectExpr(List.of(first), Ast.ObjectKind.EXPLICIT_STRUCT,
                List.of(declared, declared)), "duplicate declared fields");
        new Ast.ObjectExpr(List.of(first), Ast.ObjectKind.EXPLICIT_STRUCT, List.of(declared));

        for (String valid : List.of("", " ", "emoji🚀", "x".repeat(1024))) {
            new Ast.ObjectField(valid, new Ast.LiteralExpr("ok"));
            new Ast.StructFieldSpec(valid, Ast.TypeRef.simple("string"));
            check(Ast.TypeRef.readonlyRecordType(
                    Map.of(valid, Ast.TypeRef.simple("string")))
                    .recordMembers().containsKey(valid), "valid row key");
            check(Ast.TypeRef.structType(
                    Map.of(valid, Ast.TypeRef.simple("string")))
                    .structMembers().containsKey(valid), "valid struct key");
        }
        for (String invalid : List.of("bad" + (char) 0, "bad" + (char) 0xD800,
                "bad" + (char) 0xDC00, "x".repeat(1025))) {
            denied(() -> new Ast.ObjectField(invalid, new Ast.LiteralExpr("bad")), "field key");
            denied(() -> new Ast.StructFieldSpec(invalid, Ast.TypeRef.simple("string")),
                    "declared field key");
            denied(() -> Ast.TypeRef.recordType(
                    Map.of(invalid, Ast.TypeRef.simple("string"))), "typed record key");
            denied(() -> Ast.TypeRef.structType(
                    Map.of(invalid, Ast.TypeRef.simple("string"))), "typed struct key");
        }

        denied(() -> Parser.parse("""
                fnc bad(): void {
                    const T value = infer<T> record{age: 1, age: 2};
                    return;
                }
                """), "duplicate parsed record fields");

        // Stage-one spread syntax is represented explicitly in the AST, and
        // type/ownership/execution must still reject it until lowering lands.
        Ast.FunctionDecl spreadFunction = firstFunction("""
                fnc spread(): int {
                    const base = infer struct{x: 1};
                    const next = record{...base, y: 2, ...base};
                    return next.x;
                }
                """);
        Ast.BindingStmt next = (Ast.BindingStmt) spreadFunction.body().get(1);
        Ast.ObjectExpr parsedSpread = (Ast.ObjectExpr) next.initializer();
        check(parsedSpread.kind() == Ast.ObjectKind.RECORD, "record kind survives spread");
        check(parsedSpread.fields().size() == 3, "spread and named items retain order");
        check(parsedSpread.fields().get(0).spread()
                && parsedSpread.fields().get(0).name() == null,
                "first spread has no fake field name");
        check(!parsedSpread.fields().get(1).spread()
                && parsedSpread.fields().get(1).name().equals("y"),
                "named initializer stays statically named");
        check(parsedSpread.fields().get(2).spread(), "repeated spread is AST-representable");
        denied(() -> new Ast.ObjectField(null, new Ast.LiteralExpr(1)),
                "named initializer with null name");
        denied(() -> new Ast.ObjectField("fake", new Ast.LiteralExpr(1), true),
                "spread with injected name");
        denied(() -> Parser.parse("""
                fnc bad(): void {
                    const row = record{x: 1, ...base, x: 2};
                    return;
                }
                """), "explicit duplicate named field around spread");
        denied(() -> Parser.parse("""
                fnc bad(): void {
                    const row = infer struct{...};
                    return;
                }
                """), "missing spread operand");

        // This smoke deliberately does not assert that user code type-checks.
        // Type checking, ownership and interpreter execution are separate gates.
    }

    private static Ast.FunctionDecl firstFunction(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void denied(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("accepted invalid " + label);
    }
}
