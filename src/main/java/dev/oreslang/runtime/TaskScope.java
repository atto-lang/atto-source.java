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
        private boolean cancellationRequested;
        private final java.util.Set<Registration> children =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        public TaskScope() {
            // A join is an internal lifetime gate, never a cancellable user Future.
            // An externally cancelled join could otherwise release borrowed memory
            // while a child still owns its physical execution lease.
            drained = new OresFuture<>(() -> false, () -> { });
        }

        public synchronized Registration reserve() {
            return reserve(null);
        }

        /**
         * Root admissions are permitted only while the lexical scope is open.
         * After close(), a child may admit descendants only while holding a
         * live ticket to this exact scope. The ticket is not a guest value.
         * Caller must pass the logical child continuation's own ticket.
         */
        public synchronized Registration reserve(Registration parent) {
            // Parent cancellation seals admissions completely; permitting even
            // a previously executing child to spawn here after revocation can
            // create work that escaped the cancellation snapshot.
            if (cancellationRequested) {
                throw new IllegalStateException("Task spawned into cancelled scope");
            }
            if (closing && (parent == null || !parent.maySpawnInto(this))) {
                throw new IllegalStateException(
                        "Task spawned into closing scope without a live child permit");
            }
            if (active == Integer.MAX_VALUE) {
                throw new IllegalStateException("Task scope admission exhausted");
            }
            active++;
            Registration ticket = new Registration(this);
            children.add(ticket);
            return ticket;
        }

        public OresFuture<Void> close() {
            synchronized (this) { closing = true; }
            publishIfReady();
            return drained;
        }

        /** Observe the internal barrier without closing this lexical scope. */
        public OresFuture<Void> drained() { return drained; }

        /**
         * Called after the owning SourceTask's guest carrier exits. Revokes
         * every admitted child, but does not release its ticket until BOTH
         * terminal notification and scheduler quiescence have occurred.
         * Repeated calls are safe; cancellation runs outside the scope lock.
         */
        public OresFuture<Void> cancelAndDrain() {
            Registration[] admitted;
            synchronized (this) {
                closing = true;
                cancellationRequested = true;
                if (firstFailure == null) {
                    firstFailure = new java.util.concurrent.CancellationException(
                            "enclosing scoped Task was cancelled");
                }
                admitted = children.toArray(new Registration[0]);
            }
            for (Registration ticket : admitted) ticket.cancelAttached();
            publishIfReady();
            return drained;
        }

        private synchronized boolean isCancellationRequested() {
            return cancellationRequested;
        }

        private void childDone(Registration ticket, Throwable failure) {
            synchronized (this) {
                if (active == 0) throw new IllegalStateException("Task scope ticket underflow");
                if (failure != null) {
                    if (firstFailure == null) firstFailure = failure;
                    else if (firstFailure != failure) firstFailure.addSuppressed(failure);
                }
                if (!children.remove(ticket)) {
                    throw new IllegalStateException("Task scope ticket retired twice");
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
            private boolean beganExecution;
            private OresFuture<?> attachedFuture;
            private Throwable failure;

            private Registration(TaskScope scope) { this.scope = scope; }

            private synchronized boolean maySpawnInto(TaskScope expected) {
                // Terminal cancellation revokes admissions even if the
                // cancelled child has not yet physically left its carrier.
                return scope == expected && beganExecution
                        && !terminal && !quiescent && !notified;
            }

            /**
             * Source continuation's first scheduler dispatch has begun.
             * Reservation alone never grants authority to spawn after close().
             * A cancellation that wins before the first dispatch revokes the
             * ticket without granting a late-admission capability.
             */
            public synchronized void beganExecution() {
                if (terminal || quiescent || notified) {
                    throw new IllegalStateException(
                            "retired scoped Task cannot begin execution");
                }
                beganExecution = true;
            }

            public void attach(OresFuture<?> future) {
                java.util.Objects.requireNonNull(future, "child Future");
                if (future == scope.drained) {
                    throw new IllegalArgumentException(
                            "Task scope cannot await its own completion barrier");
                }
                synchronized (this) {
                    if (attached || notified || terminal) {
                        throw new IllegalStateException("Task scope ticket already attached or retired");
                    }
                    attached = true;
                    attachedFuture = future;
                }
                future.whenCompleteRuntime((value, cause) -> settle(cause, false));
                if (scope.isCancellationRequested()) future.cancel(false);
            }

            private void cancelAttached() {
                OresFuture<?> future;
                synchronized (this) { future = attachedFuture; }
                if (future != null) future.cancel(false);
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
                scope.childDone(this, cause);
            }
        }
    }
