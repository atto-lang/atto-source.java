package dev.oreslang.runtime;

/**
     * Structured Task barrier. A cancelled Future alone is NOT a quiescence
     * witness. Each child holds its ticket until both its terminal notification
     * and the scheduler's post-carrier retirement callback have occurred.
     */
    public final class TaskScope {
        private final OresFuture<Void> drained;
        private OresFuture.Callback<Void> completion;
        private int active;
        private boolean closing;
        private boolean published;
        private Throwable firstFailure;

        public TaskScope() {
            drained = OresFuture.fromCallback(callback -> completion = callback);
        }

        public synchronized Registration reserve() {
            // A still-running child may spawn another child while the parent
            // scope is closing. No new child can appear after active reaches 0.
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
            if (failure != null) completion.reject(failure);
            else completion.resolve(null);
        }

        public static final class Registration {
            private final TaskScope scope;
            private boolean quiescent;
            private boolean terminal;
            private boolean notified;
            private Throwable failure;

            private Registration(TaskScope scope) { this.scope = scope; }

            public void attach(OresFuture<?> future) {
                future.whenCompleteRuntime((value, cause) -> settle(cause, false));
            }

            public void quiesced() { settle(null, true); }

            public void failedAdmission(Throwable cause) {
                synchronized (this) {
                    if (notified) return;
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
