package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.PolyglotException;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual source-backed generic loader admission and publication tests.
 * Does not establish actor mailbox migration, protected isolates, or native AOT.
 */
final class HotReloadCurrentMainTransactionTest {
    private static final String GOOD = "pub routine main(): void { return; }";
    // This must PASS the trusted compiler's syntax/type/ownership/capability
    // checks but fail INSIDE guest execution. A dynamic selector prevents
    // static constant-index rejection and mirrors the separately authored
    // DynamicPropertyAccessRuntimeFailureTest runtime-boundary oracle.
    private static final String BAD_AT_START = """
            fnc selector(): int { return 4; }
            pub routine main(): void {
              const pair = tuple(1, 2);
              const int index = selector();
              const int value = pair[index];
              return;
            }
            """;
    private static final String RUNTIME_BOUNDS_DIAGNOSTIC =
            "array/list/tuple index 4 outside length 2";
    private static final ExecutionProfile PROFILE = ExecutionProfile.serverJit();

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IncrementalCompiler.CompiledUnit compiledGood() {
        return new IncrementalCompiler().compile(Map.of("app.ores", GOOD))
                .units().get("app.ores");
    }

    private static IncrementalCompiler.CompiledUnit counterfeit(
            IncrementalCompiler.CompiledUnit original, String source,
            String digest, Set<String> dependencies) {
        return new IncrementalCompiler.CompiledUnit(
                original.unitId(), original.packageId(), original.namespace(),
                digest, original.abiDigest(), dependencies, source, original.program());
    }

