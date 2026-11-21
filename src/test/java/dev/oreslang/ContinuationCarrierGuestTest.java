package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Compiled guest programs that drive suspends/await, Task continuations,
 * actors, readch/writech and the {@code <<<}/{@code >>>} shift operators through the
 * carrier pools (continuation locality, idle lane stealing). Each program
 * forces values across suspension points, so a lost, duplicated or misrouted
 * continuation changes the printed result. Repeated to shake out ordering.
 */
final class ContinuationCarrierGuestTest {

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Producer and consumer Tasks rendezvous over writech/readch; values are packed with <<< and unpacked with >>>. */
    @RepeatedTest(5)
    void tasksRendezvousOverChannelsAcrossAwaitsAndShifts() throws Exception {
        String program = """
                suspends fnc produce(Channel<int> ch, int count, int tag): int {
                  for (let i = 0; i < count; i = i + 1) {
                    await (nb writech ch, (tag <<< 16) + i);
                    rt cooperate;
                  }
                  return count;
                }

                suspends fnc consume(Channel<int> ch, int count): int {
                  let int sum = 0;
                  for (let i = 0; i < count; i = i + 1) {
                    val int packed = await (nb readch ch);
                    sum = sum + (packed >>> 16) + (packed & 65535);
                  }
                  return sum;
                }

                pub suspends fnc main(): void {
                  val Channel<int> ch = Channel.new<int>(0);
                  let Future<int> a = task { return produce(ch, 40, 1); };
                  let Future<int> b = task { return produce(ch, 40, 2); };
                  let Future<int> c = task { return consume(ch, 80); };
                  val int total = await c;
                  val int sent = (await a) + (await b);
                  stdio.stdout.write(sent);
                  stdio.stdout.write("|");
                  stdio.stdout.write(total);
                  return;
                }
                """;
        // tags: 40*1 + 40*2 = 120; indexes: 2 * (0+..+39) = 1560.
        assertEquals("80|1680", run(program, "continuation-channels-shifts.atto"));
    }

    /** A deep chain of nested Tasks, each awaiting its child; every frame's local survives resumption. */
    @RepeatedTest(5)
    void nestedTaskChainsResumeEveryFrameExactlyOnce() throws Exception {
        String program = """
                suspends fnc level(int depth): int {
                  if depth == 0 then
                    rt cooperate;
                    return 1;
                  fi
                  val int mine = depth <<< 1;
                  let Future<int> child = task { return level(depth - 1); };
                  rt cooperate;
                  val int below = await child;
                  return below + (mine >>> 1);
                }

                pub suspends fnc main(): void {
                  let Future<int> top = task { return level(30); };
                  stdio.stdout.write(await top);
                  return;
                }
                """;
        // 1 + (1 + 2 + ... + 30) = 466
        assertEquals("466", run(program, "continuation-nested-tasks.atto"));
    }

    /** Actors whose receive awaits a channel read; replies go through actor outputs. */
    @RepeatedTest(5)
    void actorsAwaitChannelsInsideReceiveAndReplyInOrder() throws Exception {
        String program = """
                define actor Doubler as
                  let Channel<int> feed;
                  let int seen;

                  on_start(): void {
                    self.feed = Channel.new<int>(4);
                    self.seen = 0;
                    return;
                  }

                  receive(ActorMail<int> mail): void {
                    writech self.feed, mail.value <<< 1;
                    val int doubled = await (nb readch self.feed);
                    // Actor state written after the suspension: the resumed turn
                    // still owns the actor, possibly on a different carrier.
                    self.seen = self.seen + 1;
                    val int turn = self.seen;
                    self.send((doubled >>> 1) + turn * 1000);
                    if turn == 5 then
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val left = spawn Doubler();
                  val right = spawn Doubler();
                  await left.get_ready();
                  await right.get_ready();
                  for (let i = 1; i <= 5; i = i + 1) {
                    left.send(i);
                    right.send(i * 10);
                  }
                  let int total = 0;
                  for (let i = 1; i <= 5; i = i + 1) {
                    // ActorOutput payload types are not inferred at the use site, so
                    // bind the output first and give the payload an explicit type.
                    val leftOut = (await left.outputs.next()).value.unwrap();
                    val rightOut = (await right.outputs.next()).value.unwrap();
                    val int fromLeft = leftOut.value;
                    val int fromRight = rightOut.value;
                    total = total + fromLeft + fromRight;
                  }
                  await left.get_done();
                  await right.get_done();
                  stdio.stdout.write(total);
                  return;
                }
                """;
        // per actor: sum(seen*1000) = 15000; values: (1..5) = 15 and (10..50) = 150.
        assertEquals("30165", run(program, "continuation-actor-channels.atto"));
    }

    /** Many short-lived Tasks hop between carriers; the fold must equal the closed form. */
    @RepeatedTest(5)
    void manyTasksWithCooperatesFoldDeterministically() throws Exception {
        String program = """
                suspends fnc step(int i): int {
                  rt cooperate;
                  val int shifted = (i <<< 4) >>> 4;
                  rt cooperate;
                  return shifted * 2;
                }

                pub suspends fnc main(): void {
                  let int total = 0;
                  for (let round = 0; round < 10; round = round + 1) {
                    let Future<int> a = task { return step(round * 3); };
                    let Future<int> b = task { return step(round * 3 + 1); };
                    let Future<int> c = task { return step(round * 3 + 2); };
                    total = total + (await a) + (await b) + (await c);
                  }
                  stdio.stdout.write(total);
                  return;
                }
                """;
        // 2 * (0 + 1 + ... + 29) = 870
        assertEquals("870", run(program, "continuation-many-tasks.atto"));
    }
}
