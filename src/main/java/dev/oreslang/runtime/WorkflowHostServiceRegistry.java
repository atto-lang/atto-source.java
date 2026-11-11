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
import java.util.concurrent.atomic.AtomicReference;
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
    private record Provision(Service service, Thread owner) { }
    private static final ThreadLocal<Provision> PENDING_HOST_SERVICE = new ThreadLocal<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicReference<Thread> workerThread = new AtomicReference<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object completionMonitor = new Object();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "ores-workflow-host");
                thread.setDaemon(true);
                workerThread.set(thread);
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
        Provision token = new Provision(implementation, Thread.currentThread());
        PENDING_HOST_SERVICE.set(token);
        return () -> {
            if (Thread.currentThread() != token.owner()) {
                throw new IllegalStateException("workflow provision scope closed on another thread");
            }
            // A consumed scope must not erase a newer provision on the same
            // thread. This also protects a later context from stale cleanup.
            if (PENDING_HOST_SERVICE.get() == token) PENDING_HOST_SERVICE.remove();
        };
    }

    /** A Truffle context consumes the host provision exactly once. */
    static WorkflowHostServiceRegistry forNewContext() {
        return forNewContext(true);
    }

    /** Adversarial isolates consume and discard any ambient host provision. */
    static WorkflowHostServiceRegistry forNewContext(boolean trustedContext) {
        WorkflowHostServiceRegistry registry = new WorkflowHostServiceRegistry();
        Provision supplied = PENDING_HOST_SERVICE.get();
        PENDING_HOST_SERVICE.remove();
        if (trustedContext && supplied != null) registry.service = supplied.service();
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

    private void releaseSlot() {
        inFlight.decrementAndGet();
        synchronized (completionMonitor) { completionMonitor.notifyAll(); }
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
        // An adversarial/custom CompletionStage may invoke a registered
        // continuation and still throw from whenComplete(). Guard all paths.
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) releaseSlot(); };
        try {
            worker.execute(() -> {
                try {
                    if (closed.get()) { answer.complete(rejected(request, Status.CLOSED)); release.run(); return; }
                    CompletionStage<Reply> result = Objects.requireNonNull(authorized.dispatch(request));
                    result.whenComplete((reply, error) -> {
                        try {
                            if (closed.get() || error != null || reply == null ||
                                    !request.requestId().equals(reply.requestId())) {
                                answer.complete(rejected(request, closed.get() ? Status.CLOSED : Status.FAILED));
                            } else answer.complete(reply);
                        } finally { release.run(); }
                    });
                } catch (Throwable error) {
                    answer.complete(rejected(request, Status.FAILED));
                    release.run();
                }
            });
        } catch (RejectedExecutionException rejected) {
            release.run();
            answer.complete(rejected(request, closed.get() ? Status.CLOSED : Status.OVERLOADED));
        }
        return answer;
    }

    @Override public synchronized void close() {
        if (retired.get()) return;
        // Check the actual worker identity, not its guessable thread name;
        // a thread named "ores-workflow-host" is not a storage worker.
        if (Thread.currentThread() == workerThread.get()) {
            throw new IllegalStateException("cannot close registry from its storage worker");
        }
        closed.set(true);
        service = null;
        worker.shutdown();
        // A host must NOT destroy JNI storage while an asynchronous callback
        // might still be using it. Fail closed if draining is not possible.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException("workflow worker not retired; retain host storage");
                    }
                    break;
                } catch (InterruptedException ex) { interrupted = true; }
            }
            synchronized (completionMonitor) {
                while (inFlight.get() > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IllegalStateException("workflow request unresolved; retain host storage");
                    }
                    try { TimeUnit.NANOSECONDS.timedWait(completionMonitor, remaining); }
                    catch (InterruptedException ex) { interrupted = true; }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        retired.set(true);
        // Do not close host-owned storage or revoke its external policy here;
        // the embedding host owns it and the native driver lifetime.
    }
}
