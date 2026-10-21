package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent-Task capture admission is a spawn-time ownership boundary,
 * even if the caller never awaits the returned Future.
 *
 * Scoped immutable READ capability is a separately proven feature; this
 * v2 branch must never accidentally admit ordinary borrowed captures.
 */
final class TaskBorrowBoundaryV2Test {
    @Test
    void canonicalTaskBorrowFixturesUseRtOwnershipSyntax() {
        // Exercise the actual source parser. Scanning this test file for a
        // forbidden spelling would match its own negative-test fixtures and
        // fail regardless of compiler correctness.
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val shared = rt borrow owner;
                  val exclusive = rt mut borrow owner;
                  return;
                }
                """));
    }

    private static final String BOX = """
            define class Box as
              pub let int value = 1;
            end
            """;

    private static void rejectsWith(String program, String... fragments) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(BOX + program));
        String message = failure.getMessage().toLowerCase();
        boolean matched = false;
        for (String fragment : fragments) {
            matched |= message.contains(fragment.toLowerCase());
        }
        assertTrue(matched, () -> "missing ownership diagnostic: " + failure.getMessage());
    }

    private static void rejectsRawWith(String program, String... fragments) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        String message = failure.getMessage().toLowerCase();
        boolean matched = false;
        for (String fragment : fragments) {
            matched |= message.contains(fragment.toLowerCase());
        }
        assertTrue(matched, () -> "missing ownership diagnostic: " + failure.getMessage());
    }

    @Test
    void legacyMutableBorrowOrderingIsRejected() {
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main(): void {
                          let Box owner = new Box();
                          val loan = rt borrow mut owner;
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("rt mut borrow"),
                failure::getMessage);
    }

    @Test
    void ordinaryImmutableBorrowCannotEscapeIntoEagerTask() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  let Future<int> child = task { return loan.value; };
                  await child;
                  return;
                }
                """, "borrow", "deferred task");
    }

    @Test
    void mutableBorrowCannotEscapeIntoCpuTask() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt mut borrow owner;
                  let Future<int> child = cpu task { return loan.value; };
                  await child;
                  return;
                }
                """, "borrow", "mutable");
    }

    @Test
    void outstandingParentLoanCannotCrossAwait() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  let Future<int> child = task { return 42; };
                  await child;
                  stdio.stdout.write(loan.value);
                  return;
                }
                """, "borrow", "suspension");
    }

    @Test
    void sameAffineOwnerCannotBeCapturedTwice() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  let Future<int> first = cpu task { return owner.value; };
                  let Future<int> second = task { return owner.value; };
                  await first;
                  await second;
                  return;
                }
                """, "moved", "capture");
    }

    @Test
    void independentTaskCannotInheritMailboxSelfEvenIfTypePassIsBypassed() {
        var program = Parser.parse("""
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    let Future<void> child = task {
                      self.end();
                      return;
                    };
                    return;
                  }
                end
                """);
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(failure.getMessage().contains("independent task cannot capture actor self"),
                failure::getMessage);
    }

    @Test
    void classicForBindingInitializerCannotHideBorrowedTaskCapture() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  let Future<int> child = task {
                    for (val loopLoan = loan; false; ) {
                      return loopLoan.value;
                    }
                    return 0;
                  };
                  await child;
                  return;
                }
                """, "borrow", "deferred task");
    }

    @Test
    void classicForBindingInitializerMovesOuterOwnerAtTaskSubmission() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  let Future<int> child = task {
                    for (val Box loopOwner = owner; false; ) {
                      return loopOwner.value;
                    }
                    return 0;
                  };
                  stdio.stdout.write(owner.value);
                  await child;
                  return;
                }
                """, "moved", "owner", "capture");
    }

    @Test
    void classicForBindingShadowsOuterOwnerWithoutCapturingIt() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(BOX + """
                pub async fnc main(): void {
                  let Box owner = new Box();
                  let Future<int> child = task {
                    for (val Box owner = new Box(); false; ) {
                      stdio.stdout.write(owner.value);
                    }
                    return 7;
                  };
                  stdio.stdout.write(owner.value);
                  stdio.stdout.write(await child);
                  return;
                }
                """));
    }

    @Test
    void affineFutureCanMoveIntoTaskButCannotBeUsedTwice() {
        // Futures are transferable owned handles, not copyable capabilities.
        // A child may consume the Future while the parent loses that binding.
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> first = task { return 41; };
                  let Future<int> second = task { return (await first) + 1; };
                  stdio.stdout.write(await second);
                  return;
                }
                """));

        rejectsWith("""
                pub async fnc main(): void {
                  let Future<int> first = task { return 41; };
                  let Future<int> second = task { return await first; };
                  stdio.stdout.write(await first);
                  stdio.stdout.write(await second);
                  return;
                }
                """, "moved", "capture", "used");
    }

    @Test
    void uniquelyOwnedMutexMayMoveWhenPayloadIsSend() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Mutex<int> mutex = Mutex.new(41);
                  let Future<void> child = task {
                    val grabbed = mutex;
                    return;
                  };
                  await child;
                  return;
                }
                """));
    }

    @Test
    void rcAndUnconstrainedGenericCapturesFailSendProof() {
        rejectsRawWith("""
                pub fnc moveRc(Rc<int> owner): Future<void> {
                  let Future<void> child = task {
                    val grabbed = owner;
                    return;
                  };
                  return child;
                }
                """, "non-Send", "capability");

        rejectsRawWith("""
                pub fnc moveGeneric<T>(T owner): Future<void> {
                  let Future<void> child = task {
                    val grabbed = owner;
                    return;
                  };
                  return child;
                }
                """, "non-Send", "capability");

        rejectsRawWith("""
                define class Envelope<T> as
                  pub let T value;
                end
                pub fnc moveEnvelope(Envelope<Rc<int>> owner): Future<void> {
                  let Future<void> child = task {
                    val grabbed = owner;
                    return;
                  };
                  return child;
                }
                """, "non-Send", "capability");
    }

    @Test
    void valueMatchArmCaptureMovesOuterOwnerAtSubmission() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  let Future<int> child = task {
                    return match 1 over {
                      on 1 { return owner.value; }
                      on _ { return 0; }
                    };
                  };
                  stdio.stdout.write(owner.value);
                  stdio.stdout.write(await child);
                  return;
                }
                """, "moved", "capture");
    }

    @Test
    void sendSafeCapabilityMayMoveThroughOwnedClass() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(BOX + """
                define class SendWrapper as
                  pub let Mutex<Box> lock = Mutex.new(new Box());
                end
                pub async fnc main(): void {
                  let SendWrapper wrapper = new SendWrapper();
                  let Future<int> child = task {
                    let SendWrapper moved = wrapper;
                    return 1;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """));
    }

    @Test
    void nestedLoanInOwnedContainerCannotLaunderTaskSendProof() throws Exception {
        // Exercise the compiler's recursive Send backstop directly. Source
        // construction is irrelevant to this property: any frontend may
        // elaborate a borrowed value inside Option/Future/Channel/Result.
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class,
                java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);

        var loan = dev.oreslang.ast.Ast.TypeRef.borrowed(
                dev.oreslang.ast.Ast.TypeRef.simple("int"), false);
        for (String outer : new String[] {"Option", "Future", "Channel"}) {
            var container = new dev.oreslang.ast.Ast.TypeRef(
                    outer, java.util.List.of(loan), false);
            assertTrue((Boolean) gate.invoke(checker, container),
                    () -> outer + " must not launder a nested Task loan");
        }
        var safe = new dev.oreslang.ast.Ast.TypeRef(
                "Option", java.util.List.of(
                        dev.oreslang.ast.Ast.TypeRef.simple("int")), false);
        assertFalse((Boolean) gate.invoke(checker, safe),
                "owned scalar Option remains Send-safe");
    }

    @Test
    void canonicalAutoTraitsDriveTaskSendAdmission() throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class,
                java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);

        java.util.function.Function<String, dev.oreslang.ast.Ast.TypeRef> one =
                name -> new dev.oreslang.ast.Ast.TypeRef(
                        name,
                        java.util.List.of(
                                dev.oreslang.ast.Ast.TypeRef.simple("int")),
                        false);

        assertFalse((Boolean) gate.invoke(checker, one.apply("Box")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("RefCell")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("Mutex")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("RwLock")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("Arc")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("Weak")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("Future")));
        assertFalse((Boolean) gate.invoke(checker, one.apply("Channel")));

        assertTrue((Boolean) gate.invoke(checker, one.apply("Rc")),
                "Rc<T> is not Send across independently scheduled Tasks");

        var refCellInt = one.apply("RefCell");
        var arcRefCell = new dev.oreslang.ast.Ast.TypeRef(
                "Arc", java.util.List.of(refCellInt), false);
        assertTrue((Boolean) gate.invoke(checker, arcRefCell),
                "Arc<T> requires T to be Sync as well as Send");

        var rcInt = one.apply("Rc");
        for (String outer : new String[] {"Box", "Future", "Channel"}) {
            var wrapped = new dev.oreslang.ast.Ast.TypeRef(
                    outer, java.util.List.of(rcInt), false);
            assertTrue((Boolean) gate.invoke(checker, wrapped),
                    () -> outer + " must propagate nested non-Send Rc");
        }
    }

    @Test
    void recursiveNominalGenericCaptureCannotHideNestedNonSendAuthority() throws Exception {
        // A declaration-identity-only recursion short-circuit is unsound:
        // Recursive<T> can recur as Recursive<Rc<T>> with stronger restrictions.
        // Until nominal instances have a complete coinductive Send proof,
        // both recursive and changing-generic cycles must fail closed.
        var program = Parser.parse("""
                define class Recursive<T> as
                  pub let Recursive<Rc<T>> next;
                end
                pub fnc main(): void { return; }
                """);
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);

        var candidate = new dev.oreslang.ast.Ast.TypeRef(
                "Recursive", java.util.List.of(
                        dev.oreslang.ast.Ast.TypeRef.simple("int")), false);
        assertTrue((Boolean) gate.invoke(checker, candidate),
                "recursion cannot be treated as an automatically Send-safe capture");
    }

    @Test
    void unknownNominalTaskCaptureCannotAssumeSendWithoutLinkedLayout() throws Exception {
        // The missing class definition is NOT proof that a host/external
        // object is transferable. Only verified owned types can move across
        // independently scheduled Tasks.
        var program = Parser.parse("""
                define actor Worker as
                  receive(ActorMail<int> mail): void { return; }
                end
                pub fnc main(): void { return; }
                """);
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class,
                java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);

        var unknown = dev.oreslang.ast.Ast.TypeRef.simple("ForeignRuntimeHandle");
        assertTrue((Boolean) gate.invoke(checker, unknown),
                "unlinked nominal has no Send proof");
        assertTrue((Boolean) gate.invoke(checker,
                new dev.oreslang.ast.Ast.TypeRef(
                        "Option", java.util.List.of(unknown), false)),
                "generic wrappers must propagate missing Send proof");
        assertFalse((Boolean) gate.invoke(checker,
                dev.oreslang.ast.Ast.TypeRef.simple("int")),
                "primitive Copy/Send capture should remain supported");
        assertFalse((Boolean) gate.invoke(checker,
                new dev.oreslang.ast.Ast.TypeRef(
                        "ActorRef",
                        java.util.List.of(dev.oreslang.ast.Ast.TypeRef.simple("Worker")),
                        false)),
                "ActorRef protocol name does not embed an actor instance");
    }

    @Test
    void builtinMailboxCapabilityCannotBeForgedByNominalName() throws Exception {
        var program = Parser.parse("""
                define class ActorRef<T> as
                  pub let Rc<int> unsafePayload;
                end
                pub fnc main(): void { return; }
                """);
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);
        var claimedMailbox = new dev.oreslang.ast.Ast.TypeRef(
                "ActorRef", java.util.List.of(
                        dev.oreslang.ast.Ast.TypeRef.simple("Worker")), false);
        assertTrue((Boolean) gate.invoke(checker, claimedMailbox),
                "same-named user classes must not inherit ActorRef Send authority");
    }

    @Test
    void actorRefWithUnprovenTargetDoesNotGainTaskSendAuthority() throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);
        var unverified = new dev.oreslang.ast.Ast.TypeRef(
                "ActorRef", java.util.List.of(
                        dev.oreslang.ast.Ast.TypeRef.simple("UndeclaredActor")), false);
        assertTrue((Boolean) gate.invoke(checker, unverified),
                "ActorRef<T> requires proven actor target metadata");
    }

    @Test
    void canonicalRtCopyAndRtShareScalarsRemainSafeInTasks() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int original = 40;
                  const int copied = rt copy original;
                  const int shared = rt share original;
                  let Future<int> child = task {
                    return copied + shared - original + 2;
                  };
                  stdio.stdout.write(await child);
                  stdio.stdout.write(original);
                  return;
                }
                """));
    }

    @Test
    void canonicalRtTakeTransfersOwnedValueExactlyOnce() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box original = new Box();
                  let Box transferred = rt take original;
                  let Future<int> child = task {
                    return transferred.value;
                  };
                  stdio.stdout.write(original.value);
                  stdio.stdout.write(await child);
                  return;
                }
                """, "moved", "rt take");
    }

    @Test
    void immutableCopyValueMayBeCapturedWithoutMovingParent() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(value);
                  stdio.stdout.write(await child);
                  return;
                }
                """));
    }
}
