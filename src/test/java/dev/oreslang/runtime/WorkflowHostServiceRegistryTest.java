package dev.oreslang.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.oreslang.runtime.WorkflowHostServiceRegistry.*;

public final class WorkflowHostServiceRegistryTest {
    private static int count;
    private static void ok(boolean yes, String reason) { if (!yes) throw new AssertionError(reason); }
    private static final String REVISION = "a".repeat(40);
    private static Request message(String id) {
        return new Request(id, Operation.INSPECT, "tenant-a", "run-1", REVISION,
                1, 0, 0, "", "", "", "", "", "", "opaque-test-capability");
    }
    private static Reply reply(String id) {
        return new Reply(id, Status.OK, 1, 1, 0, 0, 0);
    }
    private static Reply await(WorkflowHostServiceRegistry r, Request m) throws Exception {
        return r.submit(m).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    private static void defaultDeny() throws Exception {
        try (WorkflowHostServiceRegistry r = new WorkflowHostServiceRegistry()) {
            ok(!r.isProvisioned(), "unprovisioned default");
            ok(await(r, message("req-1")).status() == Status.UNAVAILABLE, "unavailable");
            ok(await(r, new Request("bad", Operation.INSPECT, "bad/tenant", "run-1", REVISION,
                1, 0, 0, "", "", "", "", "", "", "cap")).status() == Status.DENIED,
                "bad identity");
        }
        count++;
    }
    private static void oneShotScope() throws Exception {
        AtomicInteger called = new AtomicInteger();
        Service service = request -> { called.incrementAndGet(); return CompletableFuture.completedFuture(reply(request.requestId())); };
        try (AutoCloseable scope = WorkflowHostServiceRegistry.provisionForHost(service)) {
            try (WorkflowHostServiceRegistry first = WorkflowHostServiceRegistry.forNewContext();
                 WorkflowHostServiceRegistry second = WorkflowHostServiceRegistry.forNewContext()) {
                ok(first.isProvisioned(), "first context acquired service");
                ok(!second.isProvisioned(), "second context did not inherit it");
                ok(await(first, message("req-first")).status() == Status.OK, "authorized first");
                ok(await(second, message("req-second")).status() == Status.UNAVAILABLE, "denied second");
            }
        }
        ok(called.get() == 1, "single dispatch");
        try (WorkflowHostServiceRegistry third = WorkflowHostServiceRegistry.forNewContext()) {
            ok(!third.isProvisioned(), "scope is not sticky");
        }
        count++;
    }
    private static void ownerWorkerAndBackpressure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Reply> held = new CompletableFuture<>();
        Service service = request -> {
            ok(Thread.currentThread().getName().contains("ores-workflow-host"), "host callback thread");
            entered.countDown();
            return held;
        };
        try (AutoCloseable scope = WorkflowHostServiceRegistry.provisionForHost(service)) {
            WorkflowHostServiceRegistry r = WorkflowHostServiceRegistry.forNewContext();
            try {
                var first = r.submit(message("hold"));
                ok(entered.await(3, TimeUnit.SECONDS), "entered host worker");
                for (int i=0;i<63;i++) r.submit(message("other-" + i));
                ok(await(r, message("overflow")).status() == Status.OVERLOADED,
                    "bounded full outstanding queue");
                held.complete(reply("hold"));
                ok(first.toCompletableFuture().get(3,TimeUnit.SECONDS).status() == Status.OK, "complete held");
            } finally {r.close();}
            ok(await(r, message("closed")).status() == Status.CLOSED, "closed context");
        }
        count++;
    }
    private static void denyForgedReplyAndFailedHost() throws Exception {
        Service service = request -> CompletableFuture.completedFuture(reply("other-request"));
        try (AutoCloseable scope = WorkflowHostServiceRegistry.provisionForHost(service);
             WorkflowHostServiceRegistry r = WorkflowHostServiceRegistry.forNewContext()) {
            ok(await(r, message("sent")).status() == Status.FAILED, "reply identity mismatch");
        }
        Service broken = request -> { throw new IllegalArgumentException("sensitive secret"); };
        try (AutoCloseable scope = WorkflowHostServiceRegistry.provisionForHost(broken);
             WorkflowHostServiceRegistry r = WorkflowHostServiceRegistry.forNewContext()) {
            Reply reply = await(r, message("failed"));
            ok(reply.status() == Status.FAILED, "failure does not leak exception");
        }
        count++;
    }
    private static void noNestedProvision() throws Exception {
        Service service = r -> CompletableFuture.completedFuture(reply(r.requestId()));
        try (AutoCloseable ignored = WorkflowHostServiceRegistry.provisionForHost(service)) {
            boolean rejected = false;
            try { WorkflowHostServiceRegistry.provisionForHost(service); }
            catch (IllegalStateException e) { rejected = true; }
            ok(rejected, "nested provisioning denied");
        }
        count++;
    }
    // A malicious CompletionStage can register a callback and then throw.
    // The second, later callback must never release a permit twice.
    private static void completionRegistrationThrowsAfterRegistration() throws Exception {
        final class UnreliableStage extends CompletableFuture<Reply> {
            @Override public CompletableFuture<Reply> whenComplete(
                    java.util.function.BiConsumer<? super Reply, ? super Throwable> action) {
                super.whenComplete(action);
                throw new IllegalStateException("after-callback-registration");
            }
        }
        UnreliableStage stage = new UnreliableStage();
        Service service = ignored -> stage;
        try (ProvisioningScope scope = WorkflowHostServiceRegistry.provisionForHost(service);
             WorkflowHostServiceRegistry r = WorkflowHostServiceRegistry.forNewContext()) {
            ok(await(r, message("faulty-stage")).status() == Status.FAILED,
                    "invalid completion stage fails closed");
            stage.complete(reply("faulty-stage"));
            java.lang.reflect.Field field = WorkflowHostServiceRegistry.class.getDeclaredField("inFlight");
            field.setAccessible(true);
            AtomicInteger outstanding = (AtomicInteger) field.get(r);
            ok(outstanding.get() == 0, "double-release of one admission slot must not occur");
        }
        count++;
    }
    private static void staleScopeCannotEraseNewProvision() throws Exception {
        Service first = ignored -> CompletableFuture.completedFuture(reply("first"));
        Service second = request -> CompletableFuture.completedFuture(reply(request.requestId()));
        ProvisioningScope stale = WorkflowHostServiceRegistry.provisionForHost(first);
        try (WorkflowHostServiceRegistry consumed = WorkflowHostServiceRegistry.forNewContext()) {
            ok(consumed.isProvisioned(), "initial provision consumed");
        }
        try (ProvisioningScope newer = WorkflowHostServiceRegistry.provisionForHost(second)) {
            stale.close();
            try (WorkflowHostServiceRegistry next = WorkflowHostServiceRegistry.forNewContext()) {
                ok(next.isProvisioned(), "stale scope did not erase newer provision");
                ok(await(next, message("second")).status() == Status.OK, "correct provision usable");
            }
        }
        count++;
    }
    private static void workerIdentityNotThreadName() throws Exception {
        Service service = request -> CompletableFuture.completedFuture(reply(request.requestId()));
        try (ProvisioningScope scope = WorkflowHostServiceRegistry.provisionForHost(service)) {
            WorkflowHostServiceRegistry registry = WorkflowHostServiceRegistry.forNewContext();
            ok(await(registry, message("spawn-worker")).status() == Status.OK, "worker created");
            java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
            Thread other = new Thread(() -> {
                try { registry.close(); } catch (Throwable error) { failed.set(error); }
            }, "ores-workflow-host");
            other.start(); other.join(3000);
            ok(!other.isAlive() && failed.get() == null, "close uses actual worker identity");
            ok(await(registry, message("after-close")).status() == Status.CLOSED, "closed");
        }
        count++;
    }
    private static void adversarialContextConsumesButDeniesProvision() throws Exception {
        Service permitted = request -> CompletableFuture.completedFuture(reply(request.requestId()));
        try (ProvisioningScope scope = WorkflowHostServiceRegistry.provisionForHost(permitted);
             WorkflowHostServiceRegistry untrusted = WorkflowHostServiceRegistry.forNewContext(false)) {
            ok(!untrusted.isProvisioned(), "adversarial context cannot gain host service");
            ok(await(untrusted, message("untrusted")).status() == Status.UNAVAILABLE,
                    "untrusted storage fails closed");
        }
        try (WorkflowHostServiceRegistry later = WorkflowHostServiceRegistry.forNewContext(true)) {
            ok(!later.isProvisioned(), "denied provision not inherited later");
        }
        count++;
    }
    public static void main(String[] args) throws Exception {
        defaultDeny(); oneShotScope(); ownerWorkerAndBackpressure();
        denyForgedReplyAndFailedHost(); noNestedProvision();
        completionRegistrationThrowsAfterRegistration(); staleScopeCannotEraseNewProvision();
        workerIdentityNotThreadName(); adversarialContextConsumesButDeniesProvision();
        System.out.println("WORKFLOW_HOST_REGISTRY_RESULT passed=" + count + " host_only=true");
    }
}
