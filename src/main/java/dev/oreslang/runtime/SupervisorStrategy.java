package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Restart strategies for a supervisor actor, built on the parent-only
 * CONTROL callback {@link ActorRuntime.Behavior#onChildFailure}.
 *
 * <p>The supervisor starts its children in declaration order. When a child
 * fails, the strategy decides which children to replace:</p>
 * <ul>
 *   <li>{@link Mode#ONE_FOR_ONE}: only the failed child;</li>
 *   <li>{@link Mode#ONE_FOR_ALL}: every child;</li>
 *   <li>{@link Mode#REST_FOR_ONE}: the failed child and every child declared
 *       after it.</li>
 * </ul>
 *
 * <p>Each replacement is a <b>new incarnation</b> (spec chapter 27, section 4):
 * a new ActorId, a new heap and new stacks. Refs to the old incarnation stay
 * fenced. Siblings being replaced are cancelled (non-blocking, structured)
 * in reverse declaration order, then all replacements start in declaration
 * order. Notices from incarnations the supervisor already replaced are stale
 * and ignored, so cancelling a sibling never triggers a second restart.</p>
 *
 * <p>Whether a terminated child is replaced at all depends on its
 * {@link Restart} type. A {@link ActorRuntime.ActorFailureKind#HOST_FATAL}
 * failure is never restarted: it escalates immediately.</p>
 *
 * <p>Restart intensity is bounded by a {@link SupervisorRestartWindow}: at most
 * {@code maxRestarts} restart events in a rolling window. A failure that would
 * exceed it is not restarted. The supervisor fails instead with a cause-free
 * {@link RestartIntensityExceededException}, which escalates to its own parent
 * through the same CONTROL lane.</p>
 *
 * <p>All decisions run on the supervisor's own serialized turn; nothing here
 * blocks a carrier or waits for a child to terminate.</p>
 */
public final class SupervisorStrategy {

    public enum Mode { ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE }

    /** When a terminated child is replaced. */
    public enum Restart {
        /** Replaced after any termination notice. */
        PERMANENT,
        /** Replaced only after an abnormal termination, not after cancellation. */
        TRANSIENT,
        /** Never replaced. */
        TEMPORARY
    }

    /** One declared child: a stable name plus how to start a fresh incarnation. */
    public record ChildSpec<C>(
            String name,
            ActorRuntime.ActorKind kind,
            IsolatePolicy policy,
            Restart restart,
            ActorRuntime.BehaviorFactory<C> factory) {
        public ChildSpec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(restart, "restart");
            Objects.requireNonNull(factory, "factory");
            if (name.isEmpty() || name.length() > 128) {
                throw new IllegalArgumentException("child name must be 1..128 characters");
            }
        }
    }

    /** Supervisor failure when the restart budget is exhausted. Cause-free by design. */
    public static final class RestartIntensityExceededException extends RuntimeException {
        private final String child;
        private final int maxRestarts;

        RestartIntensityExceededException(String child, int maxRestarts, Duration window) {
            super("restart intensity exceeded: child '" + child + "' failed after "
                    + maxRestarts + " restarts within " + window, null, false, false);
            this.child = child;
            this.maxRestarts = maxRestarts;
        }

        public String child() { return child; }
        public int maxRestarts() { return maxRestarts; }
    }

    /** Supervisor failure when a child dies HOST_FATAL. Cause-free by design. */
    public static final class FatalChildFailureException extends RuntimeException {
        FatalChildFailureException(String child) {
            super("child '" + child + "' failed HOST_FATAL; escalating without restart",
                    null, false, false);
        }
    }

    /**
     * Host-readable view of a supervisor's current children. Safe to read from
     * any thread; only the supervisor's own turn writes it.
     */
    public static final class Children {
        private final Map<String, ActorRuntime.ActorRef<?>> current = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> starts = new ConcurrentHashMap<>();
        private final AtomicLong restartEvents = new AtomicLong();
        private final AtomicLong staleNotices = new AtomicLong();

        /** The live incarnation's ref, if the child is currently running. */
        public Optional<ActorRuntime.ActorRef<?>> current(String name) {
            return Optional.ofNullable(current.get(name));
        }

        @SuppressWarnings("unchecked")
        public <C> Optional<ActorRuntime.ActorRef<C>> current(String name, Class<C> ignoredMessageType) {
            return Optional.ofNullable((ActorRuntime.ActorRef<C>) current.get(name));
        }

        /** How many incarnations of this child have been started, including the first. */
        public long starts(String name) {
            AtomicLong count = starts.get(name);
            return count == null ? 0L : count.get();
        }

        /** Restart events admitted by the intensity window. */
        public long restartEvents() { return restartEvents.get(); }

        /** Notices ignored because their incarnation was already replaced. */
        public long staleNotices() { return staleNotices.get(); }
    }

    private SupervisorStrategy() { }

    /**
     * A restart supervisor. Spawn it like any actor ({@code spawnShared},
     * {@code spawnPrivate}) or declare it as a {@link ChildSpec} of another
     * supervisor to build a supervision tree. Its own mail is ignored: a
     * supervisor acts only on CONTROL.
     * Like any actor, it is constructed on its first turn: call
     * {@code ready()} on the spawned ref to start it and its children.
     *
     * <p>Every child factory must be stateless, exactly as for any other
     * actor spawn; this is checked here, eagerly.</p>
     *
     * @param children host-readable directory the supervisor keeps current
     */
    public static <M> SupervisorFactory<M> supervisor(
            Mode mode,
            int maxRestarts,
            Duration window,
            List<ChildSpec<?>> specs,
            Children children) {
        return supervisor(mode, maxRestarts, window, specs, System::nanoTime, children);
    }

    /** Test seam: an explicit monotonic clock. */
    static <M> SupervisorFactory<M> supervisor(
            Mode mode,
            int maxRestarts,
            Duration window,
            List<ChildSpec<?>> specs,
            LongSupplier clock,
            Children children) {
        return new SupervisorFactory<>(mode, maxRestarts, window, specs, clock, children);
    }

    /**
     * The runtime-owned factory for a restart supervisor. The runtime admits
     * it in place of the stateless-factory rule because what it carries is
     * immutable child specs (each with a stateless factory), not host state.
     */
    public static final class SupervisorFactory<M> implements ActorRuntime.BehaviorFactory<M> {
        private final Mode mode;
        private final int maxRestarts;
        private final Duration window;
        private final List<ChildSpec<?>> specs;
        private final LongSupplier clock;
        private final Children children;

        private SupervisorFactory(Mode mode, int maxRestarts, Duration window,
                                  List<ChildSpec<?>> specs, LongSupplier clock, Children children) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.window = Objects.requireNonNull(window, "window");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.children = Objects.requireNonNull(children, "children");
            this.specs = List.copyOf(specs);
            if (this.specs.isEmpty()) throw new IllegalArgumentException("a supervisor needs at least one child");
            Set<String> names = new HashSet<>();
            for (ChildSpec<?> spec : this.specs) {
                if (!names.add(spec.name())) {
                    throw new IllegalArgumentException("duplicate child name '" + spec.name() + "'");
                }
            }
            new SupervisorRestartWindow(maxRestarts, window.toNanos()); // validates the budget
            this.maxRestarts = maxRestarts;
            requireStatelessChildren();
        }

        /** Every child factory obeys the ordinary stateless-factory rule. */
        void requireStatelessChildren() {
            for (ChildSpec<?> spec : specs) {
                if (spec.factory() instanceof SupervisorFactory<?> nested) {
                    nested.requireStatelessChildren();
                } else {
                    ActorRuntime.requireStatelessActorFactory(spec.factory());
                }
            }
        }

        @Override
        public ActorRuntime.Behavior<M> create(ActorRuntime.ActorContext<M> context) {
            Supervisor<M> supervisor = new Supervisor<>(mode, specs,
                    new SupervisorRestartWindow(maxRestarts, window.toNanos()), window, clock, children);
            supervisor.startAll(context);
            return supervisor;
        }
    }

    private static final class Supervisor<M> implements ActorRuntime.Behavior<M> {
        private final Mode mode;
        private final List<ChildSpec<?>> specs;
        private final SupervisorRestartWindow intensity;
        private final Duration window;
        private final LongSupplier clock;
        private final Children children;
        /** Live handle per declaration index; null when not running. */
        private final ActorRuntime.ActorHandle<?>[] live;

        Supervisor(Mode mode, List<ChildSpec<?>> specs,
                   SupervisorRestartWindow intensity, Duration window, LongSupplier clock,
                   Children children) {
            this.mode = mode;
            this.specs = specs;
            this.intensity = intensity;
            this.window = window;
            this.clock = clock;
            this.children = children;
            this.live = new ActorRuntime.ActorHandle<?>[specs.size()];
        }

        void startAll(ActorRuntime.ActorContext<M> context) {
            for (int i = 0; i < specs.size(); i++) start(i, context);
        }

        private void start(int index, ActorRuntime.ActorContext<M> context) {
            ChildSpec<?> spec = specs.get(index);
            ActorRuntime.ActorHandle<?> handle =
                    context.runtime().spawnChild(spec.kind(), spec.policy(), spec.factory());
            live[index] = handle;
            children.current.put(spec.name(), handle.ref());
            children.starts.computeIfAbsent(spec.name(), ignored -> new AtomicLong()).incrementAndGet();
            // Construct the child now (a nested supervisor starts its own children),
            // so a child that cannot start fails here rather than on its first mail.
            handle.ref().ready();
        }

        private void retire(int index) {
            ActorRuntime.ActorHandle<?> handle = live[index];
            if (handle == null) return;
            live[index] = null; // its notices are stale from here on
            children.current.remove(specs.get(index).name(), handle.ref());
            handle.cancel();
        }

        private int indexOf(ActorRuntime.ActorFailureNotice notice) {
            for (int i = 0; i < live.length; i++) {
                ActorRuntime.ActorHandle<?> handle = live[i];
                if (handle != null && handle.id().equals(notice.actor())
                        && handle.ref().generation() == notice.generation()) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public void onMessage(M message, ActorRuntime.ActorContext<M> context) {
            // A supervisor acts only on CONTROL; its own mail carries no authority.
        }

        @Override
        public void onChildFailure(ActorRuntime.ActorFailureNotice notice,
                                   ActorRuntime.ActorContext<M> context) {
            int failed = indexOf(notice);
            if (failed < 0) {
                children.staleNotices.incrementAndGet();
                return;
            }
            ChildSpec<?> spec = specs.get(failed);
            // The failed incarnation is gone; drop it before deciding anything.
            live[failed] = null;
            children.current.remove(spec.name());
            if (notice.failureKind() == ActorRuntime.ActorFailureKind.HOST_FATAL) {
                throw new FatalChildFailureException(spec.name());
            }
            boolean cancelled = notice.failureKind() == ActorRuntime.ActorFailureKind.CANCELLED;
            boolean replace = switch (spec.restart()) {
                case PERMANENT -> true;
                case TRANSIENT -> !cancelled;
                case TEMPORARY -> false;
            };
            if (!replace) return;
            if (!intensity.tryAcquireFast(clock.getAsLong())) {
                throw new RestartIntensityExceededException(spec.name(), intensity.maxRestarts(), window);
            }
            children.restartEvents.incrementAndGet();
            List<Integer> affected = affected(failed);
            // Stop the siblings being replaced in reverse declaration order...
            for (int k = affected.size() - 1; k >= 0; k--) {
                int index = affected.get(k);
                if (index != failed) retire(index);
            }
            // ...then start every replacement in declaration order. A TEMPORARY
            // sibling that was running is stopped but not started again.
            for (int index : affected) {
                if (index == failed || specs.get(index).restart() != Restart.TEMPORARY) {
                    start(index, context);
                }
            }
        }

        private List<Integer> affected(int failed) {
            List<Integer> affected = new ArrayList<>();
            switch (mode) {
                case ONE_FOR_ONE -> affected.add(failed);
                case ONE_FOR_ALL -> {
                    for (int i = 0; i < specs.size(); i++) {
                        if (i == failed || live[i] != null) affected.add(i);
                    }
                }
                case REST_FOR_ONE -> {
                    for (int i = failed; i < specs.size(); i++) {
                        if (i == failed || live[i] != null) affected.add(i);
                    }
                }
            }
            return affected;
        }
    }
}
