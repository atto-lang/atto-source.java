package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class TenantIsolateRetirementRaceTest {
    // Intentional host-side introspection: hold the tenant's evaluation monitor
    // to test that the RETIRING seal is visible BEFORE a close may enter it.
    private static Object tenantMonitor(TenantIsolateManager manager, String id) throws Exception {
        Field field = TenantIsolateManager.class.getDeclaredField("tenants");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> tenants = (Map<String, ?>) field.get(manager);
        Object monitor = tenants.get(id);
        assertNotNull(monitor);
        return monitor;
    }

    private static void awaitState(TenantIsolateManager manager,
            TenantIsolateManager.TenantHandle handle,
            TenantIsolateManager.TenantState expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (manager.tenant_state(handle) != expected) {
            if (System.nanoTime() >= deadline) fail("tenant never reached " + expected);
            Thread.sleep(1);
        }
    }

    @Test
    void closeSealsNewGuestEvaluationBeforeWaitingForTenantMonitor() throws Exception {
        try (TenantIsolateManager manager = new TenantIsolateManager(2, 256)) {
            var handle = manager.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
            var other = manager.open_trusted_tenant("tenant-b", IsolatePolicy.developer());
            Source source = Source.newBuilder(OresLanguage.ID,
                    "pub routine main(): void { return; }", "retirement.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            AtomicReference<Throwable> closeError = new AtomicReference<>();
            final Thread closer;
            // Reentrant synchronization lets this test try admission while the
            // closer is blocked, without relying on busy guest execution.
            synchronized (tenantMonitor(manager, "tenant-a")) {
                closer = Thread.ofPlatform().start(() -> {
                    try { manager.close_tenant(handle); }
                    catch (Throwable error) { closeError.set(error); }
                });
                awaitState(manager, handle, TenantIsolateManager.TenantState.RETIRING);
                IllegalStateException refused = assertThrows(IllegalStateException.class,
                        () -> manager.evaluate_source(handle, source));
                assertTrue(refused.getMessage().contains("TENANT_NOT_ACTIVE"));
            }
            closer.join(5000);
            assertFalse(closer.isAlive(), "tenant close must complete after monitor release");
            assertNull(closeError.get(), "tenant close must not fail");
            assertEquals(1, manager.tenant_count());
            assertEquals(TenantIsolateManager.TenantState.ACTIVE, manager.tenant_state(other));
            assertThrows(SecurityException.class, () -> manager.evaluate_source(handle, source));
            assertEquals("", manager.evaluate_source(other, source));
        }
    }

    @Test
    void hostWideShutdownSealsEveryTenantBeforeAwaitingOneSlowRetirement() throws Exception {
        TenantIsolateManager manager = new TenantIsolateManager(2, 256);
        var first = manager.open_trusted_tenant("tenant-a", IsolatePolicy.developer());
        var second = manager.open_trusted_tenant("tenant-b", IsolatePolicy.developer());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer;
        try {
            synchronized (tenantMonitor(manager, "tenant-a")) {
                closer = Thread.ofPlatform().start(() -> {
                    try { manager.close(); }
                    catch (Throwable error) { failure.set(error); }
                });
                awaitState(manager, first, TenantIsolateManager.TenantState.RETIRING);
                assertEquals(TenantIsolateManager.TenantState.RETIRING,
                        manager.tenant_state(second));
                assertEquals(2, manager.tenant_count());
            }
            closer.join(5000);
            assertFalse(closer.isAlive());
            assertNull(failure.get());
            assertEquals(0, manager.tenant_count());
        } finally {
            manager.close();
        }
    }
}
