package dev.oreslang;

import dev.oreslang.ast.Ast;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void writableFieldInvarianceDoesNotTrustDynamicOrUnboundGenerics() {
        Types.Record typed = new Types.Record(Map.of("value", Types.Primitive.INT));
        Types.Record dynamic = new Types.Record(Map.of("value", Types.Unknown.INSTANCE));
        Types.Record generic = new Types.Record(Map.of("value", new Types.Generic("T")));
        assertFalse(Types.isAssignable(dynamic, typed));
        assertFalse(Types.isAssignable(typed, dynamic));
        assertFalse(Types.isAssignable(generic, typed));
        assertFalse(Types.isAssignable(typed, generic));
        assertTrue(Types.isAssignable(dynamic, new Types.Record(
                Map.of("value", Types.Unknown.INSTANCE))));
        // Readonly covariance is separate from mutable assignment.
        assertTrue(Types.isAssignable(typed,
                new Types.Record(Map.of("value", Types.Unknown.INSTANCE), true)));
    }

    @Test
    void ownershipRejectsInlineIntersectionWithoutTypeAlias() {
        for (String source : List.of(
                """
                fnc consume({loan: &int} & {id: int} payload): void { return; }
                """,
                """
                fnc consumeReverse({id: int} & {loan: &int} payload): void { return; }
                """)) {
            // Independently prove the ownership guard, rather than asserting that
            // type checking must choose the same first diagnostic as ownership.
            IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(Parser.parse(source)));
            assertTrue(problem.getMessage().contains("ownership-aware field projection")
                    || problem.getMessage().contains("borrowed or move-only"),
                    problem.getMessage());
            // End-to-end compilation must still reject the unsafe source.
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
        }
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
                    return struct{request_id: int, message: string}{request_id: 9, message: "ready"};
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
        assertEquals("9\nready\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void ownershipFailsClosedOnBorrowedMoveOnlyAndGenericIntersectionFields() {
        for (String source : List.of(
                """
                type C = {loan: &int} & {id: int};
                fnc use(C value): void { return; }
                """,
                """
                define class Resource as
                end
                type C = {owned: Resource} & {id: int};
                fnc use(C value): void { return; }
                """)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(failure.getMessage().contains("ownership-aware field projection")
                    || failure.getMessage().contains("borrowed or move-only"), failure.getMessage());
        }
        IllegalArgumentException generic = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        type Box<T> = {value: T};
                        type C = Box<int> & {id: int};
                        fnc use(C value): void { return; }
                        """)));
        assertTrue(generic.getMessage().contains("ownership-aware generic field projection"), generic.getMessage());
    }

    @Test
    void methodProvenanceIsNotForgeableWithStructuralFieldNames() {
        String slot = "instance$apply$arity1$generics0";
        Types.Function implementation = new Types.Function(
                List.of(Types.Primitive.INT), new Types.StringLiteral("narrow"));
        Types.Function contract = new Types.Function(
                List.of(Types.Primitive.INT), Types.Primitive.STRING);
        Types.Record realImplementation = new Types.Record(Map.of(slot, implementation),
                false, Set.of(slot));
        Types.Record realContract = new Types.Record(Map.of(slot, contract),
                false, Set.of(slot));
        Types.Record fakeImplementation = new Types.Record(Map.of(slot, implementation));
        Types.Record fakeContract = new Types.Record(Map.of(slot, contract));

        assertTrue(Types.isAssignable(realImplementation, realContract),
                "verified methods retain covariant return types");
        assertFalse(Types.isAssignable(fakeImplementation, realContract),
                "a callable-valued field must never impersonate a method");
        assertFalse(Types.isAssignable(realImplementation, fakeContract),
                "a method must never implicitly become a writable data slot");
        assertFalse(Types.isAssignable(fakeImplementation, fakeContract),
                "writable function-valued data fields remain invariant");
        assertFalse(Types.isAssignable(fakeImplementation,
                realContract.withMembers(realContract.members(), true)),
                "readonly views cannot manufacture method identity");
        assertEquals(realContract.methodKeys(),
                realContract.withMembers(realContract.members(), true).methodKeys());
        assertTrue(fakeImplementation.methodKeys().isEmpty());
    }

    @Test
    void methodDataNameCollisionRejectsIntersectionEvenWithIdenticalType() {
        String slot = "instance$apply$arity1$generics0";
        Types.Function fn = new Types.Function(
                List.of(Types.Primitive.INT), Types.Primitive.STRING);
        Types.Record data = new Types.Record(Map.of(slot, fn));
        Types.Record methods = new Types.Record(Map.of(slot, fn), false, Set.of(slot));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Types.intersectOf(methods, data));
        assertTrue(failure.getMessage().contains("field/method collision"),
                failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Types.intersectOf(data, methods));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.Record(Map.of(slot, Types.Primitive.INT),
                        false, Set.of(slot)));
    }

    @Test
    void sourceRecordCannotClaimReservedCompilerMethodSlotName() {
        rejected("""
                type Forged = {"instance$apply$arity1$generics0": typeof fnc(int) => string};
                fnc consume(Forged value): void { return; }
                """, "reserved for compiler method contracts");
    }
}
