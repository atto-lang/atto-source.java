package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(20)
final class SourceLoopSoftPreemptionTest {
    @Test
    void asyncForLoopRunsThousandsOfIterationsWithoutStackGrowthOrReplay() throws Exception {
        String output = run("""
                async fnc count(): int {
                  let total = 0;
                  for (let i = 0; i < 10000; i = i + 1) {
                    total = total + 1;
                  }
                  return total;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await count());
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("10000|"), output);
        long cooperates = Long.parseLong(output.substring("10000|".length()));
        assertTrue(cooperates > 0, "CPU-only source loops must release their carrier at least once");
    }

    @Test
    void nestedIfAndLoopPreserveContinueBreakAndMutableLocals() throws Exception {
        assertEquals("667", run("""
                async fnc count(): int {
                  let count = 0;
                  let i = 0;
                  if true; do
                    loop do
                      i = i + 1;
                      if i % 3 == 0; do
                        continue;
                      fi
                      count = count + 1;
                      if i == 1000; do
                        break;
                      fi
                    done
                  fi
                  return count;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await count());
                  return;
                }
                """));
    }

    @Test
    void nestedForOfDoesNotRepeatConsumedIteratorElementsAfterCooperate() throws Exception {
        assertEquals("2400", run("""
                async fnc count(): int {
                  let total = 0;
                  for (let i = 0; i < 600; i = i + 1) do
                    for const item of arr[1, 2, 3, 4] do
                      total = total + 1;
                    done
                  done
                  return total;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await count());
                  return;
                }
                """));
    }

    @Test
    void synchronousReceiveLoopAutoPreemptsWithoutOvertakingLaterMailboxMessages()
            throws Exception {
        String output = run("""
                define actor Worker as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "first"; do
                      for (let i = 0; i < 4000; i = i + 1) do
                        self.count = self.count + 1;
                      done
                      self.send(self.count);
                      return;
                    fi
                    if mail.value == "second"; do
                      self.send(self.count + 1);
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("first");
                  worker.send("second");
                  val first = (await worker.outputs.next()).value.unwrap();
                  val second = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(first.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.value);
                  await worker.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("4000|4001|"), output);
        long cooperates = Long.parseLong(output.substring("4000|4001|".length()));
        assertTrue(cooperates > 0, "synchronous actor receive must be source-task preemptible");
    }

    @Test
    void automaticLoopCooperationDoesNotYieldWhileMutexGuardIsLive() throws Exception {
        assertEquals("2000|0", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val guard = mutex.lock();
                  for (let i = 0; i < 2000; i = i + 1) do
                    guard.value = guard.value + 1;
                  done
                  stdio.stdout.write(guard.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  guard.release();
                  return;
                }
                """));
    }

    @Test
    void blockingChannelAndSelectCannotArmUnderLiveMutexGuard() {
        // These must fail promptly instead of creating pending channel
        // waiters or consuming a message while a no-yield guard is live.
        for (String operation : new String[] {
                "readch pipe;",
                "writech pipe, 7;",
                "select { case readch pipe: val item { } }"
        }) {
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> run("""
                            define class Counter as
                              pub let int value = 0;
                            end
                            pub async routine main(): void {
                              val mutex = Mutex.new(new Counter());
                              val Channel<int> pipe = Channel.new<int>(0);
                              val guard = mutex.lock();
                            """ + operation + """
                              guard.release();
                              return;
                            }
                            """), operation);
            assertTrue(rejected.getMessage().contains("MutexGuard"),
                    operation + ": " + rejected.getMessage());
        }
    }

    @Test
    void blockingChannelReadWriteRemainUsableAfterReleasingMutex()
            throws Exception {
        assertEquals("7", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val Channel<int> pipe = Channel.new<int>(1);
                  val guard = mutex.lock();
                  guard.value = 7;
                  guard.release();
                  writech pipe, 7;
                  stdio.stdout.write(readch pipe);
                  return;
                }
                """));
    }

    @Test
    void generatorCannotUseCooperateToEscapeALiveMutexGuard() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> run("""
                        define class Counter as
                          pub let int value = 0;
                        end
                        generator fnc bad(): int {
                          val mutex = Mutex.new(new Counter());
                          val guard = mutex.lock();
                          rt cooperate;
                          guard.release();
                          yield 7;
                          return;
                        }
                        pub routine main(): void {
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("MutexGuard"),
                "generator must retain no-yield guard authority: " + failure.getMessage());
    }

    @Test
    void generatorMayCooperateOnceItsGuardIsReleased() throws Exception {
        assertEquals("7", run("""
                define class Counter as
                  pub let int value = 0;
                end
                generator fnc values(): int {
                  val mutex = Mutex.new(new Counter());
                  val guard = mutex.lock();
                  guard.value = 7;
                  guard.release();
                  rt cooperate;
                  yield 7;
                  return;
                }
                pub async routine main(): void {
                  for const value of values() do
                    stdio.stdout.write(value);
                  done
                  return;
                }
                """));
    }

    @Test
    void explicitRtCooperateCannotSuspendALiveMutexGuard() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val guard = mutex.lock();
                  rt cooperate;
                  guard.release();
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("MutexGuard"),
                "guard violation must fail closed: " + error.getMessage());
    }

    @Test
    void explicitRtCooperateWorksImmediatelyAfterGuardRelease()
            throws Exception {
        assertEquals("released-then-cooperated", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val guard = mutex.lock();
                  guard.value = guard.value + 1;
                  guard.release();
                  rt cooperate;
                  stdio.stdout.write("released-then-cooperated");
                  return;
                }
                """));
    }

    @Test
    void overdueWhileLoopNeverYieldsWithGuardButCooperatesAfterRelease()
            throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val guard = mutex.lock();
                  let i = 0;
                  while (i < 1024) do
                    guard.value = guard.value + 1;
                    i = i + 1;
                  done
                  stdio.stdout.write(guard.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  guard.release();

                  // The dispatch has already exhausted its budget. A newly
                  // safe backedge must cooperate immediately, rather than
                  // waiting for another full 64-backedge polling batch.
                  let resumed = 0;
                  while resumed < 1 do
                    resumed = resumed + 1;
                  done
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("1024|0|"), output);
        assertTrue(Long.parseLong(output.substring("1024|0|".length())) > 0,
                "release of an overdue lexical guard must restore fairness");
    }

    @Test
    void infiniteCpuActorDoesNotStarvePeerWithOneSharedCarrier()
            throws Exception {
        // Both actors deliberately use the SHARED lane. With one physical
        // worker, a non-cooperating infinite loop deadlocks the peer output.
        // Keep this scoped to the test to avoid changing other contexts.
        String setting = "ores.runtime.actor.shared.parallelism";
        String before = System.getProperty(setting);
        System.setProperty(setting, "1");
        try {
            String output = run("""
                    define actor Greedy as
                      let int spins;

                      on_start(): void {
                        self.spins = 0;
                        return;
                      }

                      receive(ActorMail<String> mail): void {
                        self.send("entered");
                        while (true) do
                          self.spins = self.spins + 1;
                        done
                      }
                    end

                    define actor Peer as
                      receive(ActorMail<String> mail): void {
                        self.send("peer");
                        self.end();
                        return;
                      }
                    end

                    pub async routine main(): void {
                      val greedy = spawn Greedy();
                      val peer = spawn Peer();
                      await greedy.get_ready();
                      await peer.get_ready();

                      greedy.send("begin");
                      // Admission barrier: greedy has reached receive before
                      // the peer is submitted, not merely been enqueued.
                      val entered = (await greedy.outputs.next()).value.unwrap();
                      peer.send("begin");
                      val reply = (await peer.outputs.next()).value.unwrap();
                      stdio.stdout.write(reply.value);
                      stdio.stdout.write("|");
                      stdio.stdout.write(process.descriptor.source_loop_cooperates);

                      // Cancellation must release the actual executing actor;
                      // no forced thread stop or guest-stack unwinding.
                      greedy.stop();
                      await greedy.get_done();
                      await peer.get_done();
                      return;
                    }
                    """);
            assertTrue(output.startsWith("peer|"),
                    "peer starved or failed to execute: " + output);
            long handoffs = Long.parseLong(output.substring("peer|".length()));
            assertTrue(handoffs > 0,
                    "an infinite source loop must release its physical carrier");
        } finally {
            if (before == null) System.clearProperty(setting);
            else System.setProperty(setting, before);
        }
    }

    @Test
    void whileSourceSyntaxRetainsReductionCooperationAcrossAotCompatibleBackedges()
            throws Exception {
        String output = run("""
                async fnc count(): int {
                  let value = 0;
                  while value < 4000 do
                    value = value + 1;
                  done
                  return value;
                }
                pub async routine main(): void {
                  stdio.stdout.write(await count());
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("4000|"), output);
        assertTrue(Long.parseLong(output.substring("4000|".length())) > 0,
                "while must lower to the same resumable budgeted backedge");
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "source-loop-quantum.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
