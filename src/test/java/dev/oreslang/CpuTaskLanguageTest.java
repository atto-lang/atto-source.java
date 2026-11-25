package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class CpuTaskLanguageTest {
    @Test void cpuTaskReturnsAValueThroughFuture() throws Exception {
        assertEquals("42", run("""
                pub async fnc main(): void {
                  let Future<int> t = cpu task {
                    return 42;
                  };
                  stdio.stdout.write(await t);
                  return;
                }
                """));
    }

    @Test void defaultTaskAndLegacyNbRemainCompatible() throws Exception {
        assertEquals("7|8", run("""
                pub async fnc main(): void {
                  let Future<int> modern = task {
                    return 7;
                  };
                  let Future<int> legacy = nb cpu {
                    return 8;
                  };
                  stdio.stdout.write(await modern);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await legacy);
                  return;
                }
                """));
    }

    @Test void taskIsSubmittedBeforeCallerAwaitsItsFuture() throws Exception {
        // The caller waits on a channel message *before* awaiting t. If task
        // execution were lazy-on-await this program would deadlock.
        assertEquals("7|done", run("""
                pub async fnc main(): void {
                  val Channel<int> ready = Channel.new<int>(1);
                  let Future<void> t = cpu task {
                    writech ready, 7;
                    return;
                  };
                  val int value = readch ready;
                  stdio.stdout.write(value);
                  stdio.stdout.write("|");
                  await t;
                  stdio.stdout.write("done");
                  return;
                }
                """));
    }

    @Test void cpuTaskUsesSourceTaskLoopCooperationWithoutAwaitInsideBody() throws Exception {
        String output = run("""
                pub async fnc main(): void {
                  let Future<int> t = cpu task {
                    let total = 0;
                    for (let i = 0; i < 10000; i = i + 1) {
                      total = total + 1;
                    }
                    return total;
                  };
                  stdio.stdout.write(await t);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("10000|"), output);
        assertTrue(Long.parseLong(output.substring("10000|".length())) > 0,
                "CPU Task loop must cooperate independently of await: " + output);
    }

    @Test void moveOnlyCaptureTransfersAtTaskSubmission() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                          pub let int value = 1;
                        end
                        pub async fnc main(): void {
                          let original = new Box();
                          let Future<int> t = cpu task {
                            original.value = 99;
                            return original.value;
                          };
                          stdio.stdout.write(original.value);
                          stdio.stdout.write(await t);
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("moved")
                        || failure.getMessage().contains("capture"),
                failure::getMessage);
    }

    @Test void taskCannotCaptureALiveMutexGuard() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                          pub let int value = 1;
                        end
                        pub async fnc main(): void {
                          val mutex = Mutex.new(new Box());
                          val lock_guard = mutex.lock();
                          let Future<int> t = cpu task {
                            return lock_guard.value;
                          };
                          lock_guard.release();
                          stdio.stdout.write(await t);
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("guard")
                        || failure.getMessage().contains("MutexGuard"),
                failure::getMessage);
    }

    @Test void consecutivelySubmittedTasksDoNotClonePreviousFuture() throws Exception {
        assertEquals("1|2", run("""
                pub async fnc main(): void {
                  let Future<int> first = task { return 1; };
                  let Future<int> second = task { return 2; };
                  stdio.stdout.write(await first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await second);
                  return;
                }
                """));
    }

    @Test void shadowedParentFutureDoesNotLeakIntoChildSnapshot() throws Exception {
        assertEquals("42|1", run("""
                pub async fnc main(): void {
                  let Future<int> value = task { return 1; };
                  block {
                    const int value = 41;
                    let Future<int> second = task { return value + 1; };
                    stdio.stdout.write(await second);
                  }
                  stdio.stdout.write("|");
                  stdio.stdout.write(await value);
                  return;
                }
                """));
    }

    @Test void taskLocalShadowDoesNotDetachOuterCapability() throws Exception {
        assertEquals("42", run("""
                define class Box as
                  pub let int value = 1;
                end
                pub async fnc main(): void {
                  val mutex = Mutex.new(new Box());
                  let Future<int> worker = task {
                    const int mutex = 41;
                    return mutex + 1;
                  };
                  stdio.stdout.write(await worker);
                  return;
                }
                """));
    }

    @Test void taskLocalCanShadowUnrelatedParentFuture() throws Exception {
        assertEquals("42|1", run("""
                pub async fnc main(): void {
                  let Future<int> value = task { return 1; };
                  let Future<int> second = task {
                    const int value = 41;
                    return value + 1;
                  };
                  stdio.stdout.write(await second);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await value);
                  return;
                }
                """));
    }

    @Test void taskCapturesTransitiveNestedTaskReferences() throws Exception {
        assertEquals("42", run("""
                pub async fnc main(): void {
                  const int base = 40;
                  let Future<int> outer = task {
                    let Future<int> inner = task { return base + 2; };
                    return await inner;
                  };
                  stdio.stdout.write(await outer);
                  return;
                }
                """));
    }

    @Test void affineFutureMovesIntoSecondTaskWithoutRuntimeCloning() throws Exception {
        assertEquals("42", run("""
                pub async fnc main(): void {
                  let Future<int> first = task {
                    return 41;
                  };
                  let Future<int> second = task {
                    return (await first) + 1;
                  };
                  stdio.stdout.write(await second);
                  return;
                }
                """));
    }

    @Test void copiedScalarCaptureIsFrozenAtSubmission() throws Exception {
        assertEquals("41|99", run("""
                pub async fnc main(): void {
                  let int value = 41;
                  let Future<int> worker = task { return value; };
                  value = 99;
                  stdio.stdout.write(await worker);
                  stdio.stdout.write("|");
                  stdio.stdout.write(value);
                  return;
                }
                """));
    }

    @Test void gpuTaskIsRejectedInsteadOfExecutingOnCpu() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc main(): void {
                          let Future<int> t = gpu task {
                            return 42;
                          };
                          stdio.stdout.write(await t);
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("no implicit CPU fallback"),
                failure::getMessage);
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "cpu-task-v2.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}
