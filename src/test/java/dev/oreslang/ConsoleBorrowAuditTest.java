package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConsoleBorrowAuditTest {
    private static final String PAYLOAD = """
            define class ConsolePayload as pub let int value = 42; end
            fnc consume(ConsolePayload item):String { return "consumed"; }
            fnc edit(borrow mut ConsolePayload item):String { item.value = 43; return "edited"; }
            """;

    private static String main(String statements) {
        return PAYLOAD + "pub fnc main():int { val ConsolePayload item = new ConsolePayload();"
                + statements + "return item.value; }";
    }

    @Test
    void consoleFormattingBorrowsWithoutMovingAndReleasesAfterTheCall() {
        for (String operation : java.util.List.of("stdio.print", "stdio.println", "stdio.stdout.log")) {
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(main(
                    operation + "(item, item); edit(rt mut borrow item);")), operation);
        }
    }

    @Test
    void laterArgumentsCannotMoveEarlierFormattingValues() {
        for (String operation : java.util.List.of("stdio.print", "stdio.println", "stdio.stdout.log")) {
            assertBorrowRejected(main(operation + "(item, consume(item));"));
        }
    }

    @Test
    void laterArgumentsCannotMutateEarlierFormattingValues() {
        for (String operation : java.util.List.of("stdio.print", "stdio.println", "stdio.stdout.log")) {
            assertBorrowRejected(main(operation + "(item, edit(rt mut borrow item));"));
        }
    }

    @Test
    void explicitReborrowRemainsLiveAcrossAllArguments() {
        assertBorrowRejected(main("stdio.stdout.log(rt borrow item, consume(item));"));
    }

    @Test
    void conditionalFormattingPinsBothPossibleOwners() {
        assertBorrowRejected(main("val ConsolePayload other = new ConsolePayload();"
                + "stdio.stdout.log(true ? item : other, consume(other));"));
    }

    @Test
    void assignmentResultPinsItsNewOwner() {
        assertBorrowRejected(PAYLOAD + """
                pub fnc main():int {
                  let ConsolePayload target = new ConsolePayload();
                  val ConsolePayload donor = new ConsolePayload();
                  stdio.stdout.log(target = donor, consume(target));
                  return 0;
                }
                """);
    }

    @Test
    void matchFormattingPinsAllPossibleArmOwners() {
        assertBorrowRejected(main("val ConsolePayload other = new ConsolePayload();"
                + "stdio.stdout.log(match<ConsolePayload> true over "
                + "on true -> { return item; } on false -> { return other; } end, consume(other));"));
    }

    @Test
    void formattingSpreadPinsTheCollectionUntilTheInvocationCompletes() {
        assertBorrowRejected("""
                fnc consume(Array<int> values):String { return "consumed"; }
                pub fnc main():int {
                  val Array<int> values = new Array<int>(); values.add(42);
                  stdio.stdout.log(...values, consume(values));
                  return 0;
                }
                """);
    }

    @Test
    void collectionGetterViewPinsItsCollection() {
        assertBorrowRejected(PAYLOAD + """
                fnc consume_array(Array<ConsolePayload> values):String { return "consumed"; }
                pub fnc main():int {
                  val Array<ConsolePayload> values = new Array<ConsolePayload>();
                  values.add(new ConsolePayload());
                  stdio.stdout.log(values.get(0), consume_array(values));
                  return 0;
                }
                """);
    }

    @Test
    void collectionGetterCopySnapshotDoesNotKeepAnUnnecessaryLoan() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc consume_array(Array<int> values):String { return "consumed"; }
                pub fnc main():int {
                  val Array<int> values = new Array<int>(); values.add(42);
                  stdio.stdout.log(values.get(0), consume_array(values));
                  return 0;
                }
                """));
    }

    @Test
    void collectionReceiverIsProtectedWhileItsIndexIsEvaluated() {
        assertBorrowRejected("""
                fnc consume_index(Array<int> values):int { return 0; }
                pub fnc main():int {
                  val Array<int> values = new Array<int>(); values.add(42);
                  stdio.println(values.get(consume_index(values)));
                  return 0;
                }
                """);
    }

    @Test
    void projectedCollectionCopySnapshotDoesNotKeepAnUnnecessaryLoan() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class Holder as pub val Array<int> values; end
                fnc consume_holder(Holder holder):String { return "consumed"; }
                pub fnc main():int {
                  val Array<int> values = new Array<int>(); values.add(42);
                  val Holder holder = new Holder(values);
                  stdio.stdout.log(holder.values.get(0), consume_holder(holder));
                  return 0;
                }
                """));
    }

    @Test
    void shadowedConsoleStillUsesOrdinaryConsumingCallSemantics() {
        var failure = assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse("""
                define class Payload as end
                define class Sink as pub println(Payload value):void { return; } end
                fnc use(Sink stdio, Payload value):void {
                  stdio.println(value); stdio.println(value); return;
                }
                """)));
        assertTrue(failure.getMessage().contains("moved"), failure.getMessage());
    }

    @Test
    void projectedValuePinsItsContainingOwner() {
        assertBorrowRejected(PAYLOAD + """
                define class ConsoleHolder as pub val ConsolePayload payload; end
                fnc consume_holder(ConsoleHolder holder):String { return "consumed"; }
                pub fnc main():int {
                  val ConsoleHolder holder = new ConsoleHolder(new ConsolePayload());
                  stdio.stdout.log(holder.payload, consume_holder(holder));
                  return 0;
                }
                """);
    }

    @Test
    void exclusiveIncomingViewRemainsUsableAfterFormatting() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(PAYLOAD + """
                fnc display(borrow mut ConsolePayload item):void {
                  stdio.println(item, item);
                  item.value = 44;
                  return;
                }
                pub fnc main():int { return 0; }
                """));
    }

    @Test
    void guardCannotPassThroughAnyDirectFormattingEntryPoint() {
        for (String operation : java.util.List.of("stdio.print", "stdio.println",
                "stdio.stdout.write", "stdio.stdout.println", "stdio.stdout.log")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(PAYLOAD + """
                            pub fnc main():int {
                              val mutex = Mutex.new(new ConsolePayload());
                              val lock_guard = mutex.lock();
                            """ + operation + "(lock_guard); lock_guard.release(); return 0;}"));
            assertTrue(failure.getMessage().contains("guard") || failure.getMessage().contains("MutexGuard"),
                    failure.getMessage());
        }
    }

    @Test
    void guardScalarSnapshotStillFormats() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(PAYLOAD + """
                pub fnc main():int {
                  val mutex = Mutex.new(new ConsolePayload());
                  val lock_guard = mutex.lock();
                  stdio.println("value=", lock_guard.value);
                  lock_guard.release(); return 0;
                }
                """));
    }

    private static void assertBorrowRejected(String program) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(failure.getMessage().contains("borrow"), failure.getMessage());
    }
}
