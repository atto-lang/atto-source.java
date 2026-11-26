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

final class NamedAggregateTypeBinderTest {
    private static void check(String body) {
        TypeChecker.check(Parser.parse("fnc main(): int { " + body + " }"));
    }
    @Test void wrapperDeclarationsUseExistingLexicalAliasAst() {
        String body = """
                const Record<infer T1> r = (a=true,b="xyz",c=5);
                const Tuple<infer T2> t = (true,"xyz",5);
                const struct<infer T3> s = {a:true,b:"xyz",c:5};
                const Record<T1> r2 = (c=10,b="abc",a=false);
                const Tuple<T2> t2 = (false,"abc",12);
                const struct<T3> s2 = {c:20,b:"abc",a:false};
                return r2.c + t2[2] + s2.c;
                """;
        assertDoesNotThrow(() -> check(body));
        Ast.Program ast = Parser.parse("fnc main(): int {" + body + "}");
        Ast.FunctionDecl fn = ast.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance).map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        assertEquals("T1", ((Ast.BindingStmt)fn.body().get(0)).inferredTypeName());
        assertEquals("T2", ((Ast.BindingStmt)fn.body().get(1)).inferredTypeName());
        assertEquals("T3", ((Ast.BindingStmt)fn.body().get(2)).inferredTypeName());
        assertEquals("T1", ((Ast.BindingStmt)fn.body().get(3)).declaredType().name());
    }
    @Test void badBindersAndReuseFailClosed() {
        for (String body : new String[]{
                "const Record<infer> r=(a=1);",
                "const Tuple<infer> t=(1,2);",
                "const struct<infer> s={a:1};",
                "const Record<Missing> r=(a=1);",
                "val Record<infer T> r=(a=1);",
                "const Record<infer T> r=(a=1,a=2);",
                "const Record<infer T> r=(a=1); const Tuple<infer T> t=(1,2);",
                "const Record<infer T> r=(a=1); const Record<T> s=(a=true);",
                "const Record<infer T> r=(a=1,b=2); const Record<T> s=(a=1);",
                "const Record<infer T> r=(a=1); const Record<T> s=(a=1,b=2);",
                "const Tuple<infer T> t=(1,true); const Tuple<T> s=(true,1);",
                "const Tuple<infer T> t=(1,true); const Tuple<T> s=(1);",
                "const struct<infer T> r={a:1}; const Record<T> s=(a=1);"
        }) assertThrows(IllegalArgumentException.class, () -> check(body+" return 0;"),body);
    }
    @Test void bareAggregateAnnotationsDoNotIntroduceAliases() {
        assertDoesNotThrow(() -> check("""
                const Record r=(a=10);
                const Tuple t=(true,12);
                const struct s={a:20};
                return r.a+t[1]+s.a;
                """));
        assertThrows(IllegalArgumentException.class, () -> check(
                "const Record r=(a=1); const Record<T> s=(a=1); return 0;"));
    }
    @Test void actualGuestPrints42() throws Exception {
        String source = """
                pub fnc main(): void {
                  const Record<infer R> r=(a=1,b=true);
                  const Record<R> r2=(b=false,a=10);
                  const Tuple<infer T> t=(true,2);
                  const Tuple<T> t2=(false,12);
                  const struct<infer S> s={a:1};
                  const struct<S> s2={a:20};
                  stdio.println(r2.a+t2[1]+s2.a);
                  return;
                }
                """;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(Context context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()){
            context.eval(Source.newBuilder(OresLanguage.ID,source,"named-aggregate.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("42",out.toString(StandardCharsets.UTF_8).trim());
    }

    private static final String MODULE_SHAPES = """
            const Record<infer T1> r = (a=true, b="xyz", c=5);
            const Tuple<infer T2> t = (true, "xyz", 5);
            """;

    @Test void namedAggregateCallableAndProjectedSequenceGuests() throws Exception {
        for (String[] variant : new String[][]{
                {"Tuple<T2>", "(input.a, input.b, input.c)", "const Tuple<T2> out = process(arg); stdio.println(out[2]);"},
                {"typeof input.c", "seq (input.a, input.b, input.c)", "stdio.println(process(arg));"},
                {"typeof input.c", "seq input.a, input.b, input.c", "stdio.println(process(arg));"}
        }) {
            String source = MODULE_SHAPES + "fnc process(Record<T1> input) -> " + variant[0]
                    + " { return " + variant[1] + "; }\n"
                    + "pub fnc main(): void { const Record<T1> arg=(a=false,b=\"abc\",c=42); "
                    + variant[2] + " return; }";
            TypeChecker.check(Parser.parse(source));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, source, "aggregate-call.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
            assertEquals("42", out.toString(StandardCharsets.UTF_8).trim(), variant[1]);
        }
    }

    @Test void callableShapeAndSequenceTypeErrorsReject() {
        for (String decl : new String[]{
                "fnc process(Record<T1> input) -> Tuple<T2> { return (input.b,input.a,input.c); }",
                "fnc process(Record<T1> input) -> typeof input.missing { return input.c; }",
                "fnc process(Record<T1> input) -> typeof input.c { return seq (input.c,input.a,input.b); }",
                "fnc process(Record<T1> input) -> typeof input.c { return seq input.c,input.a,input.b; }",
                "fnc process(Record<T1> input) -> typeof input.c { return seq input.a,input.b,input.c,; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(MODULE_SHAPES + decl + "\nfnc main(): int { return 0; }")), decl);
        }
    }
    @Test void moduleLiteralShapesExpandInLaterCallableSignatures() {
        Ast.Program ast = Parser.parse(MODULE_SHAPES
                + "fnc process(Record<T1> input) -> Tuple<T2> { return (input.a,input.b,input.c); }");
        Ast.FunctionDecl fn = ast.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance).map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        assertTrue(fn.parameters().getFirst().type().isRecordType());
        assertTrue(fn.returnType().isTupleType());
        assertEquals("int", fn.parameters().getFirst().type().aggregateMembers().get("c").name());
        assertDoesNotThrow(() -> TypeChecker.check(ast));
    }

    @Test void unsupportedModuleInitializersAndCollisionsReject() {
        for (String source : new String[]{
                "const struct<infer S> s={a:1}; fnc main(): int { return 0; }",
                "const Record<infer T> r=(a=1+2); fnc main(): int { return 0; }",
                "const Record<infer T> r=(a=1); const Tuple<infer T> t=(1,2);",
                "type T = int; const Record<infer T> r=(a=1);",
                "const Record<infer T> r=(a=1); type T = int;",
                "const Record<infer T> r=(a=1); const Record<T> r2=(a=1,b=2);"
        }) assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
    }

    @Test void moduleShapeNamesAreNotVisibleInSiblingModuleParserScopes() {
        Ast.Program ast = Parser.parse("""
                define module first as
                    const Record<infer T> r=(a=1);
                    fnc f(Record<T> input): int { return input.a; }
                end
                define module second as
                    const Tuple<infer T> t=(true,2);
                    fnc f(Tuple<T> input): int { return input[1]; }
                end
                """);
        var first = (Ast.FunctionDecl) ast.modules().get(0).declarations().getLast();
        var second = (Ast.FunctionDecl) ast.modules().get(1).declarations().getLast();
        assertTrue(first.parameters().getFirst().type().isRecordType());
        assertTrue(second.parameters().getFirst().type().isTupleType());
    }

    @Test void wrapperParserActuallyDispatchesInsideCallableBody() {
        Ast.Program ast = Parser.parse("fnc main(): int { const Record<infer R> r=(a=1); "
                + "const Record<R> other=(a=42); return other.a; }");
        Ast.FunctionDecl fn = ast.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance).map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        assertEquals("R", ((Ast.BindingStmt) fn.body().get(0)).inferredTypeName());
        assertEquals("R", ((Ast.BindingStmt) fn.body().get(1)).declaredType().name());
    }

    @Test void sequenceReturnLowersBothSpellingsToProceduralAst() {
        Ast.FunctionDecl grouped = (Ast.FunctionDecl) Parser.parse(
                "fnc f(int n): int { return seq (true,\"abc\",n); }")
                .modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl bare = (Ast.FunctionDecl) Parser.parse(
                "fnc f(int n): int { return seq true,\"abc\",n; }")
                .modules().getFirst().declarations().getFirst();
        assertEquals(grouped.body(), bare.body());
        Ast.BlockStmt block = assertInstanceOf(Ast.BlockStmt.class, bare.body().getFirst());
        assertEquals(3, block.body().size());
        assertInstanceOf(Ast.ExprStmt.class, block.body().get(0));
        assertInstanceOf(Ast.ExprStmt.class, block.body().get(1));
        assertInstanceOf(Ast.ReturnStmt.class, block.body().get(2));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "fnc f(int n): int { return seq true,\"abc\",n; }")));
    }

    @Test void sequencePrefixesExecuteOnceInSourceOrderInRealGuest() throws Exception {
        for (String form : new String[]{"seq (prefix(1),prefix(2),42)", "seq prefix(1),prefix(2),42"}) {
            String source = "fnc prefix(int n): int { stdio.println(n); return n; } "
                    + "fnc f(): int { return " + form + "; } "
                    + "pub fnc main(): void { stdio.println(f()); return; }";
            TypeChecker.check(Parser.parse(source));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, source, "seq-prefix-order.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
            assertEquals("1\n2\n42", out.toString(StandardCharsets.UTF_8).trim().replace("\r\n","\n"));
        }
    }

    @Test void malformedReturnSequencesFailBeforeLowering() {
        for (String form : new String[]{"seq", "seq ()", "seq 1,", "seq (1,)", "seq ,42"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc f(): int { return " + form + "; }"), form);
        }
    }
}
