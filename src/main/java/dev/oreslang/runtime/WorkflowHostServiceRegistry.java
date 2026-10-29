package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Context-local, default-deny workflow storage port. A host chooses the
 * implementation; guest code cannot obtain it through Java interop or receive
 * native handles. A separate typed evaluator intrinsic is still required.
 * Operations run off the actor carrier, and no critical state loan is taken.
 */
public final class WorkflowHostServiceRegistry implements AutoCloseable {
    public enum Operation { ACTIVATE, ACCEPT_STEP, CLAIM_DISPATCH, RECONCILE, FINISH, INSPECT }
    public enum Status { OK, DENIED, UNAVAILABLE, OVERLOADED, CLOSED, FAILED }
    public record Request(String requestId, Operation operation, String tenant,
            String run, String revision, long generation, long epoch,
            long expectedVersion, String step, String messageId, String effectKey,
            String digest, String outcome, String receipt, String capability) { }
    public record Reply(String requestId, Status status, long epoch,
            long version, long prepared, long inDoubt, long committed) {
        public Reply {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(status);
        }
    }
    @FunctionalInterface public interface Service {
        CompletionStage<Reply> dispatch(Request request);
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,96}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern RECEIPT = Pattern.compile("[A-Za-z0-9_.:-]{1,160}");
    // This is a one-shot host provision, not a service locator. Do not use an
    // InheritableThreadLocal: no actor or guest thread may inherit a capability.
    private static final ThreadLocal<Service> PENDING_HOST_SERVICE = new ThreadLocal<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "ores-workflow-host");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile Service service;

    interface ProvisioningScope extends AutoCloseable {
        @Override void close();
    }

    /** Used only from trusted LinkedProgramRunner embedding code. */
    static ProvisioningScope provisionForHost(Service implementation) {
        Objects.requireNonNull(implementation, "host workflow service");
        if (PENDING_HOST_SERVICE.get() != null) {
            throw new IllegalStateException("nested workflow host provisioning refused");
        }
        PENDING_HOST_SERVICE.set(implementation);
        return () -> PENDING_HOST_SERVICE.remove();
    }

    /** A Truffle context consumes the host provision exactly once. */
    static WorkflowHostServiceRegistry forNewContext() {
        WorkflowHostServiceRegistry registry = new WorkflowHostServiceRegistry();
        Service supplied = PENDING_HOST_SERVICE.get();
        PENDING_HOST_SERVICE.remove();
        if (supplied != null) registry.service = supplied;
        return registry;
    }

    /** Default constructor intentionally provisions no authority. */
    public WorkflowHostServiceRegistry() { }
    public boolean isProvisioned() { return service != null && !closed.get(); }

    private static boolean valid(Pattern pattern, String text) {
        return text != null && pattern.matcher(text).matches();
    }
    private static boolean optional(String text) {
        return text != null && text.length() <= 160;
    }
    private static boolean validRequest(Request r) {
        if (r == null || !valid(ID, r.requestId()) || r.operation() == null ||
                !valid(ID, r.tenant()) || !valid(ID, r.run()) ||
                !valid(SHA, r.revision()) || r.generation() < 1 ||
                r.epoch() < 0 || r.expectedVersion() < 0 ||
                r.capability() == null || r.capability().isBlank() ||
                r.capability().length() > 4096 || !optional(r.step()) ||
                !optional(r.messageId()) || !optional(r.effectKey()) ||
                !optional(r.digest()) || !optional(r.outcome()) ||
                !optional(r.receipt())) return false;
        return switch (r.operation()) {
            case ACTIVATE, FINISH, INSPECT -> true;
            case ACCEPT_STEP -> valid(ID, r.step()) && valid(ID, r.messageId()) &&
                    valid(ID, r.effectKey()) && valid(DIGEST, r.digest());
            case CLAIM_DISPATCH -> valid(ID, r.step()) &&
                    valid(ID, r.effectKey()) && valid(DIGEST, r.digest());
            case RECONCILE -> valid(ID, r.step()) &&
                    ("committed".equals(r.outcome()) || "not_applied".equals(r.outcome())) &&
                    valid(RECEIPT, r.receipt());
        };
    }
    private static Reply rejected(Request r, Status status) {
        return new Reply(r == null || r.requestId() == null ? "" : r.requestId(),
                status, -1, -1, -1, -1, -1);
    }

    /** Does not execute a host callback on the caller's thread. */
    public CompletionStage<Reply> submit(Request request) {
        if (closed.get()) return CompletableFuture.completedFuture(rejected(request, Status.CLOSED));
        if (!validRequest(request)) return CompletableFuture.completedFuture(rejected(request, Status.DENIED));
        Service authorized = service;
        if (authorized == null) return CompletableFuture.completedFuture(rejected(request, Status.UNAVAILABLE));
        if (inFlight.incrementAndGet() > 64) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(rejected(request, Status.OVERLOADED));
        }
        CompletableFuture<Reply> answer = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    if (closed.get()) { answer.complete(rejected(request, Status.CLOSED)); return; }
                    CompletionStage<Reply> result = Objects.requireNonNull(authorized.dispatch(request));
                    result.whenComplete((reply, error) -> {
                        try {
                            if (closed.get() || error != null || reply == null ||
                                    !request.requestId().equals(reply.requestId())) {
                                answer.complete(rejected(request, closed.get() ? Status.CLOSED : Status.FAILED));
                            } else answer.complete(reply);
                        } finally { inFlight.decrementAndGet(); }
                    });
                } catch (Throwable error) {
                    answer.complete(rejected(request, Status.FAILED));
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.decrementAndGet();
            answer.complete(rejected(request, closed.get() ? Status.CLOSED : Status.OVERLOADED));
        }
        return answer;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        service = null;
        worker.shutdown();
        // Do not close host-owned storage or revoke its external policy here;
        // the embedding host owns it and the native driver lifetime.
    }
}
