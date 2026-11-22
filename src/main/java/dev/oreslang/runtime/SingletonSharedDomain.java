package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Host-only candidate for one singleton identity per application domain.
 *
 * <p>NOT an executable guest singleton implementation. No compiler lowering,
 * cross-native-isolate sharing, public Java interop, or permission to access
 * actor-private state is provided. This owns an independent immutable snapshot
 * registry and opt-in guarded shared regions. Actor-state RW loans remain
 * sealed to their owning allocation; do not reuse those loans as cross-actor
 * permissions.</p>
 *
 * <p>Runtime must mint principals from authenticated logical actor turns,
 * resolve declaration keys after imports, and prohibit raw handle export.
 * Callbacks execute outside the arbiter monitor. They MUST NOT suspend.</p>
 */
public final class SingletonSharedDomain {
    public record Key(String namespace, String module, String declaration) {
        public Key {
            requireName(namespace);
            requireName(module);
            requireName(declaration);
        }
        private static void requireName(String s) {
            if (s == null || s.isBlank() || s.length() > 256
                    || s.indexOf((char) 0) >= 0) {
                throw new IllegalArgumentException("invalid singleton declaration identity");
            }
        }
    }

    private record Turn(long actor, long incarnation, long execution) { }

    /** An opaque, domain-bound token; never serialize or expose to a guest. */
    public static final class Principal {
        private final SingletonSharedDomain domain;
        private final Turn turn;
        private Principal(SingletonSharedDomain domain, Turn turn) {
            this.domain = domain;
            this.turn = turn;
        }
    }

    public enum Access { READ, WRITE }

    /** Authenticated single-use region admission, not ownership transfer. */
    public static final class Grant {
        private final Region region;
        private final Principal principal;
        private final Access access;
        private final long epoch;
        private boolean consumed;
        private Grant(Region region, Principal principal, Access access, long epoch) {
            this.region = region;
            this.principal = principal;
            this.access = access;
            this.epoch = epoch;
        }
    }

    private final Map<Key, CompletableFuture<Object>> values = new ConcurrentHashMap<>();
    // Protected by the values monitor. Checked compiler publications must never
    // silently reuse a declaration whose resolved schema has changed.
    private final Map<Key, String> compiledSchemas = new HashMap<>();
    private final int maxSnapshots;

    public SingletonSharedDomain() { this(4096); }

    /** Small bounded budgets also make hostile admission testable. */
    SingletonSharedDomain(int maxSnapshots) {
        if (maxSnapshots < 1 || maxSnapshots > 65536) {
            throw new IllegalArgumentException("invalid domain singleton quota");
        }
        this.maxSnapshots = maxSnapshots;
    }

    /** A borrower may cancel a queued request but may not forge its completion. */
    private static final class AdmissionFuture<T> extends CompletableFuture<T> {
        private SecurityException forged() {
            return new SecurityException("runtime-owned future cannot be completed by caller");
        }
        @Override public boolean complete(T value) { throw forged(); }
        @Override public boolean completeExceptionally(Throwable error) { throw forged(); }
        @Override public void obtrudeValue(T value) { throw forged(); }
        @Override public void obtrudeException(Throwable error) { throw forged(); }
        @Override public CompletableFuture<T> completeAsync(
                Supplier<? extends T> supplier) { throw forged(); }
        @Override public CompletableFuture<T> completeAsync(
                Supplier<? extends T> supplier, java.util.concurrent.Executor executor) {
            throw forged();
        }
        @Override public CompletableFuture<T> completeOnTimeout(
                T value, long timeout, java.util.concurrent.TimeUnit unit) { throw forged(); }
        @Override public CompletableFuture<T> orTimeout(
                long timeout, java.util.concurrent.TimeUnit unit) { throw forged(); }
        boolean grantFromRuntime(T value) { return super.complete(value); }
        boolean failFromRuntime(Throwable error) { return super.completeExceptionally(error); }
    }
    private final ThreadLocal<Set<Key>> activeInitializers =
            ThreadLocal.withInitial(HashSet::new);
    private final Map<Turn, Principal> principals = new ConcurrentHashMap<>();
    private final Map<Key, Region> regions = new ConcurrentHashMap<>();

    /**
     * Nonblocking publication receipt. Initialization runs on the first caller
     * outside the shared registry lock, completes exactly once, and is sticky
     * on failure. Compiler stage zero only admits constant primitive initializers.
     * Recursive same-turn initialization fails instead of deadlocking.
     */
    public CompletableFuture<Object> snapshotOnce(Key key, Supplier<?> initialize) {
        return snapshotInternal(key, null, initialize);
    }

