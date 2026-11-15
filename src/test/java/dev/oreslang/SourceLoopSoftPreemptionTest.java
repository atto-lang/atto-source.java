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
                      if Integer.rem(i, 3) == 0; do
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
    void provenSynchronousScalarHelperRemainsCallableUnderMutexGuard()
            throws Exception {
        assertEquals("42", run("""
                define class Counter as
                  pub let int value = 0;
                end
                fnc add_one(value: int): int {
                  return value + 1;
                }
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  let answer = add_one(41);
                  lock_guard.release();
                  stdio.stdout.write(answer);
                  return;
                }
                """));
    }

    @Test
    void asyncHelperCannotLaunchUnderLiveMutexGuardEvenWithoutAwait()
            throws Exception {
        // A Future return type must not bypass child-launch admission.
        String text = """
                define class Counter as
                  pub let int value = 0;
                end
                async fnc blocked_source(): int {
                  stdio.stdout.write("ILLEGAL_ASYNC_CHILD_STARTED");
                  return 7;
                }
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  let pending = blocked_source();
                  lock_guard.release();
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(
                        OresLanguage.ID, text, "async-guard-prelaunch.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        RuntimeException rejected;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            rejected = assertThrows(RuntimeException.class, () -> context.eval(src));
        }
        assertTrue(rejected.getMessage().contains("MutexGuard"),
                "explicit async child Task must be denied before launch: " + rejected);
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "invalid async source child executed a guest side effect");
    }

    @Test
    void guardedSourceHelperRejectsBeforeEvaluatingEffectfulArguments()
            throws Exception {
        assertGuardedCallRejectedWithoutOutput("""
                define class Counter as
                  pub let int value = 0;
                end
                fnc effectful_argument(): int {
                  stdio.stdout.write("ILLEGAL_ARGUMENT_COMMITTED");
                  return 4096;
                }
                fnc count_in_helper(n: int): int {
                  let count = 0;
                  for (let i = 0; i < n; i = i + 1) do
                    count = count + 1;
                  done
                  return count;
                }
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  val int answer = count_in_helper(effectful_argument());
                  lock_guard.release();
                  stdio.stdout.write(answer);
                  return;
                }
                """);
    }

    @Test
    void guardedSourceMethodRejectsBeforeEvaluatingEffectfulArguments()
            throws Exception {
        assertGuardedCallRejectedWithoutOutput("""
                define class Counter as
                  pub let int value = 0;
                end
                define class Calculator as
                  pub count_in_helper(n: int): int {
                    let count = 0;
                    for (let i = 0; i < n; i = i + 1) do
                      count = count + 1;
                    done
                    return count;
                  }
                end
                fnc effectful_argument(): int {
                  stdio.stdout.write("ILLEGAL_MEMBER_ARGUMENT_COMMITTED");
                  return 4096;
                }
                pub async routine main(): void {
                  val Calculator calculator = new Calculator();
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  val int answer = calculator.count_in_helper(effectful_argument());
                  lock_guard.release();
                  stdio.stdout.write(answer);
                  return;
                }
                """);
    }

    @Test
    void guardedStaticSourceHelperRejectsBeforeEffectfulArguments()
            throws Exception {
        assertGuardedCallRejectedWithoutOutput("""
                define class Counter as
                  pub let int value = 0;
                end
                define class Calculator as
                  pub static fnc count_in_helper(n: int): int {
                    let count = 0;
                    for (let i = 0; i < n; i = i + 1) do
                      count = count + 1;
                    done
                    return count;
                  }
                end
                fnc effectful_argument(): int {
                  stdio.stdout.write("ILLEGAL_STATIC_ARGUMENT_COMMITTED");
                  return 4096;
                }
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  val int answer = Calculator.count_in_helper(effectful_argument());
                  lock_guard.release();
                  stdio.stdout.write(answer);
                  return;
                }
                """);
    }

    @Test
    void settledFutureFlattenCannotStartNestedTaskUnderLiveMutexGuard()
            throws Exception {
        // Future.flatten is a built-in INVOKABLE with a declared Future
        // result, but its implementation starts a new scheduler Task even
        // when the source Future has already settled. Its admission must
        // therefore fail before invocation, not at a later implicit await.
        // Current main also checks Future.flatten statically (it requires
        // Future<Future<T>>), so this source may be refused before execution;
        // either way no chained Task may start and no output may appear.
        assertGuardedCallRejectedWithoutOutput("Future.flatten requires Future<Future<T>>", """
                define class Counter as
                  pub let int value = 0;
                end
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("stop");
                  await worker.get_done();
                  val completed = worker.get_done();
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  val future = completed.flatten();
                  lock_guard.release();
                  stdio.stdout.write("ILLEGAL_FUTURE_CHAIN_STARTED");
                  return;
                }
                """);
    }

    @Test
    void guardedReceiverMethodRejectsBeforeEvaluatingEffectfulArguments()
            throws Exception {
        // The receiver is a MutexGuard, not a direct OresObject. Resolving
        // its underlying checked source method after evaluating arguments
        // would commit a guest side effect before failing admission.
        assertGuardedCallRejectedWithoutOutput("""
                define class Calculator as
                  pub let int value = 0;
                  pub count_in_helper(n: int): int {
                    let total = 0;
                    for (let i = 0; i < n; i = i + 1) do
                      total = total + 1;
                    done
                    return total;
                  }
                end
                fnc effectful_argument(): int {
                  stdio.stdout.write("ILLEGAL_GUARD_RECEIVER_ARGUMENT");
                  return 4096;
                }
                pub async routine main(): void {
                  val mutex = Mutex.new(new Calculator());
                  val lock_guard = mutex.lock();
                  val int result = lock_guard.count_in_helper(effectful_argument());
                  lock_guard.release();
                  stdio.stdout.write(result);
                  return;
                }
                """);
    }

    @Test
    void dynamicCallbackInNestedExpressionCannotRunUnderLiveMutexGuard()
            throws Exception {
        // This must be caught by the synchronous eval(CallExpr) path too:
        // a lexical Fnc value has no proven non-suspending effect summary,
        // and the enclosing arithmetic expression may take the hot path.
        String code = """
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val Fnc<int> callback = || -> {
                    stdio.stdout.write("ILLEGAL_CALLBACK_STARTED");
                    return 41;
                  };
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  val int answer = 1 + callback();
                  lock_guard.release();
                  stdio.stdout.write(answer);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "nested-dynamic-call-under-lock_guard.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        RuntimeException rejected;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            rejected = assertThrows(RuntimeException.class, () -> context.eval(source));
        }
        assertTrue(rejected.getMessage().contains("MutexGuard"),
                "unproven dynamic Fnc must fail before invocation: " + rejected);
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "a rejected Fnc callback committed guest effects");
    }

    @Test
    void dynamicCallbackCanRunOnceMutexGuardHasBeenReleased()
            throws Exception {
        assertEquals("42", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val Fnc<int> callback = || -> {
                    return 41;
                  };
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  lock_guard.release();
                  stdio.stdout.write(1 + callback());
                  return;
                }
                """));
    }

    @Test
    void nestedCpuHelperCannotStartUnderLiveMutexGuard() throws Exception {
        // A synchronous helper can contain an implicit safepoint. Refuse
        // its Task admission BEFORE invoking the source body, so an invalid
        // call cannot execute arbitrary guest effects or take a guard loan.
        String sourceText = """
                define class Counter as
                  pub let int value = 0;
                end
                fnc count_in_helper(n: int): int {
                  stdio.stdout.write("ILLEGAL_CHILD_STARTED");
                  let count = 0;
                  for (let i = 0; i < n; i = i + 1) do
                    count = count + 1;
                  done
                  return count;
                }
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  let answer = count_in_helper(4096);
                  lock_guard.release();
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "nested-cpu-lock_guard.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        RuntimeException rejected;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            rejected = assertThrows(RuntimeException.class, () -> context.eval(source));
        }
        assertTrue(rejected.getMessage().contains("MutexGuard"),
                "implicit nested task must be denied under a live guard: " + rejected);
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "rejected nested source callable executed a guest side effect");
    }

    @Test
    void synchronousReceiveHelperLoopPreservesOneLogicalMessageAndCooperates()
            throws Exception {
        // A nested plain synchronous function must carry its heap-owned
        // call/loop continuation through the same actor SourceTask trampoline.
        // This tests a transitive loop, rather than a loop directly in receive.
        String output = run("""
                define actor Worker as
                  let int count;
                  count_in_helper(n: int): int {
                    let result = 0;
                    for (let i = 0; i < n; i = i + 1) do
                      result = result + 1;
                    done
                    return result;
                  }
                  on_start(): void {
                    self.count = 0;
                    return;
                  }
                  receive(ActorMail<String> mail): void {
                    if mail.value == "first"; do
                      self.count = self.count_in_helper(4096);
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
        assertTrue(output.startsWith("4096|4097|"),
                "nested synchronous call replayed or admitted a newer message: " + output);
        long cooperates = Long.parseLong(output.substring("4096|4097|".length()));
        assertTrue(cooperates > 0,
                "a nested synchronous helper loop must release the carrier without explicit await");
    }

    @Test
    void twoSourceActorsRetainDistinctReceiversAcrossHelperSuspension()
            throws Exception {
        // Both instances have the same source class but distinct immutable
        // actor identities. Each cooperative helper mutates only its original
        // receiver, even when physical carriers hand off between their tasks.
        String output = run("""
                define actor Worker as
                  let int count;
                  on_start(): void {
                    self.count = 0;
                    return;
                  }
                  count_in_helper(n: int): int {
                    for (let i = 0; i < n; i = i + 1) do
                      self.count = self.count + 1;
                    done
                    return self.count;
                  }
                  receive(ActorMail<int> mail): void {
                    self.send(self.count_in_helper(mail.value));
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val left = spawn Worker();
                  val right = spawn Worker();
                  await left.get_ready();
                  await right.get_ready();
                  left.send(4096);
                  right.send(3072);
                  val first = (await left.outputs.next()).value.unwrap();
                  val second = (await right.outputs.next()).value.unwrap();
                  stdio.stdout.write(first.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.value);
                  await left.get_done();
                  await right.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("4096|3072|"),
                "cooperating actor tasks lost their distinct sealed receivers: " + output);
        long handoffs = Long.parseLong(output.substring("4096|3072|".length()));
        assertTrue(handoffs > 0,
                "actor helper continuation should safely resume after cooperation");
    }

    @Test
    void cancelledNestedActorHelperPhysicallyRetiresBeforeDone()
            throws Exception {
        // A nested synchronous loop is an implicitly scheduled SourceTask.
        // Cancellation of its parent must not free actor-owned state while
        // the helper still owns a resumable receiver frame.
        String setting = "ores.runtime.actor.shared.parallelism";
        String previous = System.getProperty(setting);
        System.setProperty(setting, "1");
        try {
            String output = run("""
                    define actor Busy as
                      let int spins;
                      on_start(): void {
                        self.spins = 0;
                        return;
                      }
                      spin_forever(): void {
                        while (true) do
                          self.spins = self.spins + 1;
                        done
                      }
                      receive(ActorMail<String> mail): void {
                        self.send("entered");
                        self.spin_forever();
                        return;
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
                      val busy = spawn Busy();
                      val peer = spawn Peer();
                      await busy.get_ready();
                      await peer.get_ready();
                      busy.send("start");
                      val entered = (await busy.outputs.next()).value.unwrap();
                      peer.send("start");
                      val completed = (await peer.outputs.next()).value.unwrap();
                      stdio.stdout.write(entered.value);
                      stdio.stdout.write("|");
                      stdio.stdout.write(completed.value);
                      busy.stop();
                      await busy.get_done();
                      await peer.get_done();
                      stdio.stdout.write("|retired");
                      return;
                    }
                    """);
            assertEquals("entered|peer|retired", output,
                    "nested source helper cancellation must drain before actor done");
        } finally {
            if (previous == null) System.clearProperty(setting);
            else System.setProperty(setting, previous);
        }
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
                  val lock_guard = mutex.lock();
                  for (let i = 0; i < 2000; i = i + 1) do
                    lock_guard.value = lock_guard.value + 1;
                  done
                  stdio.stdout.write(lock_guard.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  lock_guard.release();
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
                              val lock_guard = mutex.lock();
                            """ + operation + """
                              lock_guard.release();
                              return;
                            }
                            """), operation);
            assertTrue(rejected.getMessage().contains("MutexGuard"),
                    operation + ": " + rejected.getMessage());
        }
    }

    @Test
    void pendingActorCompletionCannotParkWhileHoldingLiveMutexGuard() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
                define class Counter as
                  pub let int value = 0;
                end
                define actor Idle as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val idle = spawn Idle();
                  await idle.get_ready();
                  val pending = idle.get_done();
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  await pending;
                  lock_guard.release();
                  return;
                }
                """));
        assertTrue(failure.getMessage().contains("MutexGuard"),
                "a pending Future must not park with the lock held: " + failure.getMessage());
    }

    @Test
    void settledActorCompletionIsAlsoRejectedByStaticNoYieldChecking() {
        // Source ownership checking forbids await under a guard even when the
        // Future is known to be settled at runtime. The runtime inline path
        // remains relevant to host calls that have escaped source admission.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
                define class Counter as
                  pub let int value = 0;
                end
                define actor Idle as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val idle = spawn Idle();
                  await idle.get_ready();
                  idle.send("stop");
                  await idle.get_done();
                  val mutex = Mutex.new(new Counter());
                  val lock_guard = mutex.lock();
                  await idle.get_done();
                  lock_guard.release();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """));
        assertTrue(failure.getMessage().contains("MutexGuard"),
                "static no-yield admission must reject even a settled await: "
                        + failure.getMessage());
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
                  val lock_guard = mutex.lock();
                  lock_guard.value = 7;
                  lock_guard.release();
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
                          val lock_guard = mutex.lock();
                          rt cooperate;
                          lock_guard.release();
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
                  val lock_guard = mutex.lock();
                  lock_guard.value = 7;
                  lock_guard.release();
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
                  val lock_guard = mutex.lock();
                  rt cooperate;
                  lock_guard.release();
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
                  val lock_guard = mutex.lock();
                  lock_guard.value = lock_guard.value + 1;
                  lock_guard.release();
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
                  val lock_guard = mutex.lock();
                  let i = 0;
                  while (i < 1024) do
                    lock_guard.value = lock_guard.value + 1;
                    i = i + 1;
                  done
                  stdio.stdout.write(lock_guard.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  lock_guard.release();

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
    void twoInfiniteNestedHelperActorsCannotStarveSingleCarrierPeer()
            throws Exception {
        // Both same-class actor instances run nested synchronous CPU loops.
        // On one SHARED carrier, neither may monopolize the scheduler;
        // STOP/get_done must fence both physical logical continuations.
        final String setting = "ores.runtime.actor.shared.parallelism";
        final String before = System.getProperty(setting);
        System.setProperty(setting, "1");
        try {
            String output = runAtto("""
                    define actor Greedy as
                      spin_forever(): void {
                        while true do
                          let n = 1 + 1;
                        done
                      }

                      receive(ActorMail<String> mail): void {
                        self.send("entered");
                        self.spin_forever();
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
                      val first = spawn Greedy();
                      val second = spawn Greedy();
                      val peer = spawn Peer();
                      await first.get_ready();
                      await second.get_ready();
                      await peer.get_ready();

                      first.send("start");
                      val first_entered = (await first.outputs.next()).value.unwrap();
                      // The second helper must be admitted even though the
                      // first has already entered its infinite CPU loop.
                      second.send("start");
                      val second_entered = (await second.outputs.next()).value.unwrap();
                      peer.send("start");
                      val reply = (await peer.outputs.next()).value.unwrap();
                      stdio.stdout.write(reply.value);
                      stdio.stdout.write("|");
                      stdio.stdout.write(process.descriptor.source_loop_cooperates);

                      first.stop();
                      second.stop();
                      await first.get_done();
                      await second.get_done();
                      await peer.get_done();
                      return;
                    }
                    """);
            assertTrue(output.startsWith("peer|"),
                    "two nested infinite helpers starved peer actor: " + output);
            assertTrue(Long.parseLong(output.substring("peer|".length())) > 0,
                    "a nested synchronous loop did not yield its carrier");
        } finally {
            if (before == null) System.clearProperty(setting);
            else System.setProperty(setting, before);
        }
    }

    @Test
    void sustainedMailboxTrafficRemainsOrderedWhileInfiniteActorIsCooperating()
            throws Exception {
        String setting = "ores.runtime.actor.shared.parallelism";
        String before = System.getProperty(setting);
        System.setProperty(setting, "1");
        try {
            String output = run("""
                    define actor Greedy as
                      receive(ActorMail<String> mail): void {
                        self.send("entered");
                        while true do
                          let n = 1 + 1;
                        done
                      }
                    end

                    define actor Peer as
                      let int count;
                      on_start(): void {
                        self.count = 0;
                        return;
                      }
                      receive(ActorMail<String> mail): void {
                        if mail.value == "stop" then
                          self.end();
                          return;
                        fi
                        self.count = self.count + 1;
                        self.send(self.count);
                        return;
                      }
                    end

                    pub async routine main(): void {
                      val greedy = spawn Greedy();
                      val peer = spawn Peer();
                      await greedy.get_ready();
                      await peer.get_ready();
                      greedy.send("start");
                      val entered = (await greedy.outputs.next()).value.unwrap();

                      // Each reply must make progress even though a separate
                      // shared-lane actor never voluntarily exits its loop.
                      let i = 1;
                      while i <= 64 do
                        peer.send("tick");
                        val reply = (await peer.outputs.next()).value.unwrap();
                        if reply.value != i then
                          stdio.stdout.write("replayed-or-reordered");
                          return;
                        fi
                        i = i + 1;
                      done
                      peer.send("stop");
                      greedy.stop();
                      await greedy.get_done();
                      await peer.get_done();
                      stdio.stdout.write("64|");
                      stdio.stdout.write(process.descriptor.source_loop_cooperates);
                      return;
                    }
                    """);
            assertTrue(output.startsWith("64|"),
                    "progress or mailbox ordering failure: " + output);
            assertTrue(Long.parseLong(output.substring("64|".length())) >= 1,
                    "infinite CPU actor never returned its physical carrier");
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

    private static void assertGuardedCallRejectedWithoutOutput(String alternative, String code)
            throws Exception {
        assertRejectedWithoutOutput(code, alternative);
    }

    private static void assertGuardedCallRejectedWithoutOutput(String code)
            throws Exception {
        assertRejectedWithoutOutput(code, null);
    }

    private static void assertRejectedWithoutOutput(String code, String alternative)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "guarded-argument-preflight.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        RuntimeException rejected;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            rejected = assertThrows(RuntimeException.class, () -> context.eval(source));
        }
        assertTrue(rejected.getMessage().contains("MutexGuard")
                        || (alternative != null && rejected.getMessage().contains(alternative)),
                "the pre-invocation no-yield gate must reject the call: " + rejected);
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "a rejected nested source call evaluated an effectful argument");
    }

    private static String runAtto(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "two-infinite-actors.atto")
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
