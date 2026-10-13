package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Channel<T> is a recursively data-only transport, not a local object/function pipe. */
final class ChannelDataOnlyPayloadTest {
    private static void permits(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static IllegalArgumentException forbids(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void permitsPrimitiveTupleRecordArraysAndRecursiveTaggedData() {
        permits("""
                fnc data(
                    Channel<int> a,
                    Channel<string> b,
                    Channel<[int, string]> pair,
                    Channel<{foo: string, count: int}> row,
                    Channel<Array<{foo: string, count: int}>> rows,
                    Channel<Option<Result<[int, string], {reason: string}>>> sum
                ): void { return; }
                """);
        permits("""
                type Payload = {name: string, values: Array<int>};
                fnc send(Channel<Payload> output, Payload value): void {
                  writech output, value;
                  return;
                }
                """);
        permits("""
                pub routine main(): void {
                  val Channel<{value: int, label: string}> queue =
                      Channel.new<{value: int, label: string}>(1);
                  writech queue, struct{value: int, label: string}{value: 1, label: "one"};
                  select {
                    when readch queue: const {value: int} -> {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """);
    }

    @Test
    void rejectsClassesEvenIfMethodFreeAndRejectsNominalInterfaces() {
        IllegalArgumentException klass = forbids("""
                define class Plain as
                  pub val int value;
                end
                fnc bad(Channel<Plain> channel): void { return; }
                """);
        assertTrue(klass.getMessage().contains("cannot transport nominal/capability type"),
                klass.getMessage());
        IllegalArgumentException nested = forbids("""
                define class Plain as
                  pub val int value;
                end
                fnc bad(Channel<Array<{item: Plain}>> channel): void { return; }
                """);
        assertTrue(nested.getMessage().contains("field 'item'"), nested.getMessage());
        forbids("""
                define interface Runnable as
                    fnc run(): void;
                end
                fnc bad(Channel<Runnable> channel): void { return; }
                """);
    }

    @Test
    void rejectsCallableFieldsIncludingNestedOptionAndResult() {
        forbids("""
                fnc bad(Channel<Fnc<int, int>> handlers): void { return; }
                """);
        IllegalArgumentException error = forbids("""
                fnc bad(Channel<{name: string, meta: Option<{handler: Fnc<int, int>}>}> ch): void {
                  return;
                }
                """);
        assertTrue(error.getMessage().contains("handler"), error.getMessage());
        forbids("""
                fnc bad(Channel<Result<int, Array<Fnc<int, int>>>> ch): void { return; }
                """);
    }

    @Test
    void rejectsActorCapabilitiesAndRuntimeHandlesAtEveryNestingDepth() {
        for (String type : new String[]{
                "ActorRef<int>", "ActorMail<int>", "ActorId",
                "Future<int>", "Channel<int>", "Mutex<int>",
                "SharedMutex<int>", "Iterator<int>", "AsyncIterator<int>",
                "SelectCase", "SelectSet", "SelectResult",
                "Array<Channel<int>>",
                "{status: bool, next: Option<Future<int>>}",
                "[int, ActorRef<int>]"
        }) {
            final String source = "fnc bad(Channel<" + type + "> channel): void { return; }";
            assertTrue(forbids(source).getMessage().contains("channel"),
                    "expected data-only diagnostic for " + type);
        }
    }

    @Test
    void rejectsUnknownGenericAndBorrowedChannelPayloads() {
        forbids("""
                fnc bad<T>(Channel<T> ch): void { return; }
                """);
        forbids("""
                fnc bad(Channel<{value: &int}> ch): void { return; }
                """);
    }

    @Test
    void rejectsUnsafeTypesAtFactoriesAndSelectCases() {
        forbids("""
                pub routine main(): void {
                  val Channel<Fnc<int, int>> handlers =
                      Channel.new<Fnc<int, int>>(1);
                  return;
                }
                """);
        forbids("""
                fnc bad(Channel<{callback: Fnc<int, int>}> ch): void {
                  select {
                    when readch ch: const value -> {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """);
    }
}
