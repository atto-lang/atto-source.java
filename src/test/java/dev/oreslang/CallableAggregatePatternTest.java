package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CallableAggregatePatternTest {
    private static Ast.Program check(String program) {
        Ast.Program typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);
        return typed;
    }

    private static void execute(String program, String expected) throws Exception {
        check(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "callable-aggregate-pattern.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).err(diagnostics).build()) {
            context.eval(source);
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8));
        assertEquals("", diagnostics.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unaryTuplePatternKeepsOneFormalSlot() {
        Ast.Program program = Parser.parse("""
                define module app as
                  fnc total((a: int, b: int, c: int)): int {
                    return a + b + c;
                  }
                end
                """);
        Ast.FunctionDecl total = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(1, total.parameters().size());
        assertTrue(total.parameters().getFirst().type().isTupleType());
        assertInstanceOf(Ast.DestructureStmt.class, total.body().getFirst());
        check("""
                fnc total((a: int, b: int, c: int)): int { return a + b + c; }
                fnc use(): int { return total((5, 3, 5)); }
                """);
    }

    @Test
    void tuplePipelineAndNamedCallableExecuteOnce() throws Exception {
        execute("""
                fnc values(): (int, int, int) {
                    stdio.stdout.write("once:");
                    return (5, 3, 5);
                }
                fnc total((a: int, b: int, c: int)): int {
                    return a + b + c;
                }
                pub routine main(): void {
                    stdio.stdout.write(values() |> |(a, b, c)| -> a + b + c);
                    stdio.stdout.write(":");
                    stdio.stdout.write(total((5, 3, 5)));
                    return;
                }
                """, "once:13:13");
    }

    @Test
    void existingMultiFormalPipelineStillExpandsTuple() throws Exception {
        execute("""
                pub routine main(): void {
                    stdio.stdout.write((5, 3, 5) |> |a, b, c| -> a + b + c);
                    return;
                }
                """, "13");
    }

    @Test
    void recordFieldAliasAndReorderedSelection() throws Exception {
        execute("""
                fnc total({y as b, x as a}: {x: int, y: int}): int { return a + b; }
                pub routine main(): void {
                    stdio.stdout.write(total(record{x: 5, y: 8}));
                    stdio.stdout.write(":");
                    stdio.stdout.write(record{x: 5, y: 8, extra: true} |> |{y, x}| -> x + y);
                    return;
                }
                """, "13:13");
    }

    @Test
    void structuralStructFieldsAreRealProjections() throws Exception {
        execute("""
                pub routine main(): void {
                    val point = infer struct{x: 5, y: 8};
                    stdio.stdout.write(point |> |{x, y}| -> x + y);
                    return;
                }
                """, "13");
    }

    @Test
    void nominalClassPublicDataFieldsAndMethodParameter() throws Exception {
        execute("""
                define class Point as
                    pub val int x = 5;
                    pub val int y = 8;
                end
                define class Calculator as
                    pub total(Point {x, y}): int { return x + y; }
                end
                fnc total(Point {x, y}): int { return x + y; }
                pub routine main(): void {
                    stdio.stdout.write(total(new Point()));
                    stdio.stdout.write(":");
                    stdio.stdout.write(new Point() |> |Point {x, y}| -> x + y);
                    stdio.stdout.write(":");
                    stdio.stdout.write(new Calculator().total(new Point()));
                    return;
                }
                """, "13:13:13");
    }

    @Test
    void nominalClassPrivateFieldAndMethodAreNotPatternFields() {
        for (String selector : new String[]{"secret", "read"}) {
            assertThrows(IllegalArgumentException.class, () -> check("""
                    define class Vault as
                        private val int secret = 7;
                        pub read(): int { return self.secret; }
                    end
                    fnc bad(Vault {%s}): int { return 0; }
                    """.formatted(selector)), selector);
        }
    }

    @Test
    void emptyTupleIsOneFormalAndSingletonRequiresComma() throws Exception {
        execute("""
                fnc zero(()): int { return 7; }
                fnc single((a: int,)): int { return a; }
                pub routine main(): void {
                    stdio.stdout.write(zero(()));
                    stdio.stdout.write(":");
                    stdio.stdout.write(() |> |()| -> 8);
                    stdio.stdout.write(":");
                    stdio.stdout.write(single((9,)));
                    return;
                }
                """, "7:8:9");
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad(): int { return (1,) |> |()| -> 0; }"));
    }

    @Test
    void rebindScalarLeafIsIndependentOfRootAndFieldDiscardChecksExistence() throws Exception {
        execute("""
                fnc total((let a: int, val b: int)): int { a = a + 1; return a+b; }
                fnc fields({x as let a, y as _, z}: {x: int, y: int, z: int}): int {
                    a = a + 1; return a+z;
                }
                pub routine main(): void {
                    stdio.stdout.write(total((5, 8)));
                    stdio.stdout.write(":");
                    stdio.stdout.write(fields(record{x:5,y:99,z:8}));
                    return;
                }
                """, "14:14");
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad({missing as _}: {x: int}): int { return 0; }"));
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad({x as _, x as a}: {x: int}): int { return a; }"));
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad((val a: int, b: int)): int { a = 0; return b; }"));
    }

    @Test
    void discardCannotIntroduceBindingOrPretendToBeTyped() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad((_: int, a: int)): int { return a; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad((let _, a): (int,int)): int { return a; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad({x as let _}: {x: int}): int { return 0; }"));
    }

    @Test
    void explicitTupleTypeFormalRemainsOrdinary() {
        check("""
                fnc ordinary((int, int) t): int { return t[0] + t[1]; }
                fnc use(): int { return ordinary((5, 8)); }
                """);
    }

    @Test
    void recordTypeFollowedByFormalNameIsNotPattern() {
        check("""
                fnc ordinary(record {x: int, y: int} t): int { return t.x + t.y; }
                """);
    }

    @Test
    void methodPatternHasOneRootAndDoesNotFlattenArity() {
        Ast.Program program = Parser.parse("""
                define module app as
                  define class Calculator as
                    pub total((a: int, b: int)): int { return a + b; }
                  end
                end
                """);
        Ast.ClassDecl cls = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(1, cls.methods().getFirst().parameters().size());
        assertInstanceOf(Ast.DestructureStmt.class, cls.methods().getFirst().body().getFirst());
        OwnershipChecker.check(TypeChecker.check(program));
    }

    @Test
    void rejectsWrongArityScalarAndArray() {
        for (String input : new String[]{"(1, 2, 3)", "1", "[1, 2]"}) {
            assertThrows(IllegalArgumentException.class, () -> check(
                    "fnc bad(): int { return " + input + " |> |(a,b)| -> a+b; }"), input);
        }
    }

    @Test
    void rejectsDuplicateLeafAndDuplicateSelector() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad((a: int, a: int)): int { return a; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad((a: int, b: int), int a): int { return a; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad({x as a, x as b}: {x: int}): int { return a+b; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad({x as self}: {x: int}): int { return 0; }"));
    }

    @Test
    void rejectsMissingFieldAndNonCopyLeaves() {
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad(): int { return record{x:1} |> |{y}| -> y; }"));
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad(): int { return (1, (2,3)) |> |(a,b)| -> a; }"));
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc bad(): int { return record{x:(2,3)} |> |{x}| -> 1; }"));
    }

    @Test
    void rejectsUnsupportedNestedAndAliasRatherThanFlattening() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad(): int { return ((1,2),3) |> |((a,b),c)| -> a; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad(): int { return (1,2) |> |(a,b) as t| -> a; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad((a)): int { return a; }"));
    }

    @Test
    void leafCannotBeUsedAsNamedArgument() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc total((a: int, b: int)): int { return a + b; }
                fnc bad(): int { return total(a: 1, b: 2); }
                """));
    }

    // Independent bounded source generator. Do not derive the oracle from
    // Parser/TypeChecker output, or a wrong lowering can certify itself.
    private record GeneratedPattern(int arity, int code, String name,
                                    String pattern, String tupleType, String tupleValue,
                                    String body, long expected) { }

    private static String tupleSyntax(java.util.List<String> items) {
        return "(" + String.join(", ", items) + (items.size() == 1 ? "," : "") + ")";
    }

    private static java.util.List<GeneratedPattern> generatedPatterns(int maxArity) {
        java.util.List<GeneratedPattern> result = new java.util.ArrayList<>();
        int combinations = 1;
        for (int n = 0; n <= maxArity; n++, combinations *= 3) {
            for (int code = 0; code < combinations; code++) {
                java.util.List<String> leaves = new java.util.ArrayList<>();
                java.util.List<String> types = new java.util.ArrayList<>();
                java.util.List<String> values = new java.util.ArrayList<>();
                StringBuilder body = new StringBuilder();
                StringBuilder expression = new StringBuilder("tail");
                long expected = 19;
                int digits = code;
                for (int i = 0; i < n; i++, digits /= 3) {
                    int mode = digits % 3; // fixed, locally rebindable, discard
                    String name = "p_" + i;
                    int value = i * 3 + 2;
                    long weight = i + 2;
                    types.add("int");
                    values.add(Integer.toString(value));
                    leaves.add(mode == 2 ? "_" : (mode == 1 ? "let " : "val ") + name);
                    if (mode == 2) continue;
                    if (mode == 1) body.append(name).append(" = ").append(name).append(" + 1; ");
                    expression.append(" + ").append(weight).append(" * ").append(name);
                    expected += weight * (value + (mode == 1 ? 1 : 0));
                }
                body.append("return ").append(expression).append(";");
                result.add(new GeneratedPattern(n, code, "case_" + n + "_" + code,
                        tupleSyntax(leaves), tupleSyntax(types), tupleSyntax(values),
                        body.toString(), expected));
            }
        }
        return result;
    }

    private static String generatedDeclaration(GeneratedPattern p) {
        return "fnc " + p.name() + "(" + p.pattern() + ": " + p.tupleType()
                + ", int tail): int { " + p.body() + " }\n";
    }

    @Test
    void generatedTuplePlansPreserveActualFormalSlotsAndProjectionOrder() {
        var cases = generatedPatterns(4); // all 121 binding-mode combinations
        assertEquals(121, cases.size());
        String source = "define module app as\n"
                + String.join("", cases.stream().map(CallableAggregatePatternTest::generatedDeclaration).toList())
                + "end";
        Ast.Program program = check(source);
        var declarations = program.modules().getFirst().declarations();
        assertEquals(cases.size(), declarations.size());
        for (int k = 0; k < cases.size(); k++) {
            GeneratedPattern p = cases.get(k);
            Ast.FunctionDecl f = assertInstanceOf(Ast.FunctionDecl.class, declarations.get(k));
            assertEquals(p.name(), f.name());
            assertEquals(2, f.parameters().size(), p.name());
            assertTrue(f.parameters().getFirst().type().isTupleType(), p.name());
            assertEquals(p.arity(), f.parameters().getFirst().type().arguments().size(), p.name());
            assertEquals("tail", f.parameters().get(1).name());
            Ast.DestructureStmt d = assertInstanceOf(Ast.DestructureStmt.class, f.body().getFirst());
            assertEquals(Ast.DestructureKind.SEQUENCE, d.kind());
            Ast.NameExpr root = assertInstanceOf(Ast.NameExpr.class, d.initializer());
            assertEquals(f.parameters().getFirst().name(), root.name(), p.name());
            assertEquals(p.arity(), d.bindings().size(), p.name());
            int digits = p.code();
            for (int i = 0; i < p.arity(); i++, digits /= 3) {
                int mode = digits % 3;
                Ast.DestructureBinding slot = d.bindings().get(i);
                assertEquals(mode == 2, slot.isDiscard(), p.name());
                assertFalse(slot.rest());
                assertFalse(slot.mutableReferent());
                if (mode != 2) {
                    assertEquals("p_" + i, slot.name(), p.name());
                    assertEquals(mode == 1 ? Ast.BindingKind.LET : Ast.BindingKind.VAL, slot.kind());
                }
            }
        }
    }

    @Test
    void generatedGuestResultsAndArgumentTraceRefineIndependentScalarOracle() throws Exception {
        var cases = generatedPatterns(3); // 40 independently computed oracles
        assertEquals(40, cases.size());
        StringBuilder source = new StringBuilder();
        for (int n = 0; n <= 3; n++) {
            final int arity = n;
            var sample = cases.stream().filter(p -> p.arity() == arity).findFirst().orElseThrow();
            source.append("fnc source_").append(n).append("(): ").append(sample.tupleType())
                    .append(" { stdio.stdout.write(\"A\"); return ").append(sample.tupleValue()).append("; }\n");
        }
        source.append("fnc next_tail(): int { stdio.stdout.write(\"B\"); return 19; }\n");
        for (GeneratedPattern p : cases) source.append(generatedDeclaration(p));
        source.append("pub routine main(): void {\n");
        StringBuilder expected = new StringBuilder();
        for (GeneratedPattern p : cases) {
            source.append("stdio.stdout.write(").append(p.name()).append("(source_")
                    .append(p.arity()).append("(), next_tail())); stdio.stdout.write(\";\");\n");
            expected.append("AB").append(p.expected()).append(";");
        }
        source.append("return; }\n");
        execute(source.toString(), expected.toString());
    }
}
