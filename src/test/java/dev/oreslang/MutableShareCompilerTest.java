package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rt mut borrow is an exclusive scoped loan. rt mut share retains an existing
 * synchronized SharedMutex handle; it must never make arbitrary T writable.
 */
final class MutableShareCompilerTest {
    private static final String COUNTER = """
            define class Counter as
              pub let int value = 1;
            end
            """;

    private static void checked(String code) {
        Ast.Program program = TypeChecker.check(Parser.parse(code));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    private static void rejected(String code, String diagnostic) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(code))));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test
    void parsesMutableShareAsItsOwnOperationNotMutableBorrowOrPlainShare() {
        var program = Parser.parse("""
                fnc example(): void {
                  val shared = rt mut share mutex;
                  val borrowed = rt mut borrow foo;
                  return;
                }
                """);
        var function = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        var shared = (Ast.BindingStmt) function.body().get(0);
        var borrowed = (Ast.BindingStmt) function.body().get(1);
        var share = assertInstanceOf(Ast.RuntimeCallExpr.class, shared.initializer());
        assertEquals("mut_share", share.operation());
        assertEquals(1, share.arguments().size());
        assertEquals("&mut", assertInstanceOf(Ast.UnaryExpr.class, borrowed.initializer()).operator());
    }

    @Test
    void admitsOwnedBuiltinSharedMutexHandleWithoutDuplicatingProtectedObject() throws Exception {
        String program = COUNTER + """
                pub routine main(): void {
                  val mutex = SharedMutex.new(new Counter());
                  val alias = rt mut share mutex;
                  val guard = alias.lock();
                  guard.value = 9;
                  guard.release();
                  val observed = mutex.lock();
                  stdio.stdout.write(observed.value);
                  observed.release();
                  return;
                }
                """;
        checked(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder("ores", program, "rt-mut-share.ores").build());
        }
        assertEquals("9", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOrdinaryMutableObjectAndLocalMutex() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val counter = new Counter();
                  val alias = rt mut share counter;
                  return;
                }
                """, "rt mut share requires");
        rejected("""
                fnc bad(): void {
                  val local = Mutex.new(7);
                  val alias = rt mut share local;
                  return;
                }
                """, "rt mut share requires");
    }

    @Test
    void rejectsScalarsBorrowedHandlesAndUnprovenTypes() {
        rejected("""
                fnc bad(): void {
                  val alias = rt mut share 42;
                  return;
                }
                """, "rt mut share requires");
        rejected("""
                fnc bad(): void {
                  val mutex = SharedMutex.new(7);
                  val borrowed = rt borrow mutex;
                  val alias = rt mut share borrowed;
                  return;
                }
                """, "rt mut share requires");
        rejected("""
                fnc bad<T>(T value): void {
                  val alias = rt mut share value;
                  return;
                }
                """, "rt mut share requires");
    }

    @Test
    void rejectsForgedNominalSharedMutexDeclaration() {
        // An earlier reserved-name/type gate may reject this class before
        // the rt mut share ownership gate; both must deny the forgery.
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class SharedMutex<T> as
                          pub let int value = 1;
                        end
                        fnc bad(): void {
                          val fake = new SharedMutex<int>();
                          val alias = rt mut share fake;
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("SharedMutex"), error.getMessage());
    }

    @Test
    void mutableBorrowStillExcludesConflictingLoans() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val counter = new Counter();
                  val write = rt mut borrow counter;
                  val read = rt borrow counter;
                  return;
                }
                """, "borrow");
        checked(COUNTER + """
                fnc ok(): void {
                  val counter = new Counter();
                  val write = rt mut borrow counter;
                  write.value = 7;
                  return;
                }
                """);
    }

    @Test
    void guardedSharingCannotEscapeTheSynchronizationProtocol() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val mutex = SharedMutex.new(new Counter());
                  val alias = rt mut share mutex;
                  alias.value = 99;
                  return;
                }
                """, "value");
        rejected(COUNTER + """
                fnc bad(): void {
                  val mutex = SharedMutex.new(new Counter());
                  val guard = mutex.lock();
                  val alias = rt mut share guard;
                  return;
                }
                """, "rt mut share requires");
    }

    @Test
    void heldGuardAndExclusiveBorrowCannotCrossSuspension() {
        rejected(COUNTER + """
                async fnc bad(): void {
                  val counter = new Counter();
                  val loan = rt mut borrow counter;
                  rt cooperate;
                  loan.value = 5;
                  return;
                }
                """, "borrow");
        rejected(COUNTER + """
                async fnc bad(): void {
                  val mutex = SharedMutex.new(new Counter());
                  val alias = rt mut share mutex;
                  val guard = alias.lock();
                  rt cooperate;
                  guard.value = 2;
                  return;
                }
                """, "MutexGuard");
    }

    @Test
    void policyGateRejectsMutableSharingBeforeExecution() {
        var program = Parser.parse("""
                fnc bad(): void {
                  val alias = rt mut share handle;
                  return;
                }
                """);
        SecurityException denial = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denial.getMessage().contains("rt mut share"), denial.getMessage());
    }

    @Test
    void malformedMutableOperationsDoNotFallbackToOrdinaryExpression() {
        for (String expression : new String[] {
                "rt mut copy value", "rt mut take value", "rt borrow mut value",
                "rt share mut value", "rt mut share()", "rt mut share(value, value)"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    fnc bad(): void {
                      val value = 1;
                      val alias = %s;
                      return;
                    }
                    """.formatted(expression)), expression);
        }
    }
}
