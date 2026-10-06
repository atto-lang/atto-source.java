package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Explicit synchronized mutable ownership domain created by {@code rt proxy}.
 *
 * <p>A proxy does not publish an actor heap. The wrapped value is owned by this
 * proxy domain and may be accessed only while holding the proxy's fair
 * read/write lock. Source-level lowering must not expose the raw wrapped value
 * outside the dynamic lock scope.</p>
 */
public final class OresProxy<T> {
    public static final class ProxyContendedException extends IllegalStateException {
        public ProxyContendedException(String operation) {
            super(operation + " would block an actor carrier; retry/cooperate or use cooperative proxy access");
        }
    }

    private static final ThreadLocal<OresProxy<?>> HELD_PROXY = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> HELD_WRITE = new ThreadLocal<>();

    private final ActorRuntime runtime;
    private final T value;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public OresProxy(ActorRuntime runtime, T value) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.value = Objects.requireNonNull(value, "value");
        requireCreationAuthority();
    }

    public boolean ownedBy(ActorRuntime candidate) {
        return runtime == candidate;
    }

    public ActorRuntime ownerRuntime() {
        return runtime;
    }

    public <R> R read(Function<? super T, ? extends R> reader) {
        Objects.requireNonNull(reader, "reader");
        requireAccess("rt proxy read");
        boolean entered = enterProxy(false);
        boolean locked = acquireRead();
        try {
            return reader.apply(value);
        } finally {
            if (locked) lock.readLock().unlock();
            exitProxy(entered);
        }
    }

    public <R> R write(Function<? super T, ? extends R> writer) {
        Objects.requireNonNull(writer, "writer");
        requireAccess("rt proxy write");
        boolean entered = enterProxy(true);
        boolean locked = acquireWrite();
        try {
            return writer.apply(value);
        } finally {
            if (locked) lock.writeLock().unlock();
            exitProxy(entered);
        }
    }

    public boolean isWriteLocked() {
        return lock.isWriteLocked();
    }

    public int readLockCount() {
        return lock.getReadLockCount();
    }

    private boolean acquireRead() {
        if (!ActorRuntime.inActorExecution()) {
            lock.readLock().lock();
            return true;
        }
        if (!lock.readLock().tryLock()) {
            throw new ProxyContendedException("rt proxy read");
        }
        return true;
    }

    private boolean acquireWrite() {
        if (!ActorRuntime.inActorExecution()) {
            lock.writeLock().lock();
            return true;
        }
        if (!lock.writeLock().tryLock()) {
            throw new ProxyContendedException("rt proxy write");
        }
        return true;
    }

    private void requireCreationAuthority() {
        ActorRuntime.ActorKind kind = ActorRuntime.currentActorKind();
        if (kind == ActorRuntime.ActorKind.PRIVATE || kind == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException("rt proxy is unavailable to private/untrusted actors");
        }
        IsolatePolicy policy = ActorRuntime.currentActorPolicy();
        if (policy != null) {
            policy.require(IsolatePolicy.Capability.ACTOR_PROXY, "rt proxy");
        }
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        if (current != null && current != runtime) {
            throw new SecurityException("rt proxy cannot capture state from another ActorRuntime");
        }
    }

    private void requireAccess(String operation) {
        ActorRuntime.ActorKind kind = ActorRuntime.currentActorKind();
        if (kind == ActorRuntime.ActorKind.PRIVATE || kind == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException(operation + " is unavailable to private/untrusted actors");
        }
        IsolatePolicy policy = ActorRuntime.currentActorPolicy();
        if (policy != null) {
            policy.require(IsolatePolicy.Capability.ACTOR_PROXY, operation);
        }
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        if (current != null && current != runtime) {
            throw new SecurityException(operation + " crossed ActorRuntime ownership boundary");
        }
    }

    private boolean enterProxy(boolean write) {
        OresProxy<?> held = HELD_PROXY.get();
        if (held == null) {
            HELD_PROXY.set(this);
            HELD_WRITE.set(write);
            return true;
        }
        if (held != this) {
            throw new IllegalStateException(
                    "nested access across different rt proxy values is forbidden; "
                            + "snapshot/read one proxy before entering another");
        }
        if (write && !Boolean.TRUE.equals(HELD_WRITE.get())) {
            throw new IllegalStateException(
                    "rt proxy read-to-write lock upgrade is forbidden; "
                            + "finish the read scope before mutating the proxy");
        }
        return false;
    }

    private void exitProxy(boolean entered) {
        if (entered) {
            HELD_WRITE.remove();
            HELD_PROXY.remove();
        }
    }

    @Override
    public String toString() {
        return "Proxy<" + value.getClass().getSimpleName() + ">";
    }
}
