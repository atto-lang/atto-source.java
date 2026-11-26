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
                "const Tuple<infer T> t=(1,true); const Tuple<T> s=(true,1);",
                "const Tuple<infer T> t=(1,true); const Tuple<T> s=(1);",
                "const struct<infer T> r={a:1}; const Record<T> s=(a=1);"
        }) assertThrows(IllegalArgumentException.class, () -> check(body+" return 0;"),body);
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
}
