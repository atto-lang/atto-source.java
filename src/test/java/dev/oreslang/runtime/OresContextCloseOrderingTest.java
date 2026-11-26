package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresContextCloseOrderingTest {
    @Test
    void independentHostServicesBothRunAndPreserveFailureOrder() {
        var calls = new ArrayList<String>();
        var first = new IllegalStateException("native-io");
        var second = new IllegalArgumentException("http");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> OresContext.closeIndependentHostServices(
                        () -> { calls.add("native"); throw first; },
                        () -> { calls.add("http"); throw second; }));

        assertSame(first, actual);
        assertEquals(List.of("native", "http"), calls);
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
    }

    @Test
    void fatalSecondHostFailureDominatesEarlierOrdinaryFailureAfterBothRun() {
        var calls = new ArrayList<String>();
        var ordinary = new IllegalStateException("native ordinary");
        var fatal = new OutOfMemoryError("http fatal");

        OutOfMemoryError actual = assertThrows(
                OutOfMemoryError.class,
                () -> OresContext.closeIndependentHostServices(
                        () -> { calls.add("native"); throw ordinary; },
                        () -> { calls.add("http"); throw fatal; }));

        assertSame(fatal, actual);
        assertEquals(List.of("native", "http"), calls);
        assertTrue(java.util.Arrays.asList(actual.getSuppressed()).contains(ordinary));
    }

    @Test
    void actorPhysicalFailurePreservesVmAndFinalResourcesForRetry() {
        var calls = new ArrayList<String>();
        var actorFailure = new IllegalStateException("actor carrier still live");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> OresContext.runDependentClosePhases(
                        () -> calls.add("async"),
                        () -> { calls.add("actors"); throw actorFailure; },
                        () -> calls.add("vm"),
                        () -> calls.add("resources")));

        assertSame(actorFailure, actual);
        assertEquals(List.of("async", "actors"), calls,
                "VM/code-image teardown must not run while an actor carrier is live");
    }

    @Test
    void asyncPhysicalFailurePreservesAllGuestRuntimeSuccessors() {
        var calls = new ArrayList<String>();

        assertThrows(IllegalStateException.class,
                () -> OresContext.runDependentClosePhases(
                        () -> { calls.add("async"); throw new IllegalStateException("async live"); },
                        () -> calls.add("actors"),
                        () -> calls.add("vm"),
                        () -> calls.add("resources")));

        assertEquals(List.of("async"), calls);
    }

    @Test
    void successfulDependencyChainReleasesResourcesLast() {
        var calls = new ArrayList<String>();
        OresContext.runDependentClosePhases(
                () -> calls.add("async"),
                () -> calls.add("actors"),
                () -> calls.add("vm"),
                () -> calls.add("resources"));
        assertEquals(List.of("async", "actors", "vm", "resources"), calls);
    }
}
