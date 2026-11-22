package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TenantIsolateManagerTest {
    private static Source source(String text, String name) throws Exception {
        return Source.newBuilder(OresLanguage.ID, text, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
    }

    private static final String TWO_ACTORS = """
            define actor Counter as
              let int count;
              on_start(): void {
                self.count = 0;
                return;
              }
              receive(ActorMail<String> mail): void {
                self.count = self.count + 1;
                self.send(self.count);
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val left = spawn Counter();
              val right = spawn Counter();
              await left.get_ready();
              await right.get_ready();
              left.send("go");
              right.send("go");
              val a = (await left.outputs.next()).value.unwrap();
              val b = (await right.outputs.next()).value.unwrap();
              stdio.stdout.write(a.value);
              stdio.stdout.write("|");
              stdio.stdout.write(b.value);
              await left.get_done();
              await right.get_done();
              return;
            }
            """;

    @Test
    void actualSourceActorsRunInIndependentTenantContexts() throws Exception {
        try (TenantIsolateManager manager = new TenantIsolateManager(3, 4096)) {
            TenantIsolateManager.TenantHandle a = manager.open_trusted_tenant_vm("tenant-a", IsolatePolicy.developer());
            var b = manager.open_trusted_tenant("tenant-b", IsolatePolicy.developer());
            assertEquals(2, manager.tenant_count());
            Source guest = source(TWO_ACTORS, "tenant-counters.ores");

            // Each guest executes real actor construction, mailbox dispatch,
            // on_start and done; not a host-side simulated ActorRuntime.
            assertEquals("1|1", manager.evaluate_source(a, guest));
            assertEquals("1|1", manager.evaluate_source(b, guest));
            assertEquals("tenant-a", a.tenant_id());
            assertEquals("tenant-b", b.tenant_id());
        }
    }

    @Test
    void closingOneTenantDoesNotRevokeAnotherAndOldHandleCannotAliasNewTenant() throws Exception {
        try (TenantIsolateManager manager = new TenantIsolateManager(2, 4096)) {
            var a = manager.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
            var b = manager.open_trusted_tenant("tenant-b", IsolatePolicy.developer());
            manager.close_tenant(a);
            assertEquals(1, manager.tenant_count());
            var simple = source("""
                    pub routine main(): void {
                      stdio.stdout.write("tenant-b-alive");
                      return;
                    }
                    """, "tenant-b-alive.ores");
            assertEquals("tenant-b-alive", manager.evaluate_source(b, simple));
            assertThrows(SecurityException.class, () -> manager.evaluate_source(a, simple));

            var anotherA = manager.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
            assertNotSame(a, anotherA);
            assertThrows(SecurityException.class, () -> manager.evaluate_source(a, simple));
        }
    }

    @Test
    void aHandleFromAnotherManagerDoesNotGrantAccess() throws Exception {
        try (TenantIsolateManager first = new TenantIsolateManager(1, 256);
             TenantIsolateManager second = new TenantIsolateManager(1, 256)) {
            var handle = first.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
            var source = source("pub routine main(): void { return; }", "simple.ores");
            assertThrows(SecurityException.class, () -> second.evaluate_source(handle, source));
            assertThrows(SecurityException.class, () -> second.close_tenant(handle));
        }
    }

    @Test
    void admissionIsBoundedAndRejectsDuplicateOrMalformedTenantIds() {
        try (TenantIsolateManager manager = new TenantIsolateManager(1, 128)) {
            manager.open_trusted_tenant("first", IsolatePolicy.developer());
            assertThrows(IllegalStateException.class,
                    () -> manager.open_trusted_tenant("first", IsolatePolicy.developer()));
            assertThrows(IllegalStateException.class,
                    () -> manager.open_trusted_tenant("second", IsolatePolicy.developer()));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.open_trusted_tenant("first" + (char) 0 + "other", IsolatePolicy.developer()));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.open_trusted_tenant(String.valueOf((char) 0xd800), IsolatePolicy.developer()));
            for (String blocked : new String[] {".", "..", "../other", "tenant/other",
                    "tenant:other", "tenant other", "ténant"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> manager.open_trusted_tenant(blocked, IsolatePolicy.developer()),
                        "tenant isolation v1 must reject " + blocked);
            }
        }
    }

    @Test
    void confidentialComputeCannotBeDowngradedToTrustedOrUntrustedContext() {
        try (TenantIsolateManager manager = new TenantIsolateManager(2, 256)) {
            for (IsolatePolicy policy : new IsolatePolicy[] {
                    IsolatePolicy.developer(), IsolatePolicy.untrustedActor() }) {
                SecurityException denied = assertThrows(SecurityException.class,
                        () -> manager.open_confidential_tenant_vm("confidential", policy));
                assertTrue(denied.getMessage().contains("CONFIDENTIAL_COMPUTE_UNAVAILABLE"));
                assertEquals(0, manager.tenant_count());
            }
            // Existing explicitly trusted admission remains unchanged.
            assertNotNull(manager.open_trusted_tenant_vm("trusted", IsolatePolicy.developer()));
            assertEquals(1, manager.tenant_count());
        }
    }

    @Test
    void protectedOrUntrustedTenantsCannotSilentlyFallBackToContexts() {
        try (TenantIsolateManager manager = new TenantIsolateManager(2, 256)) {
            SecurityException error = assertThrows(SecurityException.class,
                    () -> manager.open_trusted_tenant("untrusted",
                            IsolatePolicy.untrustedActor()));
            assertTrue(error.getMessage().contains("UNVERIFIED_TENANT_ISOLATION"));
            assertThrows(IllegalStateException.class,
                    () -> ActorHeapAdmission.requireSupportedTenantIsolationMode("vm-isolate"));
            assertEquals(0, manager.tenant_count());
        }
    }

    @Test
    void onlyOreslangSourceIsAdmittedWithoutExposingGuestValues() throws Exception {
        try (TenantIsolateManager manager = new TenantIsolateManager(1, 256)) {
            var a = manager.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
            Source foreign = Source.newBuilder("untrusted-js", "1 + 1", "foreign.js").build();
            assertThrows(SecurityException.class, () -> manager.evaluate_source(a, foreign));
        }
    }

    @Test
    void closeSealsNewTenantAdmissionAndRevokesOldHandles() throws Exception {
        TenantIsolateManager manager = new TenantIsolateManager(1, 256);
        var a = manager.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
        manager.close();
        assertEquals(0, manager.tenant_count());
        assertThrows(IllegalStateException.class,
                () -> manager.open_trusted_tenant("tenant-b", IsolatePolicy.developer()));
        var source = source("pub routine main(): void { return; }", "closed.ores");
        assertThrows(SecurityException.class, () -> manager.evaluate_source(a, source));
        manager.close(); // idempotent after full retirement
    }
    @Test
    void stderrLimitResetsBetweenGuestEvaluationsWithoutEscapingTenant() throws Exception {
        try (TenantIsolateManager manager = new TenantIsolateManager(1, 16)) {
            var tenantHandle = manager.open_trusted_tenant("stderr-tenant", IsolatePolicy.developer());
            // Inspect the host-owned bounded sink to inject exactly one quota's
            // worth of previous stderr. No guest gets the sink or Context.
            var registryField = TenantIsolateManager.class.getDeclaredField("tenants");
            registryField.setAccessible(true);
            var registry = (java.util.Map<?, ?>) registryField.get(manager);
            Object tenant = registry.get("stderr-tenant");
            assertNotNull(tenant);
            var errorField = tenant.getClass().getDeclaredField("errorOutput");
            errorField.setAccessible(true);
            var errorSink = (java.io.OutputStream) errorField.get(tenant);
            Source guest = source("pub routine main(): void { return; }", "stderr-reset.ores");
            for (int run = 0; run < 5; run++) {
                // The following write would fail on run 2 if stderr from run 1
                // were retained instead of discarded at guest admission.
                assertDoesNotThrow(() -> errorSink.write(new byte[16]));
                assertEquals("", manager.evaluate_source(tenantHandle, guest));
            }
            assertEquals(1, manager.tenant_count());
        }
    }

}
