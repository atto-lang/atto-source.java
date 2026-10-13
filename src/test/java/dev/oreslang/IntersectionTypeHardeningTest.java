package dev.oreslang;

import dev.oreslang.ast.Ast;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed, compile-time semantics for the bounded first phase of A & B. */
final class IntersectionTypeHardeningTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    private static void rejected(String source, String expected) {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(problem.getMessage().contains(expected), problem.getMessage());
    }

    @Test
    void typeIntersectionBindsMoreTightlyThanUnion() {
        Ast.Program program = Parser.parse("""
                type X = int | {a: int} & {b: string};
                """);
        Ast.TypeAliasDecl alias = (Ast.TypeAliasDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(alias.target().isUnion());
        assertEquals(2, alias.target().arguments().size());
        assertTrue(alias.target().arguments().stream().anyMatch(Ast.TypeRef::isIntersection));

        Ast.Program grouped = Parser.parse("""
                type X = (int | string) & {a: int};
                """);
        Ast.TypeAliasDecl second = (Ast.TypeAliasDecl) grouped.modules().getFirst().declarations().getFirst();
        assertTrue(second.target().isIntersection());
        assertTrue(second.target().arguments().stream().anyMatch(Ast.TypeRef::isUnion));
    }

    @Test
    void canonicalizesDuplicatesAndKeepsBorrowSeparate() {
        Ast.TypeRef intType = Ast.TypeRef.simple("int");
        Ast.TypeRef stringType = Ast.TypeRef.simple("string");
        assertEquals(intType, Ast.TypeRef.intersection(List.of(intType, intType)));
        assertEquals(
                Ast.TypeRef.intersection(List.of(intType, stringType)),
                Ast.TypeRef.intersection(List.of(stringType, intType, intType)));
        Ast.Program borrowed = Parser.parse("type Ref = &int;");
        Ast.TypeAliasDecl alias = (Ast.TypeAliasDecl) borrowed.modules().getFirst().declarations().getFirst();
        assertTrue(alias.target().isBorrow());
        assertFalse(alias.target().isIntersection());
    }

    @Test
    void mergesDisjointStructuralFieldsAndAliases() {
        accepted("""
                type A = {left: int};
                type B = {right: string};
                type C = B & A;
                fnc read(C value): int { return value.left; }
                fnc label(C value): string { return value.right; }
                """);
        accepted("""
                type C = {left: int} & {left: int};
                fnc read(C value): int { return value.left; }
                """);
    }

    @Test
    void detectsWritableConflictsWithPreciseFieldDiagnostics() {
        rejected("""
                type C = {value: int} & {value: string};
                fnc use(C value): void { return; }
                """, "conflicting writable field 'value'");
        rejected("""
                type C = {value: int} & {value: float};
                fnc use(C value): void { return; }
                """, "conflicting writable field 'value'");
        rejected("""
                type C = {value: typeof fnc() => int} & {value: int};
                fnc use(C value): void { return; }
                """, "conflicting writable field 'value'");
    }

    @Test
    void rejectsUnsupportedNominalCompositionRatherThanFakingInheritance() {
        rejected("""
                define class Cat as
                end
                define class Dog as
                end
                type Both = Cat & Dog;
                fnc consume(Both value): void { return; }
                """, "intersection has no proven common value");
    }

    @Test
    void mutableRecordsAreInvariantAndReadonlyViewsCannotRegainWriteAccess() {
        Types.Record narrow = new Types.Record(Map.of("value", Types.Primitive.INT));
        Types.Record wide = new Types.Record(Map.of("value",
                Types.unionOf(Types.Primitive.INT, Types.Primitive.STRING)));
        Types.Record readonlyNarrow = new Types.Record(Map.of("value", Types.Primitive.INT), true);
        Types.Record readonlyWide = new Types.Record(Map.of("value",
                Types.unionOf(Types.Primitive.INT, Types.Primitive.STRING)), true);

        assertFalse(Types.isAssignable(narrow, wide), "writable field widening allows destructive writes");
        assertTrue(Types.isAssignable(narrow, readonlyWide));
        assertFalse(Types.isAssignable(readonlyNarrow, narrow));
        assertTrue(Types.isAssignable(readonlyNarrow, readonlyWide));
        assertThrows(IllegalArgumentException.class, () -> Types.intersectOf(narrow, wide));
        assertEquals(readonlyNarrow, Types.intersectOf(readonlyNarrow, readonlyWide));
        assertThrows(IllegalArgumentException.class, () -> Types.intersectOf(narrow, readonlyNarrow));
    }

    @Test
    void dynamicOrGenericIntersectionsMustNotSilentlyProveConstraints() {
        assertThrows(IllegalArgumentException.class,
                () -> Types.intersectOf(Types.Unknown.INSTANCE, Types.Primitive.INT));
        assertThrows(IllegalArgumentException.class,
                () -> Types.intersectOf(new Types.Generic("T"), Types.Primitive.INT));
        assertThrows(IllegalArgumentException.class,
                () -> Types.intersectOf(Types.Primitive.INT, Types.Primitive.FLOAT));
        assertEquals(Types.Primitive.INT, Types.intersectOf(
                Types.unionOf(Types.Primitive.INT, Types.Primitive.STRING),
                Types.Primitive.INT));
    }

    @Test
    void checkerDoesNotPermitMutableRecordWidening() {
        rejected("""
                type Narrow = {value: int};
                type Wide = {value: int | string};
                fnc needWide(Wide value): void { return; }
                fnc unsafe(Narrow value): void {
                    needWide(value);
                    return;
                }
                """, "argument");
    }

    @Test
    void runtimeChecksBothStructuralRequirementsOnReturnedAlias() throws Exception {
        String source = """
                type Header = {request_id: int};
                type Payload = {message: string};
                type Event = Header & Payload;

                fnc makeEvent(): Event {
                    return Map.of("request_id", 9, "message", "ready");
                }

                pub fnc main(): void {
                    const value = makeEvent();
                    stdio.println(value.request_id);
                    stdio.println(value.message);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "intersection.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("9\\nready\\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
