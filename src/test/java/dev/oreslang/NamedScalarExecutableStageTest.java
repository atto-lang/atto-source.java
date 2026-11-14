package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.CheckedCallPlan;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First true named-value execution gate. This deliberately accepts ONLY
 * statically resolved, fully supplied, required int->int free functions;
 * optional/defaulted signatures are still fail-closed.
 * Guest tests are authored and require an actual JVM runner.
 */
final class NamedScalarExecutableStageTest {
    private static String execute(String guest) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "named_scalar.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void typeAndOwnershipCheckPublishLexicalToAbiPermutation() {
        Ast.Program parsed = Parser.parse("""
                fnc encode(x: int, y: int): int { return x * 10 + y; }
                pub fnc main(): int { return encode(y: 2, x: 4); }
                """);
        Ast.Program checked = TypeChecker.check(parsed);
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().get(1));
        Ast.ReturnStmt result = assertInstanceOf(Ast.ReturnStmt.class,
                main.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, result.value());
        assertEquals(List.of("y", "x"), call.argumentLabels());
        CheckedCallPlan plan = CheckedCallPlan.require(call);
        assertEquals(List.of(1, 0), plan.abiToSource());
        assertEquals(0, plan.omittedCount());
        assertEquals(List.of(1, 0), plan.sourceOrder().stream()
                .map(CheckedCallPlan.BoundSlot::parameterIndex).toList());
    }

    @Test
    void jvmGuestExecutesNamedReorderedAndMixedCalls() throws Exception {
        assertEquals("42:42", execute("""
                fnc encode(x: int, y: int): int { return x * 10 + y; }
                pub fnc main(): void {
                  stdio.stdout.write(encode(y: 2, x: 4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(encode(4, y: 2));
                  return;
                }
                """));
    }

    @Test
    void jvmGuestEvaluatesNamedArgumentsInLexicalOrderOnlyOnce() throws Exception {
        assertEquals("24:42", execute("""
                fnc mark(value: int): int {
                  stdio.stdout.write(value);
                  return value;
                }
                fnc encode(x: int, y: int): int { return x * 10 + y; }
                pub fnc main(): void {
                  val int result = encode(y: mark(2), x: mark(4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(result);
                  return;
                }
                """));
    }

    @Test
    void parserRejectsDuplicateLabelsOrPositionalAfterNamed() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(x:int,y:int):int{return x+y;}"
                        + "pub fnc main():int{return f(x:1,x:2);}"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(x:int,y:int):int{return x+y;}"
                        + "pub fnc main():int{return f(y:1,2);}"));
    }

    @Test
    void unknownLabelAndPositionalCollisionFailBeforeRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc encode(x:int,y:int):int{return x*10+y;}
                        pub fnc main():int{return encode(z:2,x:4);}
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc encode(x:int,y:int):int{return x*10+y;}
                        pub fnc main():int{return encode(4,x:2);}
                        """)));
    }

    @Test
    void checkedClosedScalarOmissionNowAdmittedButRuntimeDefaultsRemainBounded() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                        fnc calculate(x: int, y?: int = 10, z?: int?): int {
                          return x + y;
                        }
                        pub fnc main(): int { return calculate(x: 20, y: 22); }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc unsafe(x: int, y?: int = effect()): int {
                          return x;
                        }
                        pub fnc main(): int { return unsafe(x: 20); }
                        """)),
                "runtime-dependent defaults cannot escape the constant-only ABI");
    }

    @Test
    void ownershipCannotAuthorizeNamedCallWithoutTypecheckedPlan() {
        Ast.Program raw = Parser.parse("""
                fnc add(x:int,y:int):int{return x+y;}
                pub fnc main():int{return add(y:2,x:40);}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(raw),
                "unverified named call must not be executable from ownership-only entrypoint");
    }

    @Test
    void checkedMetadataCannotBeTransferredAcrossChangedLabels() {
        Ast.NameExpr f = new Ast.NameExpr("f");
        Ast.LiteralExpr a = new Ast.LiteralExpr(1L);
        Ast.LiteralExpr b = new Ast.LiteralExpr(2L);
        Ast.CallExpr one = new Ast.CallExpr(f, List.of(), false,
                List.of(a, b), List.of("x", "y"));
        Ast.CallExpr different = new Ast.CallExpr(f, List.of(), false,
                List.of(a, b), List.of("y", "x"));
        CheckedCallPlan.record(one, CheckedCallPlan.required(
                List.of("x", "y"),
                List.of(CheckedCallPlan.InputSlot.named("x", 0),
                        CheckedCallPlan.InputSlot.named("y", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.copyCheckedMetadata(one, different));
        assertNull(CheckedCallPlan.lookup(different));
    }
}
