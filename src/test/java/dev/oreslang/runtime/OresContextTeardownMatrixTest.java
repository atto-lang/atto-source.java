package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive host-side fault injection for the exact nine-stage shutdown
 * contract. 512 masks cover every subset of failing stage callbacks.
 */
final class OresContextTeardownMatrixTest {
    private static final String[] STEPS = {
        "tasks", "async", "nativeIo", "http", "actors",
        "vm", "codeImages", "gc", "flush"
    };

    @Test
    void everyFailureCombinationPreservesTeardownDependencies() {
        for (int mask = 0; mask < (1 << STEPS.length); mask++) {
            final int failures = mask;
            List<String> executed = new ArrayList<>();
            Runnable[] actions = new Runnable[STEPS.length];
            Throwable[] problems = new Throwable[STEPS.length];
            for (int i = 0; i < STEPS.length; i++) {
                final int index = i;
                problems[i] = (i & 1) == 0
                        ? new IllegalStateException("injected-" + i)
                        : new AssertionError("injected-" + i);
                actions[i] = () -> {
                    executed.add(STEPS[index]);
                    if ((failures & (1 << index)) != 0) {
                        if (problems[index] instanceof RuntimeException failure)
                            throw failure;
                        throw (Error) problems[index];
                    }
                };
            }

            Throwable observed = null;
            try {
                OresContextTeardown.close(actions[0], actions[1], actions[2],
                        actions[3], actions[4], actions[5], actions[6],
                        actions[7], actions[8]);
            } catch (RuntimeException | Error failure) {
                observed = failure;
            }

            int expectedCount;
            if ((mask & 1) != 0) expectedCount = 1;
            else if ((mask & 2) != 0) expectedCount = 2;
            else if ((mask & 0b11100) != 0) expectedCount = 5;
            else if ((mask & (1 << 5)) != 0) expectedCount = 6;
            else if ((mask & (1 << 6)) != 0) expectedCount = 7;
            else if ((mask & (1 << 7)) != 0) expectedCount = 8;
            else expectedCount = 9;

            assertEquals(expectedCount, executed.size(), "mask=" + mask);
            for (int i = 0; i < expectedCount; i++) {
                assertEquals(STEPS[i], executed.get(i), "mask=" + mask);
            }

            int firstFailure = -1;
            for (int i = 0; i < expectedCount; i++) {
                if ((mask & (1 << i)) != 0) {
                    firstFailure = i;
                    break;
                }
            }
            if (firstFailure == -1) {
                assertNull(observed, "mask=" + mask);
            } else if (firstFailure == 8) {
                assertSame(problems[8], observed, "flush error must propagate");
            } else {
                assertInstanceOf(IllegalStateException.class, observed);
                assertSame(problems[firstFailure], observed.getCause(),
                        "wrong root error, mask=" + mask);
                if (firstFailure >= 2 && firstFailure <= 4) {
                    int suppressed = 0;
                    for (int i = firstFailure + 1; i <= 4; i++)
                        if ((mask & (1 << i)) != 0) suppressed++;
                    assertEquals(suppressed,
                            observed.getCause().getSuppressed().length,
                            "lost provider/actor failure, mask=" + mask);
                }
            }
        }
    }

    @Test
    void rejectsEachNullActionBeforeAttemptingAnyShutdown() {
        for (int missing = 0; missing < STEPS.length; missing++) {
            Runnable[] actions = new Runnable[STEPS.length];
            for (int i = 0; i < actions.length; i++)
                actions[i] = () -> fail("teardown began before null validation");
            actions[missing] = null;
            assertThrows(NullPointerException.class,
                    () -> OresContextTeardown.close(actions[0], actions[1],
                            actions[2], actions[3], actions[4], actions[5],
                            actions[6], actions[7], actions[8]));
        }
    }

    @Test
    void repeatedProviderThrowableDoesNotSelfSuppressOrReachVm() {
        IllegalStateException shared = new IllegalStateException("shared provider error");
        List<String> executed = new ArrayList<>();
        Runnable failOnce = () -> {
            executed.add("provider");
            throw shared;
        };
        Runnable vm = () -> fail("VM must stay pinned after provider failure");
        IllegalStateException observed = assertThrows(IllegalStateException.class,
                () -> OresContextTeardown.close(
                        () -> executed.add("tasks"), () -> executed.add("async"),
                        failOnce, failOnce, () -> executed.add("actors"),
                        vm, vm, vm, vm));
        assertSame(shared, observed.getCause());
        assertEquals(0, shared.getSuppressed().length);
        assertEquals(List.of("tasks", "async", "provider", "provider", "actors"),
                executed);
    }
}
