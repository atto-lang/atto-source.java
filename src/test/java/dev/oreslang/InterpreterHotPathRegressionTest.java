package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for two interpreter hot paths:
 *
 * - The per-statement live mutex-guard scan is skipped when no guard is
 *   live. Before that, holding a large Vector made every loop quadratic.
 * - Class-hierarchy walks (field and method lookup) use identity visited
 *   sets, not hash sets of record-typed class declarations.
 *
 * Both are pure speedups, so these tests pin the semantics around them and
 * add a coarse linear-time bound.
 */
final class InterpreterHotPathRegressionTest {
    private static String run(String name, String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    void inheritedFieldsAndMethodsResolveThroughMultiLevelAndDiamondHierarchies() throws Exception {
        String out = run("diamond.ores", """
                define module shapes
                  pub define class Base as
                    pub let int base_value = 1;
                    pub fnc base_twice(): int {
                      return self.base_value * 2;
                    }
                  end
                  pub define class Left extends Base as
                    pub let int left_value = 10;
                  end
                  pub define class Right extends Base as
                    pub let int right_value = 100;
                  end
                  pub define class Diamond extends Left, Right as
                    pub let int own_value = 1000;
                  end
                  pub define class Deep extends Diamond as
                    pub let int deep_value = 10000;
                  end
                end

                define module app
                  pub fnc main(): void {
                    val d = new Deep();
                    let int total = 0;
                    for let i = 0; i < 3; i++ do
                      total = total + d.base_value + d.left_value + d.right_value
                        + d.own_value + d.deep_value;
                    done
                    stdio.println(total);
                    stdio.println(d.base_twice());
                    return;
                  }
                end
                """);
        assertEquals("33333\n2", out);
    }

    @Test
    void lexicalGuardInsideLoopIsReleasedEveryIterationNextToLargeVector() throws Exception {
        String out = run("loop-guard.ores", """
                define module model
                  define class Counter as
                    pub let int value = 0;
                  end
                end

                define module app
                  pub fnc main(): void {
                    let mut Vector[float] big = [];
                    for let i = 0; i < 5000; i++ do
                      big.add(0.5);
                    done
                    val mutex = Mutex.new(new Counter());
                    for let round = 0; round < 3; round++ do
                      val lock_guard = mutex.lock();
                      let float s = 0.0;
                      for let i = 0; i < big.size; i++ do
                        s = s + big[i];
                      done
                      lock_guard.value = lock_guard.value + 1;
                    done
                    val again = mutex.lock();
                    stdio.println(again.value);
                    again.release();
                    return;
                  }
                end
                """);
        assertEquals("3", out);
    }

    @Test
    void largeVectorLoopsStayLinearWhenNoGuardIsLive() {
        // One leaked guard anywhere in the process turns the scan back on, so
        // this bound only means something when none is live.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dev.oreslang.runtime.OresMutex.liveGuardCount() == 0,
                "a guard leaked by another test keeps the full scan enabled");
        // 300k appends, writes and reads. Linear: a few seconds. With the old
        // per-statement deep scan this was quadratic (several minutes).
        String out = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> run("big-vector.ores", """
                define module app
                  pub fnc main(): void {
                    let mut Vector[float] big = [];
                    for let i = 0; i < 300000; i++ do
                      big.add(0.0);
                    done
                    for let i = 0; i < big.size; i++ do
                      big[i] = 0.5;
                    done
                    let float s = 0.0;
                    for let i = 0; i < big.size; i++ do
                      s = s + big[i];
                    done
                    stdio.println(s);
                    return;
                  }
                end
                """));
        assertEquals("150000.0", out);
    }
}
