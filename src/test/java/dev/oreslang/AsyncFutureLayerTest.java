package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncFutureLayerTest {
    private static final String WORKER = "actor fnc worker(int id): int { return id * 10; }\n";

    @Test void explicitAwaitReturnsOneFutureLayerForThreeActors() throws Exception {
        assertEquals("10\n20\n30\n", run(WORKER + """
            async fnc launch(int id): int {
              val spawned = spawn worker(id);
              return await spawned.result;
            }
            pub routine main(): void {
              val Future<int> first = launch(1);
              val Future<int> second = launch(2);
              val Future<int> third = launch(3);
              stdio.println(await first);
              stdio.println(await second);
              stdio.println(await third);
              return;
            }
            """));
    }

    @Test void futureReturnAddsAnOuterLayerAndRequiresTwoAwaits() throws Exception {
        assertEquals("10\n", run(WORKER + """
            async fnc launch(int id): Future<int> {
              val spawned = spawn worker(id);
              return spawned.result;
            }
            pub routine main(): void {
              val Future<Future<int>> outer = launch(1);
              val Future<int> inner = await outer;
              stdio.println(await inner);
              return;
            }
            """));
    }

    @Test void missingAwaitCannotSatisfyIntReturn() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(WORKER + """
            async fnc launch(int id): int {
              val spawned = spawn worker(id);
              return spawned.result;
            }
            """)));
    }

    @Test void nestedFutureCannotBeAssignedToSingleFuture() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(WORKER + """
            async fnc launch(int id): Future<int> { return (spawn worker(id)).result; }
            pub routine main(): void { val Future<int> wrong = launch(1); return; }
            """)));
    }

    @Test void ordinaryFunctionForwardsFutureWithoutAddingLayer() throws Exception {
        assertEquals("10\n", run(WORKER + """
            fnc launch(int id): Future<int> { return (spawn worker(id)).result; }
            pub routine main(): void { val Future<int> result = launch(1); stdio.println(await result); return; }
            """));
    }

    @Test void asyncAliasesPreserveWrappingAndMainAwaits() throws Exception {
        assertEquals("7\n", run("""
            async fnc inner(): int { return 7; }
            async fnc outer(): Future<int> { val invoke = inner; return invoke(); }
            pub async routine main(): void {
              val Future<Future<int>> pending = outer();
              stdio.println(await await pending);
              return;
            }
            """));
    }

    @Test void loopsAndSynchronousHelpersResumeWithoutReplayingSideEffects() throws Exception {
        assertEquals("0\n1\n2\n3\n", run("""
            async fnc value(int n): int { return n; }
            fnc helper(int n): int { return await value(n); }
            async fnc total(): int {
              let sum = 0;
              for (let n = 0; n < 3; n = n + 1) {
                stdio.println(n);
                sum = sum + helper(n);
              }
              return sum;
            }
            pub routine main(): void { stdio.println(await total()); return; }
            """));
    }

    @Test void deeperFutureLayersRemainExplicit() throws Exception {
        assertEquals("8\n", run("""
            async fnc first(): int { return 8; }
            async fnc second(): Future<int> { return first(); }
            async fnc third(): Future<Future<int>> { return second(); }
            pub routine main(): void {
              val Future<Future<Future<int>>> pending = third();
              stdio.println(await await await pending);
              return;
            }
            """));
    }

    @Test void staticAsyncAndModuleCallsPreserveFutureLayers() throws Exception {
        assertEquals("12\n", run("""
            define module app
              pub async fnc value(): int { return 12; }
              define class Factory as
                pub static async fnc make(): Future<int> { return app.value(); }
              end
            end
            pub routine main(): void {
              val Future<Future<int>> result = app.Factory.make();
              stdio.println(await await result);
              return;
            }
            """));
    }

    @Test void exceptionsAfterAwaitRunCatchFinallyAndDeferredActions() throws Exception {
        assertEquals("deferred\ncaught\nfinally\n9\n", run("""
            async fnc value(): int { return 1; }
            async fnc task(): int {
              try {
                defer stdio.println("deferred");
                val n = await value();
                val broken = arr[0][n];
              } catch (error) {
                stdio.println("caught");
                return 9;
              } finally {
                stdio.println("finally");
              }
              return 0;
            }
            pub routine main(): void { stdio.println(await task()); return; }
            """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false").out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "async-layers.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
