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
    void trustedContextsShareOneEngineSourceAndParsedCallTarget() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager first = new HotReloadManager(policy, ExecutionProfile.serverJit());
             HotReloadManager second = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var a = first.load("actor-image.ores", MINIMAL_PROGRAM);
            var b = second.load("actor-image.ores", MINIMAL_PROGRAM);
            assertSame(a.source(), b.source(),
                    "same program image must not be copied per private context");
            assertNotSame(a.context(), b.context(), "guest contexts must remain isolated");
            assertSame(a.context().getEngine(), b.context().getEngine(),
                    "private contexts must use one process code engine");
            assertSame(ProcessCodeEngine.shared(), a.context().getEngine());

            long before = SharedCodeRegistry.process().parseInvocations("actor-image.ores", MINIMAL_PROGRAM);
            assertDoesNotThrow(a::start);
            long afterFirst = SharedCodeRegistry.process().parseInvocations("actor-image.ores", MINIMAL_PROGRAM);
            assertEquals(before + 1, afterFirst,
                    "first evaluation should populate the shared parse/call-target cache");

            assertDoesNotThrow(b::start);
            assertEquals(afterFirst,
                    SharedCodeRegistry.process().parseInvocations("actor-image.ores", MINIMAL_PROGRAM),
                    "second context must reuse the cached parsed call target");

            first.retire(a.id());
            assertTrue(a.closed());
            assertFalse(b.closed());
            assertNotNull(b.source());
        }
    }

    @Test
    void adversarialSharingRequiresExactSupervisorApprovalPerCodeUnit() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        IsolatePolicy supervisor = IsolatePolicy.developer();
        Object authority = new Object();
        String root = "pub routine main(): void { return; }";
        String changed = "pub routine main(): void { val n = 2; return; }";

        assertFalse(registry.approvedForAdversarialSharing(authority, "root.ores", root));
        registry.approveForAdversarialSharing(supervisor, authority, "root.ores", root);
        assertTrue(registry.approvedForAdversarialSharing(authority, "root.ores", root));

        assertFalse(registry.approvedForAdversarialSharing(authority, "root.ores", changed),
                "approval is bound to the exact content hash");
        assertFalse(registry.approvedForAdversarialSharing(authority, "dependency.ores", root),
                "imports/dependencies require independent approval");
        assertFalse(registry.approvedForAdversarialSharing(authority, "other-name.ores", root),
                "approval is also bound to the code-unit identity");

        registry.revokeForAdversarialSharing(supervisor, authority, "root.ores", root);
        assertFalse(registry.approvedForAdversarialSharing(authority, "root.ores", root));

        assertThrows(SecurityException.class, () ->
                registry.approveForAdversarialSharing(
                        IsolatePolicy.untrustedActor(), authority, "root.ores", root));
    }

    @Test
    void adversarialAllowlistCannotApproveCodeItsGuestPolicyRejects() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        String privileged = "pub routine main(): void { print(1); return; }";
        Object authority = new Object();
        assertThrows(SecurityException.class, () ->
                registry.approveForAdversarialSharing(
                        IsolatePolicy.developer(), authority, "privileged.ores", privileged));
        assertFalse(registry.approvedForAdversarialSharing(
                authority, "privileged.ores", privileged));
    }

    @Test
    void untrustedManagersShareOnlyExactSupervisorApprovedImages() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("polyglot.engine.IsolateLibrary") != null,
                "execution-level untrusted sharing proof requires the Oreslang native isolate library");
        IsolatePolicy supervisor = IsolatePolicy.developer();
        String codeUnitId = "untrusted-shared-proof.ores";
        String changed =
                "pub routine main(): void { val changed = 1; return; }";

        try (HotReloadManager first =
                     HotReloadManager.forUntrustedActors(
                             supervisor, ExecutionProfile.serverJit());
             HotReloadManager second =
                     HotReloadManager.forUntrustedActors(
                             supervisor, ExecutionProfile.serverJit())) {

            var privateA = first.load(codeUnitId, MINIMAL_PROGRAM);
            var privateB = second.load(codeUnitId, MINIMAL_PROGRAM);
            assertFalse(privateA.sharedCodeImage());
            assertFalse(privateB.sharedCodeImage());
            assertNotSame(privateA.source(), privateB.source(),
                    "unapproved untrusted source must remain a private code image");

            first.retire(privateA.id());
            second.retire(privateB.id());

            first.approveGuestCodeSharing(codeUnitId, MINIMAL_PROGRAM);
            try {
                var sharedA = first.load(codeUnitId, MINIMAL_PROGRAM);
                var deniedB = second.load(codeUnitId, MINIMAL_PROGRAM);
                assertFalse(deniedB.sharedCodeImage(),
                        "one manager's approval must not authorize another manager");
                second.retire(deniedB.id());
                second.approveGuestCodeSharing(codeUnitId, MINIMAL_PROGRAM);
                var sharedB = second.load(codeUnitId, MINIMAL_PROGRAM);
                assertTrue(sharedA.sharedCodeImage());
                assertTrue(sharedB.sharedCodeImage());
                assertSame(sharedA.source(), sharedB.source(),
                        "exact-approved untrusted loads may reuse one immutable Source");

                var changedVersion = first.load(codeUnitId, changed);
                assertFalse(changedVersion.sharedCodeImage(),
                        "approval must not survive a content hash change");
                assertNotSame(sharedA.source(), changedVersion.source());

                var renamed = second.load(
                        "renamed-untrusted-shared-proof.ores", MINIMAL_PROGRAM);
                assertFalse(renamed.sharedCodeImage(),
                        "approval must not transfer to another code-unit identity");
                assertNotSame(sharedA.source(), renamed.source());
            } finally {
                first.revokeGuestCodeSharing(codeUnitId, MINIMAL_PROGRAM);
                second.revokeGuestCodeSharing(codeUnitId, MINIMAL_PROGRAM);
            }
        }

        // Closing each manager revokes its scoped grant; no global allowlist remains.
    }

    @Test
    void identicalSourceGrantsCannotCrossTenantAuthorities() {
        SharedCodeRegistry registry = new SharedCodeRegistry();
        Object tenantA = new Object();
        Object tenantB = new Object();
        String source = MINIMAL_PROGRAM;
        IsolatePolicy supervisor = IsolatePolicy.developer();

        registry.approveForAdversarialSharing(
                supervisor, tenantA, "tenant-test.ores", source);
        assertTrue(registry.approvedForAdversarialSharing(
                tenantA, "tenant-test.ores", source));
        assertFalse(registry.approvedForAdversarialSharing(
                tenantB, "tenant-test.ores", source));

        registry.approveForAdversarialSharing(
                supervisor, tenantB, "tenant-test.ores", source);
        registry.revokeForAdversarialSharing(
                supervisor, tenantA, "tenant-test.ores", source);
        assertFalse(registry.approvedForAdversarialSharing(
                tenantA, "tenant-test.ores", source));
        assertTrue(registry.approvedForAdversarialSharing(
                tenantB, "tenant-test.ores", source),
                "another tenant's independent grant must survive");
        registry.revokeAllForAuthority(tenantB);
        assertFalse(registry.approvedForAdversarialSharing(
                tenantB, "tenant-test.ores", source));
    }

    @Test
    void managerCloseRevokesOnlyItsOwnGrantAndIsTerminal() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        String codeUnit = "shutdown-tenant-boundary.ores";
        try (HotReloadManager a = HotReloadManager.forUntrustedActors(
                     supervisor, ExecutionProfile.serverJit());
             HotReloadManager b = HotReloadManager.forUntrustedActors(
                     supervisor, ExecutionProfile.serverJit())) {
            a.approveGuestCodeSharing(codeUnit, MINIMAL_PROGRAM);
            b.approveGuestCodeSharing(codeUnit, MINIMAL_PROGRAM);
            a.close();
            assertThrows(IllegalStateException.class,
                    () -> a.approveGuestCodeSharing(codeUnit, MINIMAL_PROGRAM));
            assertThrows(IllegalStateException.class,
                    () -> a.revokeGuestCodeSharing(codeUnit, MINIMAL_PROGRAM));
            // Closing one authority must not remove another manager's grant.
            assertDoesNotThrow(() -> b.revokeGuestCodeSharing(codeUnit, MINIMAL_PROGRAM));
            assertDoesNotThrow(() -> b.approveGuestCodeSharing(codeUnit, MINIMAL_PROGRAM));
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
