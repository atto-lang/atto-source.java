package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PointerlessFncOwnershipTest {

    @Test
    void firstClassFncCallsTemporarilyReadBorrowNonCopyArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end

                fnc ok(): int {
                  let mut Box box = new Box();
                  val Fnc<Box, int> read = |value| -> {
                    return value.value;
                  };
                  val int before = read(box);
                  box.value = 9;
                  val int after = read(box);
                  return before + after;
                }
                """)));
    }

    @Test
    void storedRtBorrowBlocksMutationUntilItsScopeEnds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val view = rt borrow box;
                          box.value = 9;
                          stdio.println(view.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void rtTakeTransfersOwnershipAndInvalidatesTheSourceBinding() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val Box moved = rt take box;
                          stdio.println(box.value);
                          stdio.println(moved.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("moved"), error.getMessage());
    }

    @Test
    void rtCopyAndRtShareAreSafeForCopyValues() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val int source = 7;
                  val int copied = rt copy source;
                  val int shared = rt share source;
                  stdio.stdout.write(copied + shared);
                  return;
                }
                """);
        assertEquals("14", output);
    }

    @Test
    void rtShareCannotTreatCopyOnlyRuntimeHandleAsShareable() {
        // SelectCase and SelectSet are copyable handles in the present
        // ownership classifier, but deliberately do not derive Share (or
        // Task Send) in the canonical compiler capability lattice.
        for (String handleType : new String[] {"SelectCase", "SelectSet"}) {
            String source = """
                    fnc forbidden(%s handle): void {
                      val %s copied = rt copy handle;
                      val %s published = rt share handle;
                      return;
                    }
                    """.formatted(handleType, handleType, handleType);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(source)));
            assertTrue(failure.getMessage().contains(
                    "rt share requires statically proven Share capability"),
                    failure.getMessage());
        }
    }

    @Test
    void rtShareDoesNotLaunderCopyOnlyHandleThroughOptionWrapper() {
        // Option<T> itself is Copy when T is Copy, but must not make an
        // inner capability Share by recursively erasing its negative trait.
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                        fnc forbidden(Option<SelectSet> wrapper): void {
                          val Option<SelectSet> copy = rt copy wrapper;
                          val Option<SelectSet> alias = rt share wrapper;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains(
                "rt share requires statically proven Share capability"),
                failure.getMessage());
    }

    @Test
    void rtShareChecksGenericPayloadSendBeforeCopyableChannelAlias() {
        // A Channel<T> handle is Copy, but sharing it across domains needs a
        // Send-safe payload. Rc<int> is explicitly non-Send/non-Share.
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                        fnc forbidden(Channel<Rc<int>> channel): void {
                          val Channel<Rc<int>> copied = rt copy channel;
                          val Channel<Rc<int>> aliased = rt share channel;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains(
                "rt share requires statically proven Share capability"),
                failure.getMessage());
    }

    @Test
    void rtShareAdmitsProvenSynchronizedChannelAndSharedMutexHandles() {
        assertDoesNotThrow(() ->
                dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                        fnc allowed(Channel<int> channel, SharedMutex<int> guard): void {
                          val Channel<int> channel_alias = rt share channel;
                          val SharedMutex<int> guard_alias = rt share guard;
                          return;
                        }
                        """)));
    }

    @Test
    void moveOnlyCopyAndShareFailClosedUntilTheirFullContractsConverge() {
        IllegalArgumentException copied = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val other = rt copy box;
                          stdio.println(other.value);
                          return;
                        }
                        """)));
        assertTrue(copied.getMessage().contains("rt copy"), copied.getMessage());

        IllegalArgumentException shared = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val alias = rt share box;
                          stdio.println(alias.value);
                          return;
                        }
                        """)));
        assertTrue(shared.getMessage().contains("rt share"), shared.getMessage());
    }

    @Test
    void rtTakeCannotHideFieldExtractionInsideConditionalOrCast() {
        String type = """
                define class Box as
                  pub let int value = 7;
                end
                """;
        for (String expression : new String[] {
                "condition ? box.value : 7",
                "box.value as int"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(
                            type + """
                            fnc bad(bool condition): void {
                              let mut Box box = new Box();
                              val int escaped = rt take (%s);
                              return;
                            }
                            """.formatted(expression))));
            assertTrue(error.getMessage().contains("path-aware partial-move"),
                    error.getMessage());
        }
    }

    @Test
    void rtTakeCannotLaunderAnAssignmentResult() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end
                fnc bad(): void {
                  let mut Box box = new Box();
                  val int stolen = rt take (box.value = 12);
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("checked replace"),
                error.getMessage());
    }

    @Test
    void rtTakeAndRtCopyRequireTrackedNames() {
        for (String operation : new String[] {"take", "copy"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                    fnc bad(): void {
                      val inferred = rt %s absentBinding;
                      return;
                    }
                    """.formatted(operation))));
            assertTrue(failure.getMessage().contains(
                    operation.equals("take")
                            ? "statically tracked owned binding"
                            : "statically tracked Copy binding"),
                    failure.getMessage());
        }
    }

    @Test
    void rtTakeStillAcceptsIndependentFreshRvalue() {
        assertDoesNotThrow(() ->
                dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end
                fnc ok(): int {
                  val Box fresh = rt take new Box();
                  return fresh.value;
                }
                """)));
    }

    @Test
    void implicitByValueCallCannotMoveClassFieldWithoutInvalidatingSlot() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end
                define class Holder as
                  pub let Box item;
                end
                fnc consume(Box box): int { return box.value; }
                fnc invalid(Holder holder): int {
                  return consume(holder.item);
                }
                """)));
        assertTrue(error.getMessage().contains(
                "cannot consume move-only class field"), error.getMessage());
    }

    @Test
    void returningIndexedMoveOnlyElementCannotLeaveArraySlotLive() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () ->
                    dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                    define class Box as
                      pub let int value = 7;
                    end
                    fnc invalid(Array<Box> items): Box {
                      return items[0];
                    }
                    """)));
        assertTrue(error.getMessage().contains(
                "cannot consume move-only indexed element"), error.getMessage());
    }

    @Test
    void copyingScalarFieldDoesNotRequirePartialMoveLedger() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end
                fnc inspect(Box box): int {
                  val int detached = box.value;
                  return detached;
                }
                """)));
    }

    @Test
    void rtTakeFieldProjectionCannotDuplicateMoveOnlyOwner() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Payload as
                  pub let int count = 7;
                end
                define class Holder as
                  pub let mut Payload slot;
                  pub constructor(Payload p) { self.slot = p; }
                end
                fnc unsafe(): void {
                  let mut Holder holder = new Holder(new Payload());
                  val Payload escaped = rt take holder.slot;
                  stdio.println(holder.slot.count);
                  stdio.println(escaped.count);
                  return;
                }
                """)));
        assertTrue(rejected.getMessage().contains("path-aware partial-move"),
                rejected.getMessage());
    }

    @Test
    void rtTakeFieldProjectionCannotDuplicateEvenCopyableHandle() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Holder as
                  pub let int slot = 7;
                end
                fnc unsafe(): void {
                  let mut Holder holder = new Holder();
                  val int stolen = rt take holder.slot;
                  stdio.println(holder.slot + stolen);
                  return;
                }
                """)));
        assertTrue(rejected.getMessage().contains("path-aware partial-move"),
                rejected.getMessage());
    }

    @Test
    void rtTakeWholeNamedOwnerRemainsPermitted() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Payload as
                  pub let int count = 7;
                end
                fnc ok(): int {
                  let Payload original = new Payload();
                  val Payload moved = rt take original;
                  return moved.count;
                }
                """)));
    }

    @Test
    void rtCopyShareBorrowAndTakeCannotManufactureOwnedAliasFromLoan() {
        for (String operation : new String[] {
                "rt copy view", "rt share view", "rt take view"
        }) {
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    define class Payload as
                      pub let int count = 7;
                    end
                    fnc bad(): void {
                      let Payload original = new Payload();
                      val borrow Payload view = rt borrow original;
                      val alias = %s;
                      stdio.println(alias.count);
                      return;
                    }
                    """.formatted(operation))));
            assertTrue(rejected.getMessage().contains("rt ")
                            || rejected.getMessage().contains("borrow"),
                    rejected.getMessage());
        }
    }

    @Test
    void groupedBorrowIsNotATupleAndExplicitSingletonUnpacks() throws Exception {
        for (String binding : new String[] {
                "val borrowed = (rt borrow values);",
                "val (borrowed) = tuple (rt borrow values);"}) {
            assertEquals("1", run("""
                    pub routine main(): void {
                      val Array<int> values = new Array<int>();
                      values.add(7);
                      %s
                      stdio.stdout.write(borrowed.size);
                      return;
                    }
                    """.formatted(binding)));
            var error = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("""
                    fnc bad(): void {
                      val Array<int> values = new Array<int>();
                      %s
                      values.add(8);
                      stdio.println(borrowed.size);
                      return;
                    }
                    """.formatted(binding))));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }

    @Test
    void explicitTupleArityAndGroupingHaveDistinctAstNodes() {
        var program = Parser.parse("""
                fnc syntax(): void {
                  val grouped = (rt borrow values);
                  val singleton = tuple (rt borrow values);
                  val empty = tuple ();
                  val pair = tuple (1, 2);
                  return;
                }
                """);
        var function = (dev.oreslang.ast.Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        var grouped = (dev.oreslang.ast.Ast.BindingStmt) function.body().get(0);
        assertInstanceOf(dev.oreslang.ast.Ast.RuntimeCallExpr.class, grouped.initializer());
        for (int i = 1; i <= 3; i++) {
            var binding = (dev.oreslang.ast.Ast.BindingStmt) function.body().get(i);
            var tuple = assertInstanceOf(dev.oreslang.ast.Ast.TupleExpr.class, binding.initializer());
            assertEquals(new int[] {1, 0, 2}[i - 1], tuple.elements().size());
        }
    }

    @Test
    void tupleBorrowCannotEscapeOrSurviveCooperation() {
        for (String operation : new String[] {"return borrowed;", "rt cooperate; return;"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    fnc bad(): %s {
                      val Array<int> values = new Array<int>();
                      val (borrowed) = tuple (rt borrow values);
                      %s
                    }
                    """.formatted(operation.startsWith("return") ? "borrow Array<int>" : "void", operation))));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Array<int> values = new Array<int>();
                  val packed = tuple (rt borrow values);
                  return;
                }
                """)));
    }

    @Test
    void keywordMutableBorrowPreservesExclusiveAccess() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc update(borrow mut Array<int> values): void { values.add(7); return; }
                fnc ok(): void {
                  let mut Array<int> values = new Array<int>();
                  update(rt mut borrow values);
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Array<int> values = new Array<int>();
                  val first = rt mut borrow values;
                  val second = rt borrow values;
                  return;
                }
                """)));
    }

    private String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder("ores", code, "pointerless-fnc-ownership.ores").build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
