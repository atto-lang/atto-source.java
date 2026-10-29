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
    public static void main(String[] args) throws Exception {
        defaultDeny(); oneShotScope(); ownerWorkerAndBackpressure();
        denyForgedReplyAndFailedHost(); noNestedProvision();
        System.out.println("WORKFLOW_HOST_REGISTRY_RESULT passed=" + count + " host_only=true");
    }
}
