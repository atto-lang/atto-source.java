package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the trusted Java lifecycle primitive, NOT a .atto guest, compiler
 * lowering, authorized Truffle attachment or actor scheduler migration.
 */
final class NativeTelemetryScopesTest {
    @Test
    void disabledScopeUsesSharedNoopAndDoesNotRecord() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        NativeTelemetryScopes.SpanToken one =
                runtime.beginFromTrustedRuntime(carrier, "checkout");
        NativeTelemetryScopes.SpanToken two =
                runtime.beginFromTrustedRuntime(carrier, "checkout");
        assertSame(one, two, "disabled fast path should reuse a token");
        runtime.endFromTrustedRuntime(carrier, two);
        runtime.endFromTrustedRuntime(carrier, one);
        assertEquals(0, runtime.snapshotForTrustedHost().started());
        assertEquals(0, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
    }

    @Test
    void disabledScopesAreBoundToTheirContinuationWithoutPerStartAllocation() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier first = runtime.createCarrierForTrustedRuntime();
        NativeTelemetryScopes.Carrier second = runtime.createCarrierForTrustedRuntime();
        NativeTelemetryScopes.SpanToken one =
                runtime.beginFromTrustedRuntime(first, "disabled");
        NativeTelemetryScopes.SpanToken oneAgain =
                runtime.beginFromTrustedRuntime(first, "disabled");
        NativeTelemetryScopes.SpanToken other =
                runtime.beginFromTrustedRuntime(second, "disabled");
        assertSame(one, oneAgain);
        assertNotSame(one, other);
        assertThrows(IllegalStateException.class,
                () -> runtime.endFromTrustedRuntime(second, one));
        runtime.endFromTrustedRuntime(first, one);
        runtime.endFromTrustedRuntime(second, other);
        assertEquals(1, runtime.snapshotForTrustedHost().rejected());
        assertEquals(0, runtime.snapshotForTrustedHost().started());
    }

    @Test
    void opaqueCapabilityAndCarrierExposeNoPublicConstructors() {
        assertTrue(java.util.Arrays.stream(
                NativeTelemetryScopes.SpanToken.class.getDeclaredConstructors())
                .noneMatch(ctor -> java.lang.reflect.Modifier.isPublic(ctor.getModifiers())));
        assertTrue(java.util.Arrays.stream(
                NativeTelemetryScopes.Carrier.class.getDeclaredConstructors())
                .noneMatch(ctor -> java.lang.reflect.Modifier.isPublic(ctor.getModifiers())));
        assertTrue(java.util.Arrays.stream(
                NativeTelemetryScopes.SpanToken.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("close")
                        || method.getName().equals("close_scope")
                        || method.getName().equals("begin")));
    }

    @Test
    void enabledScopeClosesExactlyOnce() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        runtime.setEnabledFromTrustedHost(true);
        NativeTelemetryScopes.SpanToken span =
                runtime.beginFromTrustedRuntime(carrier, "checkout");
        runtime.endFromTrustedRuntime(carrier, span);
        assertEquals(1, runtime.snapshotForTrustedHost().started());
        assertEquals(1, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
        assertThrows(IllegalStateException.class,
                () -> runtime.endFromTrustedRuntime(carrier, span));
        assertEquals(1, runtime.snapshotForTrustedHost().rejected());
        assertEquals(1, runtime.snapshotForTrustedHost().ended());
    }

    @Test
    void nestedScopesRequireLifoAndRejectOutOfOrderCloseWithoutDamage() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        runtime.setEnabledFromTrustedHost(true);
        NativeTelemetryScopes.SpanToken outer =
                runtime.beginFromTrustedRuntime(carrier, "outer");
        NativeTelemetryScopes.SpanToken inner =
                runtime.beginFromTrustedRuntime(carrier, "inner");
        assertThrows(IllegalStateException.class,
                () -> runtime.endFromTrustedRuntime(carrier, outer));
        assertEquals(2, runtime.snapshotForTrustedHost().active());
        runtime.endFromTrustedRuntime(carrier, inner);
        runtime.endFromTrustedRuntime(carrier, outer);
        assertEquals(2, runtime.snapshotForTrustedHost().started());
        assertEquals(2, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
    }

    @Test
    void crossContinuationCloseFailsEvenAtMatchingStackDepth() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier first = runtime.createCarrierForTrustedRuntime();
        NativeTelemetryScopes.Carrier second = runtime.createCarrierForTrustedRuntime();
        runtime.setEnabledFromTrustedHost(true);
        NativeTelemetryScopes.SpanToken one =
                runtime.beginFromTrustedRuntime(first, "identical");
        NativeTelemetryScopes.SpanToken two =
                runtime.beginFromTrustedRuntime(second, "identical");
        assertThrows(IllegalStateException.class,
                () -> runtime.endFromTrustedRuntime(second, one));
        runtime.endFromTrustedRuntime(first, one);
        runtime.endFromTrustedRuntime(second, two);
        assertEquals(2, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
    }

    @Test
    void continuationScopeCanCloseOnADifferentJavaWorkerThread() throws Exception {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        runtime.setEnabledFromTrustedHost(true);
        NativeTelemetryScopes.SpanToken scope =
                runtime.beginFromTrustedRuntime(carrier, "thread-migration");
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> closed = pool.submit(() ->
                    runtime.endFromTrustedRuntime(carrier, scope));
            closed.get();
        }
        assertEquals(1, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
    }

    @Test
    void retirementEndsAllActiveScopesThenRejectsReuse() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        runtime.setEnabledFromTrustedHost(true);
        NativeTelemetryScopes.SpanToken outer =
                runtime.beginFromTrustedRuntime(carrier, "outer");
        NativeTelemetryScopes.SpanToken inner =
                runtime.beginFromTrustedRuntime(carrier, "inner");
        runtime.retireContinuationFromTrustedRuntime(carrier);
        runtime.retireContinuationFromTrustedRuntime(carrier);
        assertEquals(2, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
        assertThrows(IllegalStateException.class,
                () -> runtime.endFromTrustedRuntime(carrier, inner));
        assertThrows(IllegalStateException.class,
                () -> runtime.endFromTrustedRuntime(carrier, outer));
        assertThrows(IllegalStateException.class,
                () -> runtime.beginFromTrustedRuntime(carrier, "reused"));
    }

    @Test
    void dormantSameProcessHostTogglesDoNotChangeBusinessResult() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        List<Integer> results = new ArrayList<>();
        for (boolean enabled : new boolean[] {false, true, false, true}) {
            runtime.setEnabledFromTrustedHost(enabled);
            NativeTelemetryScopes.SpanToken span =
                    runtime.beginFromTrustedRuntime(carrier, "business");
            try {
                results.add(51);
            } finally {
                runtime.endFromTrustedRuntime(carrier, span);
            }
        }
        assertEquals(List.of(51, 51, 51, 51), results);
        assertEquals(2, runtime.snapshotForTrustedHost().started());
        assertEquals(2, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
    }

    @Test
    void activeScopeStillClosesAfterHostDisablesNewRecordings() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        runtime.setEnabledFromTrustedHost(true);
        NativeTelemetryScopes.SpanToken scope =
                runtime.beginFromTrustedRuntime(carrier, "active");
        runtime.setEnabledFromTrustedHost(false);
        runtime.endFromTrustedRuntime(carrier, scope);
        assertEquals(1, runtime.snapshotForTrustedHost().started());
        assertEquals(1, runtime.snapshotForTrustedHost().ended());
    }

    @Test
    void utf8OperationAndBoundedDepthRejectMalformedOrOverflowingInputs() {
        NativeTelemetryScopes runtime = new NativeTelemetryScopes();
        NativeTelemetryScopes.Carrier carrier = runtime.createCarrierForTrustedRuntime();
        for (String invalid : new String[] {"", "x".repeat(257),
                "\uD83D\uDE80".repeat(65), "\uD83D", "\uDE80"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.beginFromTrustedRuntime(carrier, invalid));
        }
        runtime.setEnabledFromTrustedHost(true);
        List<NativeTelemetryScopes.SpanToken> scopes = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            scopes.add(runtime.beginFromTrustedRuntime(carrier, "x".repeat(256)));
        }
        assertThrows(IllegalStateException.class,
                () -> runtime.beginFromTrustedRuntime(carrier, "overflow"));
        for (int i = scopes.size() - 1; i >= 0; i--) {
            runtime.endFromTrustedRuntime(carrier, scopes.get(i));
        }
        assertEquals(64, runtime.snapshotForTrustedHost().started());
        assertEquals(64, runtime.snapshotForTrustedHost().ended());
        assertEquals(0, runtime.snapshotForTrustedHost().active());
    }
}
