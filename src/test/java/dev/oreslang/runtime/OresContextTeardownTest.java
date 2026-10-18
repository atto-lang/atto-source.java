package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java regression tests for context teardown failure barriers. */
final class OresContextTeardownTest {
    private static final String[] NAMES = {
        "tasks", "async", "native", "http", "actors",
        "vm", "images", "gc", "flush"
    };

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final Runnable[] steps = new Runnable[NAMES.length];
        String fail = "";
        String secondFailure = "";

        Fixture() {
            for (int i = 0; i < NAMES.length; ++i) {
                String name = NAMES[i];
                steps[i] = () -> {
                    calls.add(name);
                    if (name.equals(fail) || name.equals(secondFailure))
                        throw new IllegalArgumentException(name + " failed");
                };
            }
        }

        void close() {
            OresContextTeardown.close(steps[0], steps[1], steps[2],
                    steps[3], steps[4], steps[5], steps[6], steps[7], steps[8]);
        }

        void expect(String... names) {
            assertEquals(List.of(names), calls);
        }
    }

    @Test
    void closesEverythingInDependencyOrder() {
        Fixture f = new Fixture();
        assertDoesNotThrow(f::close);
        f.expect(NAMES);
    }

    @Test
    void taskFenceFailureStopsAllFurtherTeardown() {
        Fixture f = new Fixture();
        f.fail = "tasks";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks");
    }

    @Test
    void asyncCarrierFailurePreservesProvidersAndVm() {
        Fixture f = new Fixture();
        f.fail = "async";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async");
    }

    @Test
    void nativeAndHttpFailuresAggregateWhileActorsStillStop() {
        Fixture f = new Fixture();
        f.fail = "native";
        f.secondFailure = "http";
        IllegalStateException error = assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors");
        assertNotNull(error.getCause());
        assertEquals(1, error.getCause().getSuppressed().length);
    }

    @Test
    void actorShutdownFailurePreventsVmShutdown() {
        Fixture f = new Fixture();
        f.fail = "actors";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors");
    }

    @Test
    void vmShutdownFailureNeverReclaimsSharedCode() {
        Fixture f = new Fixture();
        f.fail = "vm";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors", "vm");
    }

    @Test
    void sharedCodeFailurePreservesGarbageCollectorAndOutput() {
        Fixture f = new Fixture();
        f.fail = "images";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors", "vm", "images");
    }

    @Test
    void garbageCollectorShutdownFailureDoesNotFlush() {
        Fixture f = new Fixture();
        f.fail = "gc";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors",
                "vm", "images", "gc");
    }
}
