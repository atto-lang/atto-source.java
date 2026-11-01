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
    void chainedMutableSharesPreserveGuardedAccess() {
        checked(COUNTER + """
                fnc ok(): void {
                  val original = SharedMutex.new(new Counter());
                  val first = rt mut share original;
                  val second = rt mut share first;
                  val guard = second.lock();
                  guard.value = 5;
                  guard.release();
                  return;
                }
                """);
    }

    @Test
    void parenthesizedBorrowCannotBeUpgradedToMutableShare() {
        rejected("""
                fnc bad(): void {
                  val mutex = SharedMutex.new(7);
                  val alias = rt mut share (rt borrow mutex);
                  return;
                }
                """, "rt mut share requires");
    }

    @Test
    void rejectsMutexPayloadWrappedInSharedMutex() {
        rejected("""
                fnc bad(): void {
                  val local = Mutex.new(1);
                  val shared = SharedMutex.new(local);
                  val alias = rt mut share shared;
                  return;
                }
                """, "SharedMutex<T> requires shared-safe owned data");
    }

    @Test
    void releasesGuardBeforeReacquiringThroughOriginalOwner() {
        checked(COUNTER + """
                fnc good(): void {
                  val owner = SharedMutex.new(new Counter());
                  val alias = rt mut share owner;
                  val first = alias.lock();
                  first.value = 4;
                  first.release();
                  val next = owner.lock();
                  next.value = 5;
                  next.release();
                  return;
                }
                """);
    }

    @Test
    void cannotReturnMutexGuardFromItsLexicalScope() {
        rejected(COUNTER + """
                fnc bad(): MutexGuard<Counter> {
                  val mutex = SharedMutex.new(new Counter());
                  val guard = mutex.lock();
                  return guard;
                }
                """, "MutexGuard");
    }

    @Test
    void cannotPlaceBorrowedPayloadInsideSharedMutex() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val payload = new Counter();
                  val loan = rt borrow payload;
                  val mutex = SharedMutex.new(loan);
                  return;
                }
                """, "SharedMutex<T> requires owned data");
    }

    @Test
    void cannotPlaceUnresolvedGenericPayloadInsideSharedMutex() {
        rejected("""
                fnc bad<T>(T payload): void {
                  val mutex = SharedMutex.new(payload);
                  return;
                }
                """, "SharedMutex<T> requires shared-safe owned data");
    }

    @Test
    void developerPolicyAdmitsGuardedMutableShareOperation() {
        Ast.Program program = Parser.parse("""
                fnc ok(): void {
                  val alias = rt mut share handle;
                  return;
                }
                """);
        assertDoesNotThrow(() -> CapabilityChecker.check(program, IsolatePolicy.developer()));
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

    @Test
    void parenthesizedOwnedMutexHandleRemainsGuarded() {
        checked(COUNTER + """
                fnc good(): void {
                  val owner = SharedMutex.new(new Counter());
                  val alias = rt mut share (owner);
                  val guard = alias.lock();
                  guard.value = 11;
                  guard.release();
                  return;
                }
                """);
    }

    @Test
    void independentMutexOwnersCanBeGuardedSeparately() {
        checked(COUNTER + """
                fnc good(): void {
                  val left = SharedMutex.new(new Counter());
                  val right = SharedMutex.new(new Counter());
                  val a = rt mut share left;
                  val b = rt mut share right;
                  val first = a.lock();
                  first.value = 10;
                  first.release();
                  val second = b.lock();
                  second.value = 20;
                  second.release();
                  return;
                }
                """);
    }

    @Test
    void unreleasedGuardCannotBeUsedToExposeUnguardedFields() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val owner = SharedMutex.new(new Counter());
                  val alias = rt mut share owner;
                  val guard = alias.lock();
                  alias.value = 123;
                  guard.release();
                  return;
                }
                """, "value");
    }

    @Test
    void canonicalMutableBorrowSpellingIsAcceptedButLegacySpellingRejected() {
        checked(COUNTER + """
                fnc good(): void {
                  val owner = new Counter();
                  val loan = rt mut borrow owner;
                  loan.value = 3;
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(COUNTER + """
                fnc bad(): void {
                  val owner = new Counter();
                  val loan = rt borrow mut owner;
                  return;
                }
                """));
    }

    @Test
    void ordinaryShareOfCopyableMutexHandleCanStillBeGuarded() {
        checked(COUNTER + """
                fnc good(): void {
                  val owner = SharedMutex.new(new Counter());
                  val copied = rt share owner;
                  val guarded = rt mut share copied;
                  val lock = guarded.lock();
                  lock.value = 12;
                  lock.release();
                  return;
                }
                """);
    }

    @Test
    void exclusiveBorrowOfSharedMutexHandleCannotBeUpgradedToSharedMutation() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val owner = SharedMutex.new(new Counter());
                  val loan = rt mut borrow owner;
                  val alias = rt mut share loan;
                  return;
                }
                """, "rt mut share requires");
    }

    @Test
    void nestedOrdinarySharingCannotLaunderBorrowedHandle() {
        assertThrows(IllegalArgumentException.class, () ->
                OwnershipChecker.check(TypeChecker.check(Parser.parse(COUNTER + """
                        fnc bad(): void {
                          val owner = SharedMutex.new(new Counter());
                          val loan = rt borrow owner;
                          val alias = rt mut share (rt share loan);
                          return;
                        }
                        """))));
    }

    @Test
    void nestedGuardedSharingCannotBypassStaticIsolatePermission() {
        Ast.Program program = Parser.parse("""
                fnc bad(): void {
                  val alias = rt share (rt mut share handle);
                  return;
                }
                """);
        SecurityException denial = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denial.getMessage().contains("rt mut share"), denial.getMessage());
    }

    @Test
    void nestedSynchronizedHandleExpressionsRetainTheOriginalAllocation() throws Exception {
        String program = COUNTER + """
                pub routine main(): void {
                  val owner = SharedMutex.new(new Counter());
                  val alias = rt mut share (rt mut share owner);
                  val guard = alias.lock();
                  guard.value = 17;
                  guard.release();
                  val observed = owner.lock();
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
            context.eval(Source.newBuilder("ores", program, "rt-mut-share-nested.ores").build());
        }
        assertEquals("17", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void inlineExclusiveBorrowCannotBeUpgradedToGuardedSharedHandle() {
        rejected(COUNTER + """
                fnc bad(): void {
                  val owner = SharedMutex.new(new Counter());
                  val alias = rt mut share (rt mut borrow owner);
                  return;
                }
                """, "rt mut share requires");
    }

    @Test
    void nestedFunctionArgumentCannotBypassSharedMemoryCapability() {
        Ast.Program program = Parser.parse("""
                fnc consume(int value): void { return; }
                fnc bad(): void {
                  consume(rt mut share handle);
                  return;
                }
                """);
        SecurityException denial = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denial.getMessage().contains("rt mut share"), denial.getMessage());
    }

    @Test
    void sharedMemoryAdmissionRejectsBeforeEvaluatingRestrictedOperand() {
        Ast.Program program = Parser.parse("""
                fnc bad(): void {
                  val alias = rt mut share (process.exit(1));
                  return;
                }
                """);
        SecurityException denial = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denial.getMessage().contains("rt mut share"), denial.getMessage());
    }
}
