package dev.oreslang.runtime;

/**
     * Structured Task barrier. A cancelled Future alone is NOT a quiescence
     * witness. Each child holds its ticket until both its terminal notification
     * and the scheduler's post-carrier retirement callback have occurred.
     */
    public final class TaskScope {
        private final OresFuture<Void> drained;
        private int active;
        private boolean closing;
        private boolean published;
        private Throwable firstFailure;

        public TaskScope() {
            // A join is an internal lifetime gate, never a cancellable user Future.
            // An externally cancelled join could otherwise release borrowed memory
            // while a child still owns its physical execution lease.
            drained = new OresFuture<>(() -> false, () -> { });
        }

        public synchronized Registration reserve() {
            // Phase 1 allows a nested Task spawned while a previously
            // admitted child remains active. The compiler has not yet
            // installed provenance-carrying lifetime permits, so scoped
            // borrowed captures must stay disabled.
            if (closing && active == 0) {
                throw new IllegalStateException("Task spawned after scope quiescence");
            }
            if (active == Integer.MAX_VALUE) {
                throw new IllegalStateException("Task scope admission exhausted");
            }
            active++;
            return new Registration(this);
        }

        public OresFuture<Void> close() {
            synchronized (this) { closing = true; }
            publishIfReady();
            return drained;
        }

        private void childDone(Throwable failure) {
            synchronized (this) {
                if (active == 0) throw new IllegalStateException("Task scope ticket underflow");
                if (failure != null) {
                    if (firstFailure == null) firstFailure = failure;
                    else if (firstFailure != failure) firstFailure.addSuppressed(failure);
                }
                active--;
            }
            publishIfReady();
        }

        private void publishIfReady() {
            Throwable failure;
            synchronized (this) {
                if (published || !closing || active != 0) return;
                published = true;
                failure = firstFailure;
            }
            if (failure != null) drained.failFromRuntime(failure);
            else drained.completeFromRuntime(null);
        }

        public static final class Registration {
            private final TaskScope scope;
            private boolean quiescent;
            private boolean terminal;
            private boolean notified;
            private boolean attached;
            private Throwable failure;

            private Registration(TaskScope scope) { this.scope = scope; }

            public void attach(OresFuture<?> future) {
                java.util.Objects.requireNonNull(future, "child Future");
                synchronized (this) {
                    if (attached || notified || terminal) {
                        throw new IllegalStateException("Task scope ticket already attached or retired");
                    }
                    attached = true;
                }
                future.whenCompleteRuntime((value, cause) -> settle(cause, false));
            }

            public void quiesced() { settle(null, true); }

            public void failedAdmission(Throwable cause) {
                java.util.Objects.requireNonNull(cause, "admission failure");
                synchronized (this) {
                    if (notified) return;
                    if (attached) {
                        // Once admitted, only the real scheduler callbacks
                        // may establish terminal state and physical quiescence.
                        throw new IllegalStateException(
                                "cannot retire an attached child as failed admission", cause);
                    }
                    failure = cause;
                    terminal = true;
                    quiescent = true;
                }
                notifyIfReady();
            }

            private void settle(Throwable cause, boolean isQuiescence) {
                synchronized (this) {
                    if (isQuiescence) quiescent = true;
                    else {
                        terminal = true;
                        failure = cause;
                    }
                }
                notifyIfReady();
            }

            private void notifyIfReady() {
                Throwable cause;
                synchronized (this) {
                    if (notified || !quiescent || !terminal) return;
                    notified = true;
                    cause = failure;
                }
                scope.childDone(cause);
            }
        }
    }
