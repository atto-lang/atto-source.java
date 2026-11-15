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
    void pointerStyleBorrowExpressionsAndTypesAreRejectedAtParsing() {
        for (String source : java.util.List.of(
                "pub fnc main(): void { let int value = 1; val loan = &value; return; }",
                "pub fnc main(): void { let int value = 1; val loan = &mut value; return; }",
                "pub fnc takes(&int loan): void { return; }",
                "pub fnc takes(&mut int loan): void { return; }")) {
            var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(source));
            assertTrue(failure.getMessage().contains("pointer"),
                    failure::getMessage);
        }
    }

    @Test
    void binaryMultiplicationAndBitwiseAndRemainOrdinaryOperators() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                  const int product = 2 * 3;
                  const int masked = 3 & 1;
                  return;
                }
                """));
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
    void castsCannotLaunderBorrowLifetimeIntoOwnedOrOptionalValues() {
        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  val same = loan as Box;
                  return;
                }
                """, "borrowed references", "cast", "loan");

        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  val maybe = loan as? Box;
                  return;
                }
                """, "borrowed references", "cast", "loan");
    }

    @Test
    void futurePayloadTypesCannotHideLexicalBorrows() {
        rejectsWith("""
                pub fnc bad(borrow Box view): Future<borrow Box> {
                  return;
                }
                """, "future", "borrow", "owned");

        rejectsWith("""
                pub fnc badNested(borrow Box view): Future<Option<borrow Box>> {
                  return;
                }
                """, "future", "borrow", "owned");
    }

    @Test
    void borrowedValuesCannotEraseLifetimeThroughCheckedOrOptionalCast() {
        for (String cast : new String[] {"as Box", "as? Box"}) {
            rejectsWith("""
                    pub fnc main(): void {
                      let Box owner = new Box();
                      val loan = rt borrow owner;
                      val erased = loan %s;
                      return;
                    }
                    """.formatted(cast),
                    "borrowed references cannot cross", "cast", "lifetime");
        }
    }

    @Test
    void ownedStorageBoundariesRejectBorrowValuesByProvenance() {
        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  val wrapped = Some(loan);
                  return;
                }
                """, "cannot store a borrow", "owned sum");

        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  val lock = Mutex.new(loan);
                  return;
                }
                """, "requires an owned value", "borrowed");

        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  val moved = rt take loan;
                  return;
                }
                """, "requires owned data", "borrow");
    }

    @Test
    void conditionalBorrowedSelfResultCannotLoseProvenance() {
        rejectsWith("""
                define class FluentBox as
                  pub val int value = 7;

                  pub same(): self {
                    return self;
                  }
                end

                fnc bad(borrow FluentBox box, bool flag): FluentBox {
                  return flag ? box.same() : box.same();
                }
                """, "owned return type", "borrow");

        rejectsWith("""
                define class FluentBox as
                  pub val int value = 7;

                  pub same(): self {
                    return self;
                  }
                end

                fnc bad(borrow FluentBox left, borrow FluentBox right, bool flag): void {
                  val FluentBox chosen = flag ? left.same() : right.same();
                  return;
                }
                """, "ambiguous lifetime provenance", "conditional expression");

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(BOX + """
                define class FluentBox as
                  pub val int value = 7;

                  pub same(): self {
                    return self;
                  }
                end

                fnc ok(borrow FluentBox box, bool flag): int {
                  val FluentBox chosen = flag ? box.same() : box.same();
                  return chosen.value;
                }
                """));
    }

    @Test
    void awaitCannotCreateFreshBorrowAtSuspensionExpression() {
        rejectsWith("""
                async fnc later(): int {
                  return 42;
                }

                async fnc bad(): int {
                  let Future<int> pending = later();
                  return await (rt borrow pending);
                }
                """, "cannot await a borrowed", "borrow", "future");
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
    void provenanceMarkedOwnedLookingAliasCannotCrossAwait() {
        rejectsWith("""
                define class FluentBox as
                  pub val int value = 7;

                  pub same(): self {
                    return self;
                  }
                end

                async fnc later(): int {
                  return 42;
                }

                async fnc bad(borrow FluentBox box): int {
                  val FluentBox alias = box.same();
                  val int answer = await later();
                  return alias.value + answer;
                }
                """, "borrow", "suspension", "await");
    }

    @Test
    void localBorrowCannotEscapeItsOwnerByReturn() {
        rejectsWith("""
                pub fnc leak(): borrow Box {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  return loan;
                }
                """, "cannot return borrow", "outlive", "owner");
    }

    @Test
    void liveSharedLoanBlocksExclusiveLoanAndOwnerMutation() {
        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val shared = rt borrow owner;
                  val exclusive = rt mut borrow owner;
                  return;
                }
                """, "mutably borrow", "another borrow", "borrow");

        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val shared = rt borrow owner;
                  owner.value = 2;
                  stdio.stdout.write(shared.value);
                  return;
                }
                """, "mutate", "borrow");
    }

    @Test
    void liveLoanBlocksOwnerMoveAndTeardownTransfer() {
        rejectsWith("""
                pub fnc main(): void {
                  let Box owner = new Box();
                  val loan = rt borrow owner;
                  let Box moved = rt take owner;
                  stdio.stdout.write(loan.value);
                  return;
                }
                """, "cannot move", "borrow");
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
    void actorSendCannotLaunderProvenanceWhenTypePassIsBypassed() {
        var program = Parser.parse("""
                define class Payload as
                  pub val int value = 7;

                  pub same(): self {
                    return self;
                  }
                end

                define actor Worker as
                  receive(ActorMail<Payload> mail): void {
                    return;
                  }
                end

                fnc bad(borrow Payload payload): void {
                  val worker = spawn Worker();
                  worker.send(payload.same());
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(failure.getMessage().contains("actor-capability")
                || failure.getMessage().contains("provenance-tracked"),
                failure::getMessage);
    }

    @Test
    void ownershipPassAloneRejectsBorrowBearingGenericSpecialization() {
        var inferred = Parser.parse(BOX + """
                fnc identity<T>(T value): T {
                  return value;
                }

                fnc bad(borrow Box value): void {
                  val escaped = identity(value);
                  return;
                }
                """);
        IllegalArgumentException inferredFailure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(inferred));
        assertTrue(inferredFailure.getMessage().contains("generic 'T'")
                && inferredFailure.getMessage().contains("borrow"),
                inferredFailure::getMessage);

        var explicit = Parser.parse(BOX + """
                fnc identity<T>(T value): T {
                  return value;
                }

                fnc bad(borrow Box value): void {
                  val escaped = identity<borrow Box>(value);
                  return;
                }
                """);
        IllegalArgumentException explicitFailure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(explicit));
        assertTrue(explicitFailure.getMessage().contains("generic 'T'")
                && explicitFailure.getMessage().contains("owned"),
                explicitFailure::getMessage);

        var nestedField = Parser.parse(BOX + """
                define class BadHolder as
                  pub val Option<borrow Box> value;
                end
                """);
        IllegalArgumentException fieldFailure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(nestedField));
        assertTrue(fieldFailure.getMessage().contains("field 'BadHolder.value'")
                && fieldFailure.getMessage().contains("borrow"),
                fieldFailure::getMessage);
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
    void leftAndRefinementDoesNotMoveSameNamedParentBinding() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(BOX + """
                pub async fnc main(): void {
                  let Box narrowed = new Box();
                  let Box subject = new Box();
                  let Future<int> child = task {
                    if subject is type Box narrowed && narrowed.value > 0 then
                      return narrowed.value;
                    fi
                    return 0;
                  };
                  stdio.stdout.write(narrowed.value);
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

    // Mutex<T> is confined to its creating actor/execution domain: the runtime
    // rejects any use of a moved Mutex in a task, so the checker rejects the move.
    @Test
    void mutexStaysInItsCreatingDomainEvenWhenUniquelyOwned() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
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
                  pub let Box lock;
                  constructor(Box lock) { self.lock = lock; }
                end
                pub async fnc main(): void {
                  let SendWrapper wrapper = new SendWrapper(new Box());
                  let Future<int> child = task {
                    let SendWrapper moved = wrapper;
                    return 1;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """));

        // The same wrapper holding an actor-local Mutex must not move.
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(BOX + """
                define class MutexWrapper as
                  pub let Mutex<Box> lock;
                  constructor(Mutex<Box> lock) { self.lock = lock; }
                end
                pub async fnc main(): void {
                  let MutexWrapper wrapper = new MutexWrapper(Mutex.new(new Box()));
                  let Future<int> child = task {
                    let MutexWrapper moved = wrapper;
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
        assertTrue((Boolean) gate.invoke(checker, one.apply("Mutex")),
                "Mutex<T> is confined to its creating actor/execution domain");
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
    void futureVoidRequiresExactUnshadowedEmptyPayload() throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var gate = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        gate.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());

        var exactVoid = dev.oreslang.ast.Ast.TypeRef.simple("void");
        var valid = new dev.oreslang.ast.Ast.TypeRef(
                "Future", java.util.List.of(exactVoid), false);
        assertFalse((Boolean) gate.invoke(checker, valid),
                "Future<void> is an allowed empty-result affine handle");

        var untrustedPayload = new dev.oreslang.ast.Ast.TypeRef(
                "void", java.util.List.of(
                        new dev.oreslang.ast.Ast.TypeRef("Rc",
                                java.util.List.of(
                                        dev.oreslang.ast.Ast.TypeRef.simple("int")),
                                false)), false);
        var malformed = new dev.oreslang.ast.Ast.TypeRef(
                "Future", java.util.List.of(untrustedPayload), false);
        assertTrue((Boolean) gate.invoke(checker, malformed),
                "void with nested generic payload is not an empty result");

        java.util.List<dev.oreslang.ast.Ast.ModuleDecl> modules =
                new java.util.ArrayList<>();
        for (var module : program.modules()) {
            java.util.List<dev.oreslang.ast.Ast.Decl> declarations =
                    new java.util.ArrayList<>(module.declarations());
            declarations.add(new dev.oreslang.ast.Ast.TypeAliasDecl(
                    "void", dev.oreslang.ast.Ast.Visibility.PRIVATE,
                    java.util.List.of(), exactVoid));
            modules.add(new dev.oreslang.ast.Ast.ModuleDecl(
                    module.name(), module.annotations(),
                    module.contracts(), declarations));
        }
        var aliased = new dev.oreslang.ast.Ast.Program(
                program.namespace(), program.imports(), modules);
        var aliasChecker = constructor.newInstance(
                aliased, java.util.Map.of());
        assertTrue((Boolean) gate.invoke(aliasChecker, valid),
                "a shadowed void name must not bypass Task Send checking");
    }

    @Test
    void fabricatedCompilerLiteralTypesCannotHideNonSendPayloads()
            throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var send = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        send.setAccessible(true);

        var rcInt = new dev.oreslang.ast.Ast.TypeRef(
                "Rc", java.util.List.of(
                        dev.oreslang.ast.Ast.TypeRef.simple("int")), false);
        assertFalse((Boolean) send.invoke(
                checker, dev.oreslang.ast.Ast.TypeRef.stringLiteral("safe")));
        assertFalse((Boolean) send.invoke(
                checker, dev.oreslang.ast.Ast.TypeRef.intLiteral("42")));
        assertFalse((Boolean) send.invoke(
                checker, dev.oreslang.ast.Ast.TypeRef.boolLiteral(true)));
        for (String syntheticName : java.util.List.of(
                "$string$hello", "$int$42", "$bool$true")) {
            var fabricated = new dev.oreslang.ast.Ast.TypeRef(
                    syntheticName, java.util.List.of(rcInt), false);
            assertTrue((Boolean) send.invoke(checker, fabricated),
                    "generic metadata must not launder Task Send: "
                            + syntheticName);
        }
    }

    @Test
    void nominalClassesCannotImpersonateCopySendBuiltinScalars()
            throws Exception {
        var parsed = Parser.parse("pub fnc main(): void { return; }");
        var rcInt = new dev.oreslang.ast.Ast.TypeRef(
                "Rc", java.util.List.of(
                        dev.oreslang.ast.Ast.TypeRef.simple("int")), false);
        var fakeField = new dev.oreslang.ast.Ast.FieldDecl(
                "payload", dev.oreslang.ast.Ast.Visibility.PUBLIC,
                dev.oreslang.ast.Ast.BindingKind.LET,
                rcInt, java.util.List.of(), null);
        var modules = new java.util.ArrayList<dev.oreslang.ast.Ast.ModuleDecl>();
        for (var module : parsed.modules()) {
            var declarations =
                    new java.util.ArrayList<dev.oreslang.ast.Ast.Decl>(
                            module.declarations());
            for (String builtinName : java.util.List.of(
                    "ActorId", "ActorKind", "Symbol", "String")) {
                declarations.add(new dev.oreslang.ast.Ast.ClassDecl(
                        builtinName, dev.oreslang.ast.Ast.Visibility.PUBLIC,
                        false, dev.oreslang.ast.Ast.ActorKind.NONE,
                        java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(fakeField),
                        null, java.util.List.of()));
            }
            modules.add(new dev.oreslang.ast.Ast.ModuleDecl(
                    module.name(), module.annotations(),
                    module.contracts(), declarations));
        }
        var program = new dev.oreslang.ast.Ast.Program(
                parsed.namespace(), parsed.imports(), modules);
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var copy = OwnershipChecker.class.getDeclaredMethod(
                "isCopyType", dev.oreslang.ast.Ast.TypeRef.class);
        copy.setAccessible(true);
        var send = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        send.setAccessible(true);
        for (String name : java.util.List.of(
                "ActorId", "ActorKind", "Symbol", "String")) {
            var claimedBuiltin = dev.oreslang.ast.Ast.TypeRef.simple(name);
            assertFalse((Boolean) copy.invoke(checker, claimedBuiltin),
                    "a same-named user class must not inherit Copy: " + name);
            assertTrue((Boolean) send.invoke(checker, claimedBuiltin),
                    "a same-named user class must not inherit Send: " + name);
        }
        assertTrue((Boolean) copy.invoke(
                checker, dev.oreslang.ast.Ast.TypeRef.simple("int")));
        assertFalse((Boolean) send.invoke(
                checker, dev.oreslang.ast.Ast.TypeRef.simple("int")));
    }

    @Test
    void malformedScalarAndEmptyUnionDoNotCreateOwnershipAuthority()
            throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var copy = OwnershipChecker.class.getDeclaredMethod(
                "isCopyType", dev.oreslang.ast.Ast.TypeRef.class);
        copy.setAccessible(true);
        var send = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        send.setAccessible(true);

        var scalar = dev.oreslang.ast.Ast.TypeRef.simple("int");
        assertTrue((Boolean) copy.invoke(checker, scalar));
        assertFalse((Boolean) send.invoke(checker, scalar));

        var fabricatedScalar = new dev.oreslang.ast.Ast.TypeRef(
                "int", java.util.List.of(
                        new dev.oreslang.ast.Ast.TypeRef("Rc",
                                java.util.List.of(scalar), false)), false);
        var fabricatedUnion = new dev.oreslang.ast.Ast.TypeRef(
                "$union$", java.util.List.of(), false);
        var unresolvedPrimitive = new dev.oreslang.ast.Ast.TypeRef(
                "int", java.util.List.of(), true);
        var shape = dev.oreslang.ast.Ast.TypeRef.sequenceShape(
                java.util.List.of(scalar));
        var repeated = dev.oreslang.ast.Ast.TypeRef.repeatExact("2", scalar);
        var namedParameter = dev.oreslang.ast.Ast.TypeRef.namedParameter(
                "value", scalar);
        for (var candidate : java.util.List.of(
                fabricatedScalar, fabricatedUnion,
                unresolvedPrimitive, shape, repeated, namedParameter)) {
            assertFalse((Boolean) copy.invoke(checker, candidate),
                    "malformed/unresolved type must not gain Copy authority");
            assertTrue((Boolean) send.invoke(checker, candidate),
                    "malformed/unresolved type must not gain Task Send authority");
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

    @Test
    void independentTaskSharesOneSendProofBudgetAcrossAllCaptures()
            throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var checkerConstructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        checkerConstructor.setAccessible(true);
        var checker = checkerConstructor.newInstance(program, java.util.Map.of());

        // Exercise the exact internal per-task proof budget used by the
        // real TaskExpr path. It must NOT be reset for each captured type.
        Class<?> budgetClass = java.util.Arrays.stream(
                        OwnershipChecker.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("TaskCaptureProofBudget"))
                .findFirst().orElseThrow();
        var budgetConstructor = budgetClass.getDeclaredConstructor();
        budgetConstructor.setAccessible(true);
        var budget = budgetConstructor.newInstance();
        var exhausted = budgetClass.getDeclaredField("exhausted");
        exhausted.setAccessible(true);
        var forbidden = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability",
                dev.oreslang.ast.Ast.TypeRef.class, budgetClass);
        forbidden.setAccessible(true);
        assertFalse(exhausted.getBoolean(budget));

        var scalar = dev.oreslang.ast.Ast.TypeRef.simple("int");
        for (int i = 0; i < 4096; i++) {
            assertFalse((Boolean) forbidden.invoke(checker, scalar, budget),
                    "simple scalar remains Send through the shared visit budget: " + i);
        }
        assertFalse(exhausted.getBoolean(budget),
                "successful proof visits must not report exhaustion");
        assertTrue((Boolean) forbidden.invoke(checker, scalar, budget),
                "the next independent capture must fail closed after exhaustion");
        assertTrue(exhausted.getBoolean(budget),
                "the compiler must distinguish exhausted proof from non-Send");

        // A fresh Task gets its own proof budget; another Task's exhausted
        // traversal must never mutate global Send/Sync/Share decisions.
        var fresh = budgetConstructor.newInstance();
        assertFalse((Boolean) forbidden.invoke(checker, scalar, fresh));
        assertFalse(exhausted.getBoolean(fresh));
    }

    @Test
    void independentlyScheduledTaskCaptureProofHasGlobalTypeRefDepthAndNodeBudget()
            throws Exception {
        var program = Parser.parse("pub fnc main(): void { return; }");
        var constructor = OwnershipChecker.class.getDeclaredConstructor(
                dev.oreslang.ast.Ast.Program.class, java.util.Map.class);
        constructor.setAccessible(true);
        var checker = constructor.newInstance(program, java.util.Map.of());
        var forbidden = OwnershipChecker.class.getDeclaredMethod(
                "forbiddenTaskCapability", dev.oreslang.ast.Ast.TypeRef.class);
        forbidden.setAccessible(true);

        var intType = dev.oreslang.ast.Ast.TypeRef.simple("int");
        assertFalse((Boolean) forbidden.invoke(checker, intType),
                "ordinary int remains Send for capture");

        // This is not a deeply recursive Java object constructor: the
        // compiler's TypeRef traversal itself must reject without overflowing.
        var deeplyNested = intType;
        for (int i = 0; i < 10_000; i++) {
            deeplyNested = new dev.oreslang.ast.Ast.TypeRef(
                    "Option", java.util.List.of(deeplyNested), false);
        }
        assertTrue((Boolean) forbidden.invoke(checker, deeplyNested),
                "10,000 nested Option wrappers cannot exhaust the compiler stack");

        var ordinary = intType;
        for (int i = 0; i < 16; i++) {
            ordinary = new dev.oreslang.ast.Ast.TypeRef(
                    "Option", java.util.List.of(ordinary), false);
        }
        assertFalse((Boolean) forbidden.invoke(checker, ordinary),
                "ordinary nested Send-safe capture is not needlessly rejected");

        var wide = dev.oreslang.ast.Ast.TypeRef.tupleType(
                java.util.Collections.nCopies(5000, intType));
        assertTrue((Boolean) forbidden.invoke(checker, wide),
                "nominal and tuple children must share one proof-visit budget");
        var small = dev.oreslang.ast.Ast.TypeRef.tupleType(
                java.util.Collections.nCopies(16, intType));
        assertFalse((Boolean) forbidden.invoke(checker, small),
                "small fully checked tuple is Send");
    }
}
