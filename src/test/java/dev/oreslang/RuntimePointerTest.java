package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimePointerTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;

    private void check(String body) {
        TypeChecker.check(Parser.parse(BOX + " fnc test(): void { " + body + " return; }"));
    }

    private void reject(String body, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> check(body));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test void runtimeHandlesCannotNestOrContainNull() {
        Object pointee = new Object();
        var pointer = new dev.oreslang.runtime.OresPointer(pointee);
        // Dereference borrows the existing object: it does not clone the pointee.
        assertSame(pointee, pointer.target());
        assertSame(pointer.target(), pointer.target());
        assertThrows(NullPointerException.class,
                () -> new dev.oreslang.runtime.OresPointer(null));
        var nested = assertThrows(IllegalArgumentException.class,
                () -> new dev.oreslang.runtime.OresPointer(pointer));
        assertTrue(nested.getMessage().contains("nested Pointer<T>"), nested.getMessage());
    }

    @Test void hostCannotManufacturePointersToCopyScalars() {
        for (Object scalar : java.util.List.of(
                42, "text", true, java.util.UUID.fromString("00000000-0000-0000-0000-000000000042"),
                java.math.BigInteger.valueOf(1000))) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new dev.oreslang.runtime.OresPointer(scalar));
            assertTrue(error.getMessage().contains("scalar/Copy"), error.getMessage());
        }
    }

    @Test void hostCannotManufacturePointerOwnershipForRuntimeCapabilities() {
        dev.oreslang.runtime.ActorRuntime.ForceCancellationHook forceCancel = request -> true;
        AutoCloseable resource = () -> { };
        for (Object capability : java.util.List.of(
                new dev.oreslang.runtime.ChannelRuntime.Channel<>(1),
                dev.oreslang.runtime.OresMutex.local(7),
                dev.oreslang.runtime.OresMutex.shared(7),
                dev.oreslang.runtime.ChannelRuntime.SelectPlan.of(
                        dev.oreslang.runtime.ChannelRuntime.defaultCase()),
                dev.oreslang.runtime.ActorRuntime.ControlDispatcher.unavailable(),
                forceCancel,
                dev.oreslang.runtime.AsyncRuntime.TurnExecutor.direct(),
                resource,
                new java.util.concurrent.CompletableFuture<Integer>())) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new dev.oreslang.runtime.OresPointer(capability));
            assertTrue(error.getMessage().contains("cannot own"), error.getMessage());
        }
    }

    @Test void rawPointerEscapeRequiresNativeTrustedLowering() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(BOX + "fnc bad(): void { const p = rt raw value; return; }"));
        assertTrue(error.getMessage().contains("trusted unsafe.memory"), error.getMessage());
        assertTrue(error.getMessage().contains("managed Java backend"), error.getMessage());
    }

    @Test void invalidExclusivePointerTransferFormsAreRejectedBeforeTypeChecking() {
        for (String operation : new String[] {"ref", "unref"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(BOX + "fnc bad(): void { const p = rt " + operation
                            + " mut value; return; }"));
            assertTrue(error.getMessage().contains("not a valid Pointer<T> operation"), error.getMessage());
        }
    }

    @Test void mutableDereferenceIsExclusiveAndZeroCopy() throws Exception {
        String code = BOX + """
                pub routine main(): void {
                  let mut Pointer<Box> p = rt ref new Box();
                  block {
                    val borrow mut Box view = rt deref mut p;
                    view.value = 9;
                    stdio.stdout.write(view.value);
                  }
                  const Box owned = rt unref p;
                  stdio.stdout.write(owned.value);
                  return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
            context.eval("ores", code);
        }
        // The mutation is observed after unref, proving deref mut viewed the
        // same pointee rather than a copied/writeback temporary.
        assertEquals("99", out.toString(StandardCharsets.UTF_8));
    }

    @Test void mutableDereferenceRequiresMutablePointerAndExcludesConflictingLoans() {
        reject("const p = rt ref new Box(); val borrow mut Box view = rt deref mut p;",
                "immutable owner");

        reject("let mut p = rt ref new Box(); "
                        + "const Box read = rt deref p; "
                        + "val borrow mut Box write = rt deref mut p;",
                "another borrow is active");

        reject("let mut p = rt ref new Box(); "
                        + "val borrow mut Box write = rt deref mut p; "
                        + "const Box read = rt deref p;",
                "mutably borrowed");

        reject("let mut p = rt ref new Box(); "
                        + "val borrow mut Box write = rt deref mut p; "
                        + "const Box owned = rt unref p;",
                "borrow");

        reject("let mut p = rt ref new Box(); "
                        + "val borrow mut Box write = rt deref mut p; "
                        + "rt cooperate;",
                "borrow is live");
    }

    @Test void mutableDereferenceCanSatisfyMutableBorrowedCallForCallDuration() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc edit(borrow mut Box value): void {
                  value.value = 12;
                  return;
                }
                fnc ok(): void {
                  let mut Pointer<Box> p = rt ref new Box();
                  edit(rt deref mut p);
                  const Box owned = rt unref p;
                  return;
                }
                """)));

        var immutableFailure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(BOX + """
                        fnc edit(borrow mut Box value): void {
                          value.value = 12;
                          return;
                        }
                        fnc bad(): void {
                          let mut Pointer<Box> p = rt ref new Box();
                          edit(rt deref p);
                          return;
                        }
                        """)));
        assertTrue(immutableFailure.getMessage().contains("read-only borrow"),
                immutableFailure.getMessage());

        var callLoanFailure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(BOX + """
                        fnc consume(borrow Box view, Box owned): void { return; }
                        fnc bad(): void {
                          let mut Pointer<Box> p = rt ref new Box();
                          consume(rt deref mut p, rt unref p);
                          return;
                        }
                        """)));
        assertTrue(callLoanFailure.getMessage().contains("borrow"),
                callLoanFailure.getMessage());
    }

    @Test void genericChannelCannotTransportPointerWithoutDomainProof() {
        String code = BOX + """
                pub routine main(): void {
                  val Channel<Pointer<Box>> messages = Channel.new<Pointer<Box>>(1);
                  const p = rt ref new Box();
                  writech messages, rt take p;
                  return;
                }
                """;
        var failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(code)));
        assertTrue(failure.getMessage().contains("domain-bound local channel"),
                failure.getMessage());

        var runtimeChannel =
                new dev.oreslang.runtime.ChannelRuntime.Channel<dev.oreslang.runtime.OresPointer>(1);
        var runtimePointer = new dev.oreslang.runtime.OresPointer(new Object());
        var runtimeFailure = assertThrows(IllegalArgumentException.class,
                () -> runtimeChannel.tryWrite(runtimePointer));
        assertTrue(runtimeFailure.getMessage().contains("domain-bound local channel"),
                runtimeFailure.getMessage());
    }

    @Test void namedAndFreshTargetsHaveCheckedRoundTrips() {
        for (String access : new String[] {"deref"}) {
            check("const Pointer<Box> p = rt ref new Box(); const Box view = rt " + access + " p; stdio.println(view.value);");
            check("val Box box = new Box(); const Pointer<Box> p = rt ref(box); const Box view = rt " + access + "(p); stdio.println(view.value);");
            check("const p = rt ref struct{value: int}{value: 7}; const view = rt " + access + " p; stdio.println(view.value);");
            check("const Pointer<{}> p = rt ref struct{}{}; const {} view = rt " + access + " p;");
        }
    }

    @Test void referencesRetainFreshValuesInBothEvaluatorPaths() throws Exception {
        for (String callable : new String[] {"routine", "async fnc"}) {
            String code = BOX + """
                    pub %s main(): void {
                      const Pointer<Box> p = rt ref new Box();
                      process.gc();
                      const Box first = rt deref p;
                      const Box second = rt deref(p);
                      stdio.stdout.write(first.value + second.value);
                      const q = rt ref struct{value: int}{value: 11};
                      const record = rt unref q;
                      stdio.stdout.write(record.value);
                      return;
                    }
                    """.formatted(callable);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
                context.eval("ores", code);
            }
            assertEquals("1411", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test void refRejectsRuntimeCapabilitiesHiddenInsideNominalObjects() {
        String nestedResource = """
                define class ResourceHolder as
                  pub let Channel<int> messages = Channel.new<int>(1);
                end
                fnc bad(): void {
                  const holder = new ResourceHolder();
                  const p = rt ref holder;
                  return;
                }
                """;
        var resourceFailure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(nestedResource)));
        assertTrue(resourceFailure.getMessage().contains("nested runtime/resource"),
                resourceFailure.getMessage());

        String ordinaryNestedData = BOX + """
                define class Outer as
                  pub let Box child = new Box();
                end
                fnc ok(): void {
                  const outer = new Outer();
                  const p = rt ref outer;
                  const view = rt deref p;
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(ordinaryNestedData)));
    }

    @Test void refTransfersOwnershipAndCannotUpgradeBorrowing() {
        reject("val Box box = new Box(); const p = rt ref box; stdio.println(box.value);", "moved");
        reject("val Box box = new Box(); const view = rt borrow box; const p = rt ref view;", "Pointer<T>");
        reject("const p = rt ref 7;", "Pointer<T>");
        reject("const p = rt ref new Box(); const q = rt ref p;", "Pointer<T>");
        reject("const p = new Pointer<Box>();", "rt ref");
    }

    @Test void unrefTransfersOwnershipAndTombstonesPointer() {
        check("const p = rt ref new Box(); const Box owned = rt unref p; stdio.println(owned.value);");
        check("const p = rt ref new Box(); block { const Box view = rt deref p; } const Box owned = rt unref p;");
        reject("const p = rt ref new Box(); const Box owned = rt unref p; const again = rt deref p;", "moved");
        reject("const p = rt ref new Box(); const Box first = rt unref p; const Box second = rt unref p;", "moved");
        reject("const p = rt ref new Box(); const Box view = rt deref p; const Box owned = rt unref p;", "borrow");
        reject("const p = rt ref new Box(); const copy = rt copy p; const Box owned = rt unref p;", "rt copy");
        reject("const p = rt ref new Box(); const shared = rt share p; const Box owned = rt unref p;", "rt share");
        // Loan of the first argument must live until the full invocation returns.
        var callFailure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                fnc take_both(borrow Box view, Box owned): void { return; }
                fnc bad(): void {
                  const p = rt ref new Box();
                  take_both(rt deref p, rt unref p);
                  return;
                }
                """)));
        assertTrue(callFailure.getMessage().contains("borrow"), callFailure.getMessage());


        Object target = new Object();
        dev.oreslang.runtime.OresPointer pointer = new dev.oreslang.runtime.OresPointer(target);
        assertSame(target, pointer.target());
        assertSame(target, pointer.unref());
        assertThrows(IllegalStateException.class, pointer::target);
        assertThrows(IllegalStateException.class, pointer::unref);
    }

    @Test void unrefCannotMasqueradeAsBorrowedCallArgument() {
        String prefix = BOX + """
                fnc read(borrow Box value): int { return value.value; }
                fnc edit(borrow mut Box value): void { value.value = 9; return; }
                """;
        // Consuming unwrap returns owned Box. It is not a pointer-rooted loan.
        for (String call : new String[] {"read(rt unref p)", "edit(rt unref p)"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(prefix + """
                    fnc bad(): void {
                      const p = rt ref new Box();
                      %s;
                      return;
                    }
                    """.formatted(call))));
            assertTrue(failure.getMessage().contains("cannot borrow consuming rt unref"),
                    failure.getMessage());
        }
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(prefix + """
                fnc ok(): void {
                  const p = rt ref new Box();
                  const int before = read(rt deref p);
                  const Box owned = rt unref p;
                  const int after = read(rt borrow owned);
                  return;
                }
                """)));
        // Passing the dereference as an immutable borrow must never mint a mutable one.
        var mutableFailure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(prefix + """
                fnc bad(): void {
                  const p = rt ref new Box();
                  edit(rt deref p);
                  return;
                }
                """)));
        assertTrue(mutableFailure.getMessage().contains("read-only borrow"),
                mutableFailure.getMessage());
    }

    @Test void unrefGuestExecutionReturnsOwnedPointee() throws Exception {
        String program = BOX + """
                pub routine main(): void {
                  const p = rt ref new Box();
                  const Box owned = rt unref p;
                  stdio.stdout.write(owned.value);
                  return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
            context.eval("ores", program);
        }
        assertEquals("7", out.toString(StandardCharsets.UTF_8));
    }

    @Test void pointerOperationsRequireCorrectArityAndType() {
        for (String access : new String[] {"ref", "unref", "deref"}) {
            reject("const p = rt " + access + "();", "argument");
            reject("const p = rt " + access + "(1, 2);", ")");
        }
        reject("const p = rt unref new Box();", "Pointer<T>");
        reject("const p = rt deref 1;", "Pointer<T>");
        reject("const p = rt unref(rt ref new Box());", "named Pointer<T> owner");
    }

    @Test void viewsCannotMutateOrManufactureOwnership() {
        for (String op : new String[] {"deref"}) {
            String setup = "const p = rt ref new Box(); val Box view = rt " + op + " p; ";
            reject(setup + "view.value = 9;", "borrow");
            reject(setup + "const owned = rt take view;", "borrow");
            reject(setup + "const moved = rt take p;", "borrow");
            reject(setup + "block { const alias = view; } const moved = rt take p;", "borrow");
        }
        check("const p = rt ref new Box(); block { const Box view = rt deref p; } const moved = rt take p;");
        reject("const p = rt ref new Box(); const copied = rt copy p;", "rt copy");
        reject("const p = rt ref new Box(); const shared = rt share p;", "rt share");
    }

    @Test void viewsCannotEscapeTheirOwner() {
        var e = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                fnc bad(): borrow Box {
                  const p = rt ref new Box();
                  return rt deref p;
                }
                """)));
        assertTrue(e.getMessage().contains("borrow"), e.getMessage());
        reject("const p = rt ref new Box(); const view = rt deref p; const f = || -> { stdio.println(view.value); return; };", "borrow");
        reject("const p = rt ref new Box(); const view = rt deref p; rt cooperate;", "borrow");
    }

    @Test void nestedViewsCannotLeakMutableChildren() {
        reject("const p = rt ref struct{child: Box}{child: new Box()}; const view = rt deref p; val child = view.child; child.value = 9;", "borrow");
        reject("const p = rt ref struct{child: Box}{child: new Box()}; const view = rt deref p; const child = rt ref view.child;", "projected");
    }

    @Test void castsAndAnnotationsCannotEraseBorrowProvenance() {
        reject("const p = rt ref new Box(); const view = rt deref p; val Box alias = view as Box; alias.value = 9;", "borrow");
        reject("const p = rt ref new Box(); const view = rt deref p; const Box alias = view as Box; const q = rt ref alias;", "borrow");
        reject("const p = rt ref struct{child: Box}{child: new Box()}; const view = rt deref p; val {child} = view; child.value = 9;", "borrow");
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                fnc mutate(Box mut box): void { box.value = 9; return; }
                fnc bad(): void {
                  const p = rt ref new Box();
                  const view = rt deref p;
                  mutate(view as Box);
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }

    @Test void callBorrowsLastThroughLaterArgumentEvaluation() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc read(borrow Box view): int { return view.value; }
                fnc ok(): void {
                  const p = rt ref new Box();
                  const int n = read(rt deref p);
                  const moved = rt take p;
                  return;
                }
                """)));
        for (String access : new String[] {"deref"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                    fnc read(borrow Box view, Pointer<Box> moved): void { return; }
                    fnc bad(): void {
                      const p = rt ref new Box();
                      read(rt %s p, rt take p);
                      return;
                    }
                    """.formatted(access))));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }

    @Test void collectionReadsAndAliasesCannotMintOwningPointers() {
        reject("val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); const p = rt ref boxes.get(0);", "projected");
        reject("val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); const p = rt ref boxes; const view = rt deref p; val child = view.get(0); child.value = 9;", "borrow");
        reject("val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); const p = rt ref boxes; const view = rt deref p; val child = view[0]; child.value = 9;", "borrow");
    }

    @Test void pointerHandlesCannotHideInOrdinaryAggregatesBeforePlaceMovesExist() {
        reject("const p = rt ref new Box(); const holder = struct{ptr: Pointer<Box>}{ptr: p};",
                "place-aware move-out");
        reject("const p = rt ref new Box(); const wrapped = Some(p);",
                "place-aware move-out");
        reject("let mut Array<Pointer<Box>> values = new Array<Pointer<Box>>(); "
                        + "const p = rt ref new Box(); values.add(p);",
                "place-aware move-out");
        // Without this gate, repeated holder.ptr projections could create two
        // compiler-owned aliases to one OresPointer and defer the failure to the
        // runtime tombstone instead of preserving affine ownership statically.
    }

    @Test void pointerHandlesCannotEscapeIntoFieldsOrModuleGlobals() {
        var global = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(BOX + "const p = rt ref new Box();")));
        assertTrue(global.getMessage().contains("module/global storage"), global.getMessage());

        var field = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(BOX + """
                        define class Holder as
                          let Pointer<Box> ptr = rt ref new Box();
                        end
                        """)));
        assertTrue(field.getMessage().contains("place-aware move-out"), field.getMessage());
    }

    @Test void pointerHandlesCannotEscapeIntoRepeatableClosures() {
        reject("const p = rt ref new Box(); "
                        + "const (() => int) read = || -> { const view = rt deref p; return view.value; };",
                "closure cannot capture Pointer<T>");
        reject("const p = rt ref new Box(); "
                        + "const (() => Box) consume = || -> { return rt unref p; };",
                "closure cannot capture Pointer<T>");
    }

    @Test void pointerTypeCannotBeRedefinedOrSharedWithoutProof() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("define class Pointer as end")));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + "fnc local(Channel<Pointer<Box>> messages): void { return; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + "fnc bad(SharedMutex<Pointer<Box>> value): void { return; }")));
        reject("const Pointer<int> p = rt ref 7;", "Pointer<T>");
    }

    @Test void recursivePointerTargetGraphsFailWithADiagnostic() {
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Node as
                  pub val Pointer<Node> next;
                end
                """)));
        assertTrue(error.getMessage().contains("Pointer<T>"), error.getMessage());
    }

    @Test void expressionsCannotHidePointerViewLifetimes() {
        reject("const p = rt ref new Box(); const view = rt deref p; const hidden = true ? (view as Box) : (view as Box); const moved = rt take p;", "borrow");
        reject("const p = rt ref new Box(); const hidden = true ? (rt deref p) : (rt deref p); const moved = rt take p;", "borrow");
        reject("const p = rt ref new Box(); const hidden = (rt deref p) as? Box; const moved = rt take p;", "borrow");
        reject("const p = rt ref new Box(); const hidden = true ? ((rt deref p) as Box) : ((rt deref p) as Box);", "borrow");
        check("const p = rt ref new Box(); const n = true ? (rt deref p).value : (rt deref p).value; const moved = rt take p;");
    }

    @Test void structuralCallsRetainTemporaryPointerViews() {
        String prefix = BOX + """
                define interface Shape
                  value: int;
                end
                fnc read(structural Shape view, Pointer<Box> moved): void { return; }
                fnc inspect(structural Shape view): int { return view.value; }
                """;
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(prefix + """
                fnc bad(): void {
                  const p = rt ref new Box();
                  read((rt deref p) as Box, rt take p);
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(prefix + """
                fnc ok(): void {
                  const p = rt ref new Box();
                  const n = inspect((rt deref p) as Box);
                  const moved = rt take p;
                  return;
                }
                """)));
    }

    @Test void firstClassCallRetainsTemporaryPointerViews() {
        reject("const Fnc<Box, Pointer<Box>, void> f = |Box view, Pointer<Box> moved| -> { return; }; const p = rt ref new Box(); f((rt deref p) as Box, rt take p);", "borrow");
        check("const Fnc<Box, void> f = |Box view| -> { return; }; const p = rt ref new Box(); f((rt deref p) as Box); const moved = rt take p;");
    }

    @Test void structuralCallPreservesInlineMutableDerefExclusivity() {
        String prefix = BOX + """
                define interface Shape
                  value: int;
                end
                fnc inspect_two(structural Shape first, structural Shape second): int {
                  return first.value + second.value;
                }
                """;

        var failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(prefix + """
                        fnc bad(): void {
                          let mut Pointer<Box> p = rt ref new Box();
                          const int n = inspect_two(
                              (rt deref mut p) as Box,
                              (rt deref p) as Box);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("mutable borrow")
                        || failure.getMessage().contains("mutably borrowed"),
                failure.getMessage());
    }

    @Test void symbolicPointerAndBorrowSyntaxIsRejected() {
        for (String expression : new String[] {"&box", "&mut box", "*box"}) {
            var e = assertThrows(IllegalArgumentException.class, () -> Parser.parse(BOX + "fnc bad(): void { const p = " + expression + "; return; }"));
            assertTrue(e.getMessage().contains("symbolic"), e.getMessage());
        }
        for (String type : new String[] {"&Box", "&mut Box", "*Box", "Box*"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(BOX + "fnc bad(" + type + " p): void { return; }"));
        }
        check("const int n = 3 * 4; const int bits = 7 & 3; const bool b = true && false;");
    }
}