    @Test
    void failedStartupPreservesExactPreviouslyServingGeneration() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var previous = manager.loadAndStart("worker.ores", GOOD);
            assertDoesNotThrow(() -> OresCompiler.validateForIsolate(
                    BAD_AT_START, IsolatePolicy.developer()),
                    "fixture must pass compiler and policy admission before guest evaluation");
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> manager.reloadAndStart("worker.ores", BAD_AT_START));
            assertTrue(failure.getMessage().contains(RUNTIME_BOUNDS_DIAGNOSTIC),
                    () -> "expected dynamic guest index failure, got: " + failure);
            assertSame(previous, manager.active("worker.ores"));
            assertSame(previous, manager.serving("worker.ores"));
            assertTrue(previous.initialized());
            assertSame(previous, manager.active());
            assertFalse(previous.closed());
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void successfulStartupPublishesOnlyStartedCandidate() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var previous = manager.loadAndStart("worker.ores", GOOD);
            var current = manager.reloadAndStart("worker.ores",
                    "pub routine main(): void { val version = 2; return; }");
            assertTrue(current.started());
            assertTrue(current.initialized());
            assertSame(current, manager.active("worker.ores"));
            assertSame(current, manager.serving("worker.ores"));
            assertFalse(previous.closed());
            assertEquals(2, manager.liveGenerations());
            manager.retire(previous.id());
            assertSame(current, manager.active("worker.ores"));
            assertTrue(previous.closed());
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void failedOtherUnitNeverRedirectsGlobalLastServingRoute() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var previous = manager.loadAndStart("stable.ores", GOOD);
            assertDoesNotThrow(() -> OresCompiler.validateForIsolate(
                    BAD_AT_START, IsolatePolicy.developer()));
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> manager.loadAndStart("broken.ores", BAD_AT_START));
            assertTrue(failure.getMessage().contains(RUNTIME_BOUNDS_DIAGNOSTIC),
                    () -> "expected guest execution failure, got: " + failure);
            assertSame(previous, manager.active());
            assertNull(manager.active("broken.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void admittedCandidateOnlyFailsDuringItsRealGuestEntrypoint() {
        assertDoesNotThrow(() -> OresCompiler.validateForIsolate(
                BAD_AT_START, IsolatePolicy.developer()),
                "the deliberately failing guest must not be a type-check failure");
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = assertDoesNotThrow(() ->
                    manager.load("runtime-failure.ores", BAD_AT_START));
            assertFalse(candidate.started());
            assertFalse(candidate.initialized());
            assertEquals(1, manager.liveGenerations(),
                    "source admission actually registered a generation");
            PolyglotException failure = assertThrows(PolyglotException.class,
                    candidate::start);
            assertTrue(failure.getMessage().contains(RUNTIME_BOUNDS_DIAGNOSTIC),
                    () -> "wrong guest-runtime failure: " + failure);
            assertTrue(candidate.started(), "failed evaluation was attempted");
            assertFalse(candidate.initialized(), "runtime exception must not publish readiness");
            assertTrue(candidate.closed(), "failed guest start must retire its context");
            assertEquals(0, manager.liveGenerations(),
                    "failing guest must not retain manager generation quota");
            assertNull(manager.active("runtime-failure.ores"));
            assertNull(manager.serving("runtime-failure.ores"));
        }
    }

    @Test
    void legacyLoadRemainsExplicitlyUnreadyForServing() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var staged = manager.load("legacy.ores", GOOD);
            assertFalse(staged.started());
            assertFalse(staged.initialized());
            assertNull(manager.serving("legacy.ores"),
                    "a staged legacy candidate is never automatically service-ready");
            // The existing load() publication API is compatibility-only; use
            // reloadAndStart() as the serving-route upgrade entry point.
            assertSame(staged, manager.active("legacy.ores"));
        }
    }

    @Test
    void forgedSourceDigestCannotStageOrEvictHealthyRoute() {
        var compiled = compiledGood();
        var forged = counterfeit(compiled, "unrelated", compiled.sourceDigest(), Set.of());
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var serving = manager.loadAndStart("healthy.ores", GOOD);
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertSame(serving, manager.active());
            assertNull(manager.active("app.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void matchingDigestDoesNotAuthorizeUnverifiedAst() {
        var compiled = compiledGood();
        String invalid = "pub routine main(): void {";
        var forged = counterfeit(compiled, invalid, sha256(invalid), Set.of());
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(RuntimeException.class, () -> manager.load(forged));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void forgedEmptyDependencySetCannotHideRealExecutableImport() {
        var compiled = compiledGood();
        String imported = "import module dependency from \"dependency.ores\";\n" + GOOD;
        assertFalse(dev.oreslang.parser.Parser.parse(imported).imports().isEmpty());
        var forged = counterfeit(compiled, imported, sha256(imported), Set.of());
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var serving = manager.loadAndStart("healthy.ores", GOOD);
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertSame(serving, manager.active("healthy.ores"));
            assertNull(manager.active("app.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void managerShutdownFencesAnyUnstartedGenerationEntrypoint() {
        var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE);
        var candidate = manager.load("not-started.ores", GOOD);
        manager.close();
        assertTrue(candidate.closed());
        assertThrows(IllegalStateException.class, candidate::start);
        assertEquals(0, manager.liveGenerations());
    }

    @Test
    void retirementFencesAnyUnstartedGenerationEntrypoint() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("retire-first.ores", GOOD);
            manager.retire(candidate.id());
            assertTrue(candidate.closed());
            assertNull(manager.active("retire-first.ores"));
            assertThrows(IllegalStateException.class, candidate::start);
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void directGenerationCloseFencesEntrypointAndEvictsServingPointer() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("direct-close.ores", GOOD);
            candidate.close();
            assertTrue(candidate.closed());
            assertNull(manager.active("direct-close.ores"));
            assertNull(manager.serving("direct-close.ores"));
            assertThrows(IllegalStateException.class, candidate::start);
            assertEquals(0, manager.liveGenerations());
        }
    }

    // White-box fault injection for the synchronous entrypoint lease. These
    // two checks are host preflight gates, NOT a real Graal or Task quiescence
    // test. The latter still requires an independently executed race suite.
    private static void simulateEntrypointInFlight(
            HotReloadManager.Generation generation, boolean inFlight)
            throws ReflectiveOperationException {
        var field = generation.getClass().getDeclaredField("executing");
        field.setAccessible(true);
        synchronized (generation) {
            field.setBoolean(generation, inFlight);
        }
    }

    @Test
    void shutdownRejectsExecutingGenerationWithoutLosingRegistry() throws Exception {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("busy-shutdown.ores", GOOD);
            simulateEntrypointInFlight(candidate, true);
            try {
                assertThrows(IllegalStateException.class, manager::close);
                assertSame(candidate, manager.active("busy-shutdown.ores"));
                assertFalse(candidate.closed());
                assertEquals(1, manager.liveGenerations());
            } finally {
                simulateEntrypointInFlight(candidate, false);
            }
        }
    }

    @Test
    void retirementRejectsExecutingGenerationWithoutLosingRoute() throws Exception {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("busy-retirement.ores", GOOD);
            simulateEntrypointInFlight(candidate, true);
            try {
                assertThrows(IllegalStateException.class,
                        () -> manager.retire(candidate.id()));
                assertSame(candidate, manager.active("busy-retirement.ores"));
                assertFalse(candidate.closed());
                assertEquals(1, manager.liveGenerations());
            } finally {
                simulateEntrypointInFlight(candidate, false);
            }
        }
    }

    @Test
    void scopedOperationPinFencesConcurrentRetirement() throws Exception {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var current = manager.loadAndStart("busy-guest.ores", GOOD);
            var pin = current.pinOperation();
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result = pool.submit(() -> assertThrows(
                        IllegalStateException.class, () -> manager.retire(current.id())));
                result.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertSame(current, manager.serving("busy-guest.ores"));
                assertFalse(current.closed());
                assertEquals(1, manager.liveGenerations());
            } finally {
                pool.shutdownNow();
                pin.close();
                pin.close(); // idempotent, never decrements another lease
            }
            manager.retire(current.id());
            assertTrue(current.closed());
            assertNull(manager.serving("busy-guest.ores"));
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void managerShutdownPreflightsAllGenerationsBeforeDetachingAny() {
        var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE);
        try {
            var first = manager.loadAndStart("first.ores", GOOD);
            var second = manager.loadAndStart("second.ores", GOOD);
            var pin = first.pinOperation();
            try {
                assertThrows(IllegalStateException.class, manager::close);
                assertSame(first, manager.serving("first.ores"));
                assertSame(second, manager.serving("second.ores"));
                assertEquals(2, manager.liveGenerations());
                assertFalse(first.closed());
                assertFalse(second.closed());
            } finally {
                pin.close();
            }
            manager.close();
            assertTrue(first.closed());
            assertTrue(second.closed());
            assertEquals(0, manager.liveGenerations());
        } finally {
            manager.close();
        }
    }

    @Test
    void uninitializedAndRetiredGenerationsCannotClaimPhysicalOperationPins() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var stage = manager.load("staged.ores", GOOD);
            assertThrows(IllegalStateException.class, stage::pinOperation);
            stage.start();
            assertTrue(stage.initialized());
            try (var pin = stage.pinOperation()) {
                assertThrows(IllegalStateException.class,
                        () -> manager.retire(stage.id()));
            }
            manager.retire(stage.id());
            assertThrows(IllegalStateException.class, stage::pinOperation);
        }
    }

    @Test
    void directGenerationCloseCannotBypassScopedOperationLease() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var current = manager.loadAndStart("operation.ores", GOOD);
            var pin = current.pinOperation();
            try {
                assertThrows(IllegalStateException.class, current::close);
                assertFalse(current.closed());
                assertSame(current, manager.serving("operation.ores"));
            } finally {
                pin.close();
            }
            current.close();
            assertTrue(current.closed());
            assertNull(manager.serving("operation.ores"));
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void legacyStagingCannotShadowPreviouslyCommittedServingRoute() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var healthy = manager.loadAndStart("worker.ores", GOOD);
            var legacyCandidate = manager.load("worker.ores", GOOD);
            assertSame(legacyCandidate, manager.active("worker.ores"),
                    "legacy active pointer remains for backward compatibility");
            assertSame(healthy, manager.serving("worker.ores"),
                    "production serving route cannot be shadowed by unready staging");
            assertFalse(legacyCandidate.initialized());
            assertEquals(2, manager.liveGenerations());
            legacyCandidate.close();
            assertSame(healthy, manager.serving("worker.ores"));
            assertFalse(healthy.closed());
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void explicitStartPublishesAndFencesOlderReloadCandidates() {
        // Main's direct-start contract (#2845): a successful explicit start()
        // of a staged generation publishes it. Publication bumps the routing
        // epoch, so an older in-flight reloadAndStart() candidate is fenced.
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("worker.ores", GOOD);
            candidate.start();
            assertTrue(candidate.initialized());
            assertSame(candidate, manager.active("worker.ores"));
            assertSame(candidate, manager.serving("worker.ores"),
                    "successful explicit start publishes the serving route");
            assertSame(candidate, manager.serving());
            candidate.close();
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void servingRouteSelectionAndPhysicalPinAreAtomic() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(IllegalStateException.class, () -> manager.pinServing("worker.ores"));
            var healthy = manager.loadAndStart("worker.ores", GOOD);
            var legacyStaged = manager.load("worker.ores", GOOD);
            try (var serving = manager.pinServing("worker.ores")) {
                assertSame(healthy, serving.generation());
                assertNotSame(legacyStaged, serving.generation());
                assertThrows(IllegalStateException.class,
                        () -> manager.retire(healthy.id()));
                assertThrows(IllegalStateException.class, manager::close);
                assertSame(healthy, manager.serving("worker.ores"));
            }
            manager.retire(healthy.id());
            assertNull(manager.serving("worker.ores"));
            assertThrows(IllegalStateException.class,
                    () -> manager.pinServing("worker.ores"));
            // The legacy staged generation remains independently registered.
            assertEquals(1, manager.liveGenerations());
            legacyStaged.close();
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void concurrentServingLeasePinsV1UntilV2PublishesAndOldWorkCompletes() throws Exception {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var v1 = manager.loadAndStart("worker.ores", GOOD);
            var oldAdmitted = new java.util.concurrent.CountDownLatch(1);
            var releaseOld = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                // The simulated physical dispatch owns the V1 code image,
                // even while CONTROL stages and publishes a V2 serving route.
                var oldWork = pool.submit(() -> {
                    try (var pinned = manager.pinServing("worker.ores")) {
                        assertSame(v1, pinned.generation());
                        oldAdmitted.countDown();
                        assertTrue(releaseOld.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                try {
                    assertTrue(oldAdmitted.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    var upgrade = pool.submit(() -> manager.reloadAndStart(
                            "worker.ores",
                            "pub routine main(): void { val upgraded = 2; return; }"));
                    var v2 = upgrade.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(v2.initialized());
                    assertSame(v2, manager.serving("worker.ores"));
                    assertFalse(v1.closed(), "V1 remains live while old work is pinned");
                    assertThrows(IllegalStateException.class, () -> manager.retire(v1.id()));
                    try (var newWork = manager.pinServing("worker.ores")) {
                        assertSame(v2, newWork.generation(),
                                "new admissions select V2 after publication");
                    }
                    assertEquals(2, manager.liveGenerations());
                } finally {
                    releaseOld.countDown();
                }
                oldWork.get(10, java.util.concurrent.TimeUnit.SECONDS);
                manager.retire(v1.id());
                assertTrue(v1.closed());
                assertEquals(1, manager.liveGenerations());
                assertSame(manager.active("worker.ores"), manager.serving("worker.ores"));
            } finally {
                releaseOld.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void unverifiedDeclaredDependenciesAreRejectedEvenWithValidSource() {
        var compiled = compiledGood();
        var forged = counterfeit(compiled, GOOD, sha256(GOOD), Set.of("other.ores"));
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertEquals(0, manager.liveGenerations());
        }
    }
}
