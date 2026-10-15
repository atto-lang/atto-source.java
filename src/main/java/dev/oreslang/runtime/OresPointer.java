package dev.oreslang.runtime;

import java.util.Objects;

/** Strong managed handle. No numeric address, manual free, or transport authority. */
public final class OresPointer {
    private final Object target;

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
                || checked instanceof Enum<?> || checked instanceof java.util.UUID
                || checked instanceof OresSymbol) {
            throw new IllegalArgumentException(
                    "Pointer<T> requires reference-backed owned data, not a scalar/Copy value");
        }
        // A guest type check cannot protect callers using the public Java
        // runtime API directly. Reject known actor, synchronization, async and
        // transport capabilities without cloning/wrapping their authority.
        // This constant-size admission check does not allocate a metadata graph.
        if (checked instanceof ActorRuntime.ActorRef<?>
                || checked instanceof ActorRuntime.ActorHandle<?>
                || checked instanceof ActorRuntime.ActorGroup
                || checked instanceof ActorRuntime.ActorGroupJoinCapability
                || checked instanceof ActorRuntime.Shared<?>
                || checked instanceof ActorRuntime.SyncCell<?>
                || checked instanceof ChannelRuntime.Channel<?>
                || checked instanceof ChannelRuntime.SelectCase
                || checked instanceof ChannelRuntime.SelectSet
                || checked instanceof OresMutex.Lock<?>
                || checked instanceof OresMutex.Guard<?>
                || checked instanceof java.util.concurrent.Future<?>
                || checked instanceof java.util.concurrent.CompletionStage<?>) {
            throw new IllegalArgumentException(
                    "Pointer<T> cannot own actor, channel, mutex, shared-memory or future capabilities");
        }
        this.target = checked;
    }

    public Object target() { return target; }
}
