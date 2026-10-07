package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SharedCodeRegistryTest {
    private static final String MINIMAL_PROGRAM =
            "pub routine main(): void { return; }";

    @Test
    void reusesOneSourceInstanceUntilLastLeaseCloses() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        SharedCodeRegistry.Lease first = registry.acquire("image.ores", MINIMAL_PROGRAM);
        SharedCodeRegistry.Lease second = registry.acquire("image.ores", MINIMAL_PROGRAM);

        try {
            assertSame(first.source(), second.source(),
                    "identical source images must not be recreated for each actor context");
            assertEquals(first.sha256(), second.sha256());
            assertEquals(1, registry.liveImages());

            first.close();
            first.close();
            assertThrows(IllegalStateException.class, first::source);
            assertEquals(1, registry.liveImages(), "second lease still pins the image");
            assertNotNull(second.source());
        } finally {
            first.close();
            second.close();
        }
        assertEquals(0, registry.liveImages(), "last lease must release the cache root");
    }

    @Test
    void checkedCodeIsOneObjectAcrossActorsButPolicyChecksRemainPerActor() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        try (var shared = registry.acquire("worker.ores", MINIMAL_PROGRAM);
             var isolated = registry.acquire("worker.ores", MINIMAL_PROGRAM);
             var untrusted = registry.acquire("worker.ores", MINIMAL_PROGRAM)) {

            Ast.Program code = shared.checkedProgram();
            assertSame(code, isolated.checkedProgram(),
                    "private actor should see identical immutable code definitions");
            assertSame(code, untrusted.checkedProgram(),
                    "untrusted actor should see identical immutable code definitions");
            assertDoesNotThrow(() ->
                    CapabilityChecker.check(code, IsolatePolicy.untrustedActor()));
            assertEquals(1, registry.liveImages());
        }
    }

    @Test
    void immutableCodeSharingDoesNotShareCapabilities() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        String privilegedCode = "pub routine main(): void { print(1); return; }";
        try (var shared = registry.acquire("caps.ores", privilegedCode);
             var untrusted = registry.acquire("caps.ores", privilegedCode)) {
            assertSame(shared.checkedProgram(), untrusted.checkedProgram());
            assertDoesNotThrow(() ->
                    CapabilityChecker.check(shared.checkedProgram(), IsolatePolicy.developer()));
            assertThrows(SecurityException.class, () ->
                    CapabilityChecker.check(untrusted.checkedProgram(), IsolatePolicy.untrustedActor()));
        }
    }

    @Test
    void versionsAndSourceIdsNeverAlias() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        try (var oldImage = registry.acquire("module.ores", MINIMAL_PROGRAM);
             var updated = registry.acquire(
                     "module.ores", "pub routine main(): void { val n = 2; return; }");
             var otherName = registry.acquire("other.ores", MINIMAL_PROGRAM)) {

            assertNotSame(oldImage.source(), updated.source());
            assertNotEquals(oldImage.sha256(), updated.sha256());
            assertNotSame(oldImage.source(), otherName.source());
            assertEquals("module.ores", oldImage.source().getName());
            assertEquals("other.ores", otherName.source().getName());
            assertEquals(3, registry.liveImages());
        }
        assertEquals(0, registry.liveImages());
    }

    @Test
    void independentHotReloadContextsShareOnlyTheImmutableSourceImage() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager first = new HotReloadManager(policy, ExecutionProfile.serverJit());
             HotReloadManager second = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var a = first.load("actor-image.ores", MINIMAL_PROGRAM);
            var b = second.load("actor-image.ores", MINIMAL_PROGRAM);
            assertSame(a.source(), b.source());
            assertNotSame(a.context(), b.context(), "guest contexts must remain isolated");

            first.retire(a.id());
            assertTrue(a.closed());
            assertFalse(b.closed());
            assertNotNull(b.source());
            // Retirement of one generation cannot invalidate another actor's code.
            assertDoesNotThrow(b::start);
        }
    }

    @Test
    void missingCodeIdentityIsRejectedBeforePublication() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        assertThrows(NullPointerException.class, () -> registry.acquire(null, MINIMAL_PROGRAM));
        assertThrows(NullPointerException.class, () -> registry.acquire("x.ores", null));
        assertThrows(IllegalArgumentException.class, () -> registry.acquire("  ", MINIMAL_PROGRAM));
        assertEquals(0, registry.liveImages());
    }
}
