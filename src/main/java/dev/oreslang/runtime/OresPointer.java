package dev.oreslang.runtime;

import java.util.Objects;

/** Strong managed handle. No numeric address, manual free, or transport authority. */
public final class OresPointer {
    // Null is the tombstone for an already-consumed pointer.
    private Object target;
    private final Object executionDomain;

    public OresPointer(Object target) {
        Object checked = Objects.requireNonNull(target, "pointer target");
        // Defense in depth: the source type checker rejects Pointer<Pointer<T>>.
        // Host-created or malformed values must not bypass that invariant and
        // create an untracked chain of owning pointer capabilities.
        if (checked instanceof OresPointer) {
            throw new IllegalArgumentException("nested Pointer<T> runtime targets are forbidden");
        }
        // Pointer<T> is for owned reference-backed objects, not scalars that
        // are Copy/interned values in the guest language. Keep host callers
        // from manufacturing Pointer<int>, Pointer<String>, or enum pointers.
        if (checked instanceof Number || checked instanceof CharSequence
                || checked instanceof Boolean || checked instanceof Character
                || checked instanceof Enum<?> || checked instanceof java.util.UUID) {
            throw new IllegalArgumentException(
                    "Pointer<T> requires reference-backed owned data, not a scalar/Copy value");
        }
        // A guest type check cannot protect callers using the public Java
        // runtime API directly. Reject known actor, synchronization, async and
        // transport capabilities without cloning/wrapping their authority.
        // This constant-size admission check does not allocate a metadata graph.
        if (checked instanceof AutoCloseable
                || checked instanceof ActorRuntime.ActorRef<?>
                || checked instanceof ActorRuntime.ActorHandle<?>
                || checked instanceof ActorRuntime.ActorGroup
                || checked instanceof ActorRuntime.ActorGroupJoinCapability
                || checked instanceof ActorRuntime.ActorControlHandle
                || checked instanceof ActorRuntime.ContinuationTarget
                || checked instanceof ActorRuntime.ActorOwnedGuestState
                || checked instanceof ActorRuntime.ActorCodeExecutor
                || checked instanceof ActorRuntime.ActorContext<?>
                || checked instanceof ActorRuntime.ActorOutputStream<?>
                || checked instanceof ActorRuntime.ControlDispatcher
                || checked instanceof ActorRuntime.ForceCancellationHook
                || checked instanceof ActorRuntime.IsolationTarget
                || checked instanceof ActorRuntime.ForceCancellationRequest
                || checked instanceof ActorEventBus
                || checked instanceof ActorEventBus.Subscription<?>
                || checked instanceof AsyncRuntime.TurnExecutor
                || checked instanceof OresFuture.Callback<?>
                || checked instanceof NativeHttp.Handler
                || checked instanceof NativeHttp.Exchange
                || checked instanceof SharedCodeImageStore.CodeImage
                || checked instanceof ActorRuntime.Shared<?>
                || checked instanceof ActorRuntime.SyncCell<?>
                || checked instanceof ChannelRuntime.Channel<?>
                || checked instanceof ChannelRuntime.SelectCase
                || checked instanceof ChannelRuntime.SelectSet
                || checked instanceof ChannelRuntime.SelectPlan
                || checked instanceof OresMutex.Lock<?>
                || checked instanceof OresMutex.Guard<?>
                || checked instanceof java.util.concurrent.Future<?>
                || checked instanceof java.util.concurrent.CompletionStage<?>) {
            throw new IllegalArgumentException(
                    "Pointer<T> cannot own runtime/resource capabilities");
        }
        this.target = checked;
        this.executionDomain = ActorRuntime.currentExecutionDomain();
    }

    public synchronized Object target() {
        // Actor identity survives carrier changes. A host thread is its own
        // domain; merely holding the Java handle grants no cross-domain loan.
        // No allocator handoff proof exists on this branch, so fail closed.
        if (executionDomain != ActorRuntime.currentExecutionDomain()) {
            throw new IllegalStateException("Pointer<T> dereference requires its owning execution domain");
        }
        if (target == null) {
            throw new IllegalStateException("Pointer<T> has already been unref'd");
        }
        return target;
    }

    /** Move out the owned pointee, atomically invalidating this handle. */
    public synchronized Object unref() {
        // target() enforces the creator's actor/thread execution domain and
        // rejects a previously consumed handle under the same monitor.
        Object live = target();
        target = null;
        return live;
    }
}