    /**
     * Compiler-only immutable declaration publication. The fingerprint covers
     * the checked and canonicalized field names, types, and literal contents.
     * Different code-unit text spelling is okay; a different schema is not.
     * Unchecked and compiler-checked callers never share one registry entry.
     */
    public CompletableFuture<Object> snapshotOnceChecked(
            Key key, String compilerFingerprint, Supplier<?> initialize) {
        if (compilerFingerprint == null
                || !compilerFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid compiler singleton fingerprint");
        }
        return snapshotInternal(key, compilerFingerprint, initialize);
    }

    private CompletableFuture<Object> snapshotInternal(
            Key key, String compilerFingerprint, Supplier<?> initialize) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(initialize, "initialize");
        CompletableFuture<Object> existing;
        CompletableFuture<Object> created = null;
        synchronized (values) {
            existing = values.get(key);
            if (existing != null) {
                String original = compiledSchemas.get(key);
                if (!Objects.equals(original, compilerFingerprint)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("singleton declaration schema mismatch"));
                }
            } else {
                if (values.size() >= maxSnapshots) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("singleton identity quota exceeded"));
                }
                created = new CompletableFuture<>();
                values.put(key, created);
                if (compilerFingerprint != null) {
                    compiledSchemas.put(key, compilerFingerprint);
                }
            }
        }
        if (existing != null) {
            if (activeInitializers.get().contains(key)) {
                existing.completeExceptionally(
                        new IllegalStateException("singleton initializer cycle"));
            }
            // Never leak the registry's writable completion receipt.
            return existing.copy();
        }
        final CompletableFuture<Object> published = created;
        Set<Key> stack = activeInitializers.get();
        stack.add(key);
        try {
            Object produced = initialize.get();
            if (produced instanceof java.util.concurrent.CompletionStage<?>) {
                throw new IllegalStateException("async singleton initializer is not supported");
            }
            published.complete(freeze(produced));
        } catch (Throwable error) {
            published.completeExceptionally(error);
        } finally {
            stack.remove(key);
            if (stack.isEmpty()) activeInitializers.remove();
        }
        return published.copy();
    }

    /** Trusted runtime entry: must be unreachable from guest Java interop. */
    Principal principalForRuntime(long actor, long incarnation, long execution) {
        if (actor < 0 || incarnation < 1 || execution < 0) {
            throw new IllegalArgumentException("invalid actor execution identity");
        }
        Turn id = new Turn(actor, incarnation, execution);
        return principals.computeIfAbsent(id, turn -> new Principal(this, turn));
    }

    /** Exactly one independent shared region for each compiler-resolved key. */
    Region regionForRuntime(Key key, Principal owner, Object initial, int maxPending) {
        Objects.requireNonNull(key, "key");
        requirePrincipal(owner);
        if (maxPending < 0 || maxPending > 4096) {
            throw new IllegalArgumentException("invalid queue budget");
        }
        // Freeze before publication, outside every region metadata lock.
        Object frozen = freeze(initial);
        return regions.computeIfAbsent(
                key, ignored -> new Region(owner, frozen, maxPending));
    }

    private void requirePrincipal(Principal p) {
        if (p == null || p.domain != this || principals.get(p.turn) != p) {
            throw new SecurityException("foreign or forged singleton principal");
        }
    }

    public final class Region {
        private final Object monitor = new Object();
        private final int maxPending;
        private final ArrayDeque<Request> pending = new ArrayDeque<>();
        private final Map<Principal, Lease> active = new IdentityHashMap<>();
        private final Set<Principal> reserved =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private Principal owner;
        private Object state;
        private long epoch = 1;
        private int readers;
        private Lease writer;
        private int notificationsInFlight;
        private int callbacksInFlight;
        private boolean closed;

        private Region(Principal owner, Object state, int maxPending) {
            this.owner = owner;
            this.state = state;
            this.maxPending = maxPending;
        }

        /** Runtime authorization must be checked *before* this entry point. */
        Grant delegateForRuntime(Principal ownerToken, Principal borrower, Access access) {
            SingletonSharedDomain.this.requirePrincipal(borrower);
            Objects.requireNonNull(access, "access");
            synchronized (monitor) {
                if (closed || owner != ownerToken) {
                    throw new SecurityException("only region owner may delegate");
                }
                return new Grant(this, borrower, access, epoch);
            }
        }

        /** Queue admission never parks the scheduler's carrier thread. */
        public CompletableFuture<Lease> acquire(Principal borrower, Grant grant) {
            ArrayList<Runnable> completions = new ArrayList<>();
            Request queued = null;
            CompletableFuture<Lease> answer;
            synchronized (monitor) {
                if (closed || borrower == null || borrower.domain != SingletonSharedDomain.this
                        || principals.get(borrower.turn) != borrower
                        || grant == null || grant.region != this
                        || grant.principal != borrower || grant.epoch != epoch
                        || grant.consumed) {
                    return CompletableFuture.failedFuture(
                            new SecurityException("stale or forged singleton grant"));
                }
                if (reserved.contains(borrower)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("recursive logical execution loan"));
                }
                boolean immediate = pending.isEmpty() && canGrant(grant.access);
                if (!immediate && pending.size() >= maxPending) {
                    // A rejected request must not burn a linear grant.
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("singleton admission queue full"));
                }
                grant.consumed = true;
                reserved.add(borrower);
                if (immediate) {
                    AdmissionFuture<Lease> ready = new AdmissionFuture<>();
                    ready.grantFromRuntime(newLoan(borrower, grant.access));
                    return ready;
                }
                queued = new Request(borrower, grant.access);
                pending.addLast(queued);
                answer = queued.answer;
            }
            final Request request = queued;
            answer.whenComplete((loan, error) -> {
                if (answer.isCancelled()) cancelQueued(request);
            });
            runCompletions(completions);
            return answer;
        }

        public int activeReaders() {
            synchronized (monitor) { return readers; }
        }
        public int activeWriters() {
            synchronized (monitor) { return writer == null ? 0 : 1; }
        }
        public int pendingCount() {
            synchronized (monitor) { return pending.size(); }
        }
        public boolean physicallyRetired() {
            synchronized (monitor) {
                return closed && readers == 0 && writer == null && active.isEmpty()
                        && reserved.isEmpty() && pending.isEmpty()
                        && notificationsInFlight == 0 && callbacksInFlight == 0;
            }
        }

        /** Ownership transfer fences all old grants and requires quiescence. */
        void transferOwnerForRuntime(Principal oldOwner, Principal nextOwner) {
            SingletonSharedDomain.this.requirePrincipal(nextOwner);
            synchronized (monitor) {
                if (closed || owner != oldOwner || !reserved.isEmpty()
                        || !pending.isEmpty() || callbacksInFlight != 0
                        || notificationsInFlight != 0) {
                    throw new IllegalStateException("ownership transfer requires quiescence");
                }
                if (epoch == Long.MAX_VALUE) throw new IllegalStateException("epoch exhausted");
                owner = nextOwner;
                epoch++;
            }
        }

        /** STOP denies new accesses but leaves physical loans pinned. */
        void stopForRuntime(Principal ownerToken) {
            ArrayList<Runnable> completions = new ArrayList<>();
            synchronized (monitor) {
                if (ownerToken != owner) throw new SecurityException("foreign STOP");
                if (closed) return;
                if (epoch == Long.MAX_VALUE) throw new IllegalStateException("epoch exhausted");
                closed = true;
                epoch++;
                while (!pending.isEmpty()) {
                    Request item = pending.removeFirst();
                    reserved.remove(item.principal);
                    addCompletion(completions, () -> item.answer.failFromRuntime(
                            new IllegalStateException("singleton region STOP")));
                }
            }
            runCompletions(completions);
        }

        private boolean canGrant(Access access) {
            // Logical release must not admit a successor while the old
            // guest write callback is still physically on a carrier.
            return callbacksInFlight == 0 && writer == null
                    && (access == Access.READ ? readers < 1024 : readers == 0);
        }

        private Lease newLoan(Principal borrower, Access access) {
            Lease loan = new Lease(borrower, access, epoch);
            if (access == Access.READ) {
                if (writer != null || readers >= 1024) throw new IllegalStateException("reader overlap");
                readers++;
            } else {
                if (writer != null || readers != 0) throw new IllegalStateException("writer overlap");
                writer = loan;
            }
            if (active.putIfAbsent(borrower, loan) != null) {
                throw new IllegalStateException("duplicate actor execution loan");
            }
            return loan;
        }

        private void addCompletion(List<Runnable> actions, Runnable task) {
            notificationsInFlight++;
            actions.add(() -> {
                try { task.run(); }
                finally {
                    synchronized (monitor) { notificationsInFlight--; }
                }
            });
        }

        private void promote(List<Runnable> actions) {
            if (closed || writer != null) return;
            while (!pending.isEmpty()) {
                Request item = pending.peekFirst();
                if (!canGrant(item.access)) return;
                pending.removeFirst();
                Lease loan = newLoan(item.principal, item.access);
                addCompletion(actions, () -> {
                    if (!item.answer.grantFromRuntime(loan)) loan.release(item.principal);
                });
                if (item.access == Access.WRITE) return;
            }
        }

        private void cancelQueued(Request request) {
            ArrayList<Runnable> actions = new ArrayList<>();
            synchronized (monitor) {
                if (pending.remove(request)) {
                    reserved.remove(request.principal);
                    promote(actions);
                }
            }
            runCompletions(actions);
        }

        private void runCompletions(List<Runnable> actions) {
            // Future completion invokes user observers synchronously; NEVER
            // call it under monitor. The in-flight ledger fences reclamation.
            for (Runnable action : actions) action.run();
        }

        private final class Request {
            final Principal principal;
            final Access access;
            final AdmissionFuture<Lease> answer = new AdmissionFuture<>();
            Request(Principal principal, Access access) {
                this.principal = principal;
                this.access = access;
            }
        }

        public final class Lease {
            private final Principal principal;
            private final Access access;
            private final long generation;
            private final AtomicBoolean writing = new AtomicBoolean();
            private boolean released;

            private Lease(Principal principal, Access access, long generation) {
                this.principal = principal;
                this.access = access;
                this.generation = generation;
            }
            private void check(Principal caller) {
                if (caller != principal || closed || released || generation != epoch
                        || active.get(principal) != this) {
                    throw new SecurityException("stale, foreign, or revoked singleton loan");
                }
            }

            /** Immutable copied/validated backing, never raw actor heap memory. */
            public Object read(Principal caller) {
                synchronized (monitor) {
                    check(caller);
                    return state;
                }
            }

            /**
             * Writes are transactional. Candidate construction runs with the
             * exclusive logical loan but *outside* the metadata lock.
             * Re-entrant release/STOP cannot publish a stale write.
             */
            public void write(Principal caller, Function<Object, Object> update) {
                Objects.requireNonNull(update, "update");
                if (!writing.compareAndSet(false, true)) {
                    throw new IllegalStateException("concurrent write through same loan");
                }
                Object before;
                try {
                    synchronized (monitor) {
                        check(caller);
                        if (access != Access.WRITE || writer != this) {
                            throw new SecurityException("write requires exclusive loan");
                        }
                        callbacksInFlight++;
                        before = state;
                    }
                    try {
                        Object candidate = update.apply(before);
                        if (candidate instanceof java.util.concurrent.CompletionStage<?>) {
                            throw new IllegalStateException("await during singleton write loan forbidden");
                        }
                        Object safe = freeze(candidate);
                        synchronized (monitor) {
                            check(caller);
                            if (writer != this) {
                                throw new SecurityException("write loan lost before publication");
                            }
                            state = safe;
                        }
                    } finally {
                        ArrayList<Runnable> actions = new ArrayList<>();
                        synchronized (monitor) {
                            callbacksInFlight--;
                            // A callback may have released its own loan. Only
                            // after it physically unwinds may queued work enter.
                            promote(actions);
                        }
                        runCompletions(actions);
                    }
                } finally { writing.set(false); }
            }

            public void release(Principal caller) {
                ArrayList<Runnable> actions = new ArrayList<>();
                synchronized (monitor) {
                    if (caller != principal) {
                        throw new SecurityException("foreign singleton loan release");
                    }
                    if (released) return;
                    released = true;
                    if (active.remove(principal) != this || !reserved.remove(principal)) {
                        throw new IllegalStateException("singleton loan ledger corrupted");
                    }
                    if (access == Access.READ) readers--;
                    else {
                        if (writer != this) throw new IllegalStateException("writer ledger corrupted");
                        writer = null;
                    }
                    promote(actions);
                }
                runCompletions(actions);
            }
        }
    }

    private static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>(), new int[]{0}, 0);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> seen,
                                 int[] budget, int depth) {
        if (++budget[0] > 2048 || depth > 64) {
            throw new IllegalArgumentException("singleton immutable snapshot budget exceeded");
        }
        if (value == null || value instanceof Boolean || value instanceof Integer
                || value instanceof Long) return value;
        if (value instanceof String s) {
            if (s.length() > 8192) throw new IllegalArgumentException("singleton string too long");
            return s;
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) throw new IllegalArgumentException("nonfinite singleton value");
            return d;
        }
        if (value instanceof Float f) {
            if (!Float.isFinite(f)) throw new IllegalArgumentException("nonfinite singleton value");
            return f;
        }
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic singleton snapshot");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                if (map.size() > 1024) throw new IllegalArgumentException("singleton map too large");
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String key) || key.isBlank()
                            || "__proto__".equals(key) || "constructor".equals(key)) {
                        throw new IllegalArgumentException("unsafe singleton map key");
                    }
                    if (copy.putIfAbsent(key, freeze(e.getValue(), seen, budget, depth + 1)) != null) {
                        throw new IllegalArgumentException("duplicate singleton map key");
                    }
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                if (list.size() > 1024) throw new IllegalArgumentException("singleton list too large");
                List<Object> copy = new ArrayList<>();
                for (Object element : list) copy.add(freeze(element, seen, budget, depth + 1));
                return Collections.unmodifiableList(copy);
            }
            throw new IllegalArgumentException("unproved mutable singleton field type: "
                    + value.getClass().getName());
        } finally {
            seen.remove(value);
        }
    }
}
