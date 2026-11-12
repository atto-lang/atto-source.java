package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unqualified DIRECT calls bind lexical self only when an instance method
 * family exists. They must keep the same typed and owned invocation semantics
 * as explicit self.method(...) calls.
 */
final class ImplicitSelfLookupHardeningTest {

    @Test
    void executableClassCallsUseSameReceiverAsExplicitSelf() throws Exception {
        assertEquals("14", run("""
                define class Counter as
                  pub value(): int { return 7; }
                  pub both(): int { return value() + self.value(); }
                end
                pub routine main(): void {
                  val item = new Counter();
                  stdio.stdout.write(item.both());
                }
                """));
    }

    @Test
    void inheritedImplicitCallKeepsVirtualDispatch() throws Exception {
        assertEquals("4", run("""
                define class Base as
                  pub value(): int { return 1; }
                  pub run(): int { return value(); }
                end
                define class Child extends Base as
                  pub value(): int { return 4; }
                end
                pub routine main(): void {
                  val child = new Child();
                  stdio.stdout.write(child.run());
                }
                """));
    }

    @Test
    void dynamicSubclassCannotHijackLexicallySelectedFreeFunction() throws Exception {
        assertEquals("99", run("""
                fnc helper(): int { return 99; }
                define class Base as
                  pub execute(): int { return helper(); }
                end
                define class Child extends Base as
                  pub helper(): int { return 7; }
                end
                pub routine main(): void {
                  val child = new Child();
                  stdio.stdout.write(child.execute());
                }
                """));
    }

    @Test
    void unqualifiedSuspendingMethodCannotBypassCallerEffect() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          suspend method later(): void { return; }
                          method run(): void {
                            later();
                            return;
                          }
                        end
                        """)));
    }

    @Test
    void explicitMethodValueExtractionStaysDirectCallOnly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub calculate(): int { return 7; }
                          pub run(): void {
                            val callback = self.calculate;
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("direct-call-only"), failure.getMessage());
    }

    @Test
    void actorMethodsCanCallSiblingMethodsWithoutSelf() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  pub heartbeat(): int { return 7; }
                  pub run(): int { return heartbeat() + self.heartbeat(); }
                end
                """)));
    }

    @Test
    void actorMethodFamilyTakesPriorityOverFileFunction() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc heartbeat(): int { return 99; }
                define actor Worker as
                  pub heartbeat(): int { return 7; }
                  pub run(): int { return heartbeat(); }
                end
                """)));
    }

    @Test
    void executableClassMethodFamilyWinsOverSameArityFreeFunction() throws Exception {
        assertEquals("42", run("""
                fnc value(): int { return 99; }
                define class Box as
                  pub value(): int { return 40; }
                  pub answer(): int { return value() + 2; }
                end
                pub routine main(): void {
                  val box = new Box();
                  stdio.stdout.write(box.answer());
                }
                """));
    }

    @Test
    void wrongArityClassMethodCannotFallThroughToExactRootFunction() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc value(): int { return 99; }
                        define class Box as
                          pub value(int n): int { return n; }
                          pub answer(): int { return value(); }
                        end
                        """)));
        assertNotNull(failure.getMessage());
    }

    @Test
    void wrongArityCannotFallThroughToGlobalFunction() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc heartbeat(int value): int { return value; }
                        define actor Worker as
                          pub heartbeat(): int { return 7; }
                          pub run(): int { return heartbeat(99); }
                        end
                        """)));
        assertNotNull(failure.getMessage());
    }

    @Test
    void localCallableWinsOverMethodFamily() throws Exception {
        assertEquals("9", run("""
                define class Box as
                  pub calculate(): int { return 7; }
                  pub run(): int {
                    val (() => int) calculate = || -> 9;
                    return calculate();
                  }
                end
                pub routine main(): void {
                  val box = new Box();
                  stdio.stdout.write(box.run());
                }
                """));
    }

    @Test
    void localNonCallableDoesNotFallBackToInstanceMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub calculate(): int { return 7; }
                          pub run(): int {
                            val calculate = 42;
                            return calculate();
                          }
                        end
                        """)));
    }

    @Test
    void actorLifecycleHooksRemainRuntimeOnly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub on_start(): void { return; }
                          pub run(): void {
                            on_start();
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("lifecycle"), failure.getMessage());
    }

    @Test
    void bareNameNeverCapturesActorSelfAsMethodValue() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub heartbeat(): int { return 7; }
                          pub run(): void {
                            val handler = heartbeat;
                            return;
                          }
                        end
                        """)));
    }

    @Test
    void classConstructorCannotInvokeUnqualifiedInstanceMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          constructor() { calculate(); }
                          pub calculate(): void { return; }
                        end
                        """)));
    }

    @Test
    void freeFunctionCannotAcquireImplicitSelf() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub calculate(): int { return 7; }
                        end
                        fnc outsider(): int { return calculate(); }
                        """)));
    }

    @Test
    void reservedActorReceiveCannotBeCalledUnqualified() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(ActorMail<String> mail): void { return; }
                          pub run(): void {
                            receive();
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("lifecycle"), failure.getMessage());
    }

    @Test
    void unqualifiedHandleStateCannotInventStateAuthorityBeforeLowering() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub run(): void {
                            handle_state();
                            return;
                          }
                        end
                        """)));
        IllegalArgumentException gated = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Worker as
                          state { generation: int = 1; }
                          pub run(): void {
                            handle_state();
                            return;
                          }
                        end
                        """));
        assertTrue(gated.getMessage().contains("state ownership and handle_state lowering"),
                gated.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "implicit-self-lookup.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
