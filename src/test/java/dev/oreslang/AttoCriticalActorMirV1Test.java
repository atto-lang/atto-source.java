package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.CriticalActorMirV1;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** True Atto source -> typed critical MIR; still not executable guest dispatch. */
final class AttoCriticalActorMirV1Test {
    private static final String HEADER = """
            use namespace foo;
            declare actor CounterActor;
            define state on actor CounterActor as {
                let int value : 0;
                let bool enabled : true;
            }
            """;
    private static final String BODY = """
            critical mtd (CounterActor c) add(int delta): int {
                let int result = 0;
                using state.write(critical |s| -> {
                    if s.enabled and s.value >= 0 then
                        s.value = s.value + delta;
                        result = s.value;
                    fi
                });
                return result;
            }
            """;

    private static CriticalActorMirV1.Method lower(String body) {
        var program = Parser.parse(HEADER + body);
        var slot = new ActorStateBinder.Slot("counter@1", "foo", "CounterActor");
        return CriticalActorMirV1.lowerSourceSet("counter@1",
                Map.of("src/counter.atto", program)).get(slot).getFirst();
    }

    @Test
    void lowersRealSourceToSealedTypedMirWithoutGrantingRuntimeAuthority() {
        var result = lower(BODY);
        assertEquals("CounterActor", result.actor());
        assertEquals("add", result.name());
        assertEquals(CriticalActorMirV1.Type.I64, result.returns());
        assertEquals(Map.of("delta", CriticalActorMirV1.Type.I64), result.parameters());
        assertInstanceOf(CriticalActorMirV1.Declare.class, result.steps().getFirst());
        var loan = assertInstanceOf(CriticalActorMirV1.WriteLoan.class, result.steps().get(1));
        var conditional = assertInstanceOf(CriticalActorMirV1.Branch.class, loan.body().getFirst());
        assertEquals(CriticalActorMirV1.Type.BOOL, conditional.condition().type());
        var write = assertInstanceOf(CriticalActorMirV1.SetField.class,
                conditional.yes().getFirst());
        var checkedAdd = assertInstanceOf(CriticalActorMirV1.Binary.class, write.value());
        assertEquals("add_checked", checkedAdd.op());
        assertEquals(CriticalActorMirV1.Type.I64, checkedAdd.type());
        assertInstanceOf(CriticalActorMirV1.Return.class, result.steps().getLast());
        assertThrows(UnsupportedOperationException.class, () -> result.steps().clear());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(HEADER + BODY)))
                .getMessage().contains("LOWERING_UNSUPPORTED"));
    }

    @Test
    void rejectsUnsealedFieldUntypedBranchImplicitConversionAndBorrowEscape() {
        for (String mutated : new String[] {
                BODY.replace("s.value = s.value + delta", "s.value = true"),
                BODY.replace("s.value = s.value + delta", "s.unknown = 4"),
                BODY.replace("s.enabled and s.value >= 0", "s.value"),
                BODY.replace("s.enabled and s.value >= 0", "s.enabled + 1"),
                BODY.replace("s.value + delta", "s.value + true"),
                BODY.replace("let int result = 0", "let bool result = 0"),
                BODY.replace("return result;", "return true;"),
                BODY.replace("s.value = s.value + delta", "s.value = c.value"),
                BODY.replace("s.value = s.value + delta", "s.value = hostcall()"),
                BODY.replace("return result;", "return s.value;"),
                BODY.replace("let int result = 0", "let int delta = 0")
        }) {
            assertThrows(IllegalArgumentException.class, () -> lower(mutated), mutated);
        }
    }

    @Test
    void disallowsActorAndNamespaceMisattachmentAndUnboundedMir() {
        var source = Parser.parse(HEADER + BODY);
        assertThrows(IllegalArgumentException.class,
                () -> CriticalActorMirV1.lowerSourceSet("counter@1",
                        Map.of("src/actor.atto", Parser.parse(HEADER),
                               "src/wrong.atto",
                               Parser.parse("use namespace other;\n" + BODY))));
        var critical = source.modules().getFirst().declarations().stream()
                .filter(Ast.CriticalActorMethodDecl.class::isInstance)
                .map(Ast.CriticalActorMethodDecl.class::cast).findFirst().orElseThrow();
        var schema = source.modules().getFirst().declarations().stream()
                .filter(Ast.ActorStateDecl.class::isInstance)
                .map(Ast.ActorStateDecl.class::cast).findFirst().orElseThrow();
        var swapped = new Ast.ActorStateDecl("OtherActor", schema.fields());
        assertThrows(IllegalArgumentException.class,
                () -> CriticalActorMirV1.lower(swapped, critical));
    }

    @Test
    void handlesBooleanAndVoidReturnWithoutActorCallback() {
        var program = Parser.parse(HEADER + """
                critical mtd (CounterActor c) disable(): void {
                    using state.write(critical |s| -> {
                        s.enabled = false;
                    });
                    return;
                }
                """);
        var methods = CriticalActorMirV1.lowerSourceSet("counter@1",
                Map.of("src/counter.atto", program));
        var method = methods.values().iterator().next().getFirst();
        assertEquals(CriticalActorMirV1.Type.VOID, method.returns());
        assertNull(assertInstanceOf(CriticalActorMirV1.Return.class,
                method.steps().getLast()).value());
    }

    @Test
    void branchLocalAndLoanLocalVariablesCannotEscapeTheirLifetime() {
        var scoped = """
                critical mtd (CounterActor c) scoped(): int {
                    let int answer = 0;
                    using state.write(critical |s| -> {
                        if s.enabled then
                            let int temporary = 4;
                            s.value = temporary;
                        fi
                        answer = temporary;
                    });
                    return answer;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> lower(scoped));
        var escapedLoan = """
                critical mtd (CounterActor c) escaped(): int {
                    using state.write(critical |s| -> {
                        let int temporary = 4;
                        s.value = temporary;
                    });
                    return temporary;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> lower(escapedLoan));
    }

    @Test
    void directlyConstructedBorrowedAstStateCannotBypassMirTyping() {
        var parsed = Parser.parse(HEADER + BODY);
        var method = parsed.modules().getFirst().declarations().stream()
                .filter(Ast.CriticalActorMethodDecl.class::isInstance)
                .map(Ast.CriticalActorMethodDecl.class::cast).findFirst().orElseThrow();
        var borrowed = new Ast.ActorStateDecl("CounterActor", java.util.List.of(
                new Ast.ProtectedStateField("value",
                        Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false),
                        new Ast.LiteralExpr(0L))));
        assertThrows(IllegalArgumentException.class,
                () -> CriticalActorMirV1.lower(borrowed, method));
    }
}
