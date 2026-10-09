package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ActorGroup-scoped, bounded address registry built on the ordinary actor
 * mailbox transport. It provides OTP pg-style named memberships and Vert.x-like
 * single-consumer send / broadcast publish, without creating a new dispatcher.
 *
 * <p>This is runtime plumbing, NOT a host-interop escape hatch for Oreslang.
 * Guest-facing builtins must obtain the current actor's own ActorRef from the
 * trusted compiler/runtime, never from user-supplied host objects.</p>
 */
public final class ActorAddressBus {
    private static final int MAX_ADDRESSES_PER_ACTOR = 64;
    private static final int MAX_NAME_LENGTH = 128;

    public record BroadcastResult(int recipients, int admitted, int rejected) {
        public BroadcastResult {
            if (recipients < 0 || admitted < 0 || rejected < 0
                    || admitted + rejected != recipients) {
                throw new IllegalArgumentException("invalid broadcast accounting");
            }
        }
    }

    private static final class Address {
        private final Class<?> messageType;
        private final Map<ActorRuntime.ActorId, ActorRuntime.ActorRef<?>> subscribers =
                new LinkedHashMap<>();
        private long next;

        private Address(Class<?> messageType) {
            this.messageType = messageType;
        }
    }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorGroup group;
    /** All registry structures are guarded by lock, never held across mailbox admission. */
    private final Object lock = new Object();
    private final Map<String, Address> addresses = new HashMap<>();
    private final Map<ActorRuntime.ActorId, Integer> actorAddressCounts = new HashMap<>();
    private boolean closed;

    ActorAddressBus(ActorRuntime runtime, ActorRuntime.ActorGroup group) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.group = Objects.requireNonNull(group, "group");
    }

    /**
     * Define a typed address. Only the group's creator or the host/supervisor
     * may define an address. Re-definition with the same type is idempotent.
     */
    public void defineAddress(String name, Class<?> messageType) {
        String address = validateName(name);
        Objects.requireNonNull(messageType, "messageType");
        if (messageType.isPrimitive() || messageType == Void.class
                || messageType.isArray()) {
            throw new IllegalArgumentException("address payload type must be a reference data type");
        }
        group.requireManager("define a mailbox address");
        synchronized (lock) {
            requireOpen();
            Address old = addresses.get(address);
            if (old != null) {
                if (old.messageType != messageType) {
                    throw new IllegalStateException("mailbox address type mismatch: " + address);
                }
                return;
            }
            if (addresses.size() >= group.eventTopicLimit()) {
                throw new IllegalStateException("ActorGroup mailbox address quota exceeded");
            }
            addresses.put(address, new Address(messageType));
        }
    }

    /**
     * Join an address using only the executing actor's own reference.
     * The actor must already belong to the owning ActorGroup.
     */
    public void joinCurrent(String name, ActorRuntime.ActorRef<?> self) {
        String address = validateName(name);
        Objects.requireNonNull(self, "self");
        ActorRuntime.ActorId caller = group.requireCurrentMember("join a mailbox address");
        if (!caller.equals(self.id()) || !group.contains(self)) {
            throw new SecurityException("actors may register only their own same-runtime ActorRef");
        }
        synchronized (lock) {
            requireOpen();
            Address route = requireAddress(address);
            if (!group.isMember(caller) || !self.isAlive()) {
                throw new IllegalStateException("address subscriber is no longer alive or in the group");
            }
            if (route.subscribers.containsKey(caller)) return;
            int current = actorAddressCounts.getOrDefault(caller, 0);
            if (current >= MAX_ADDRESSES_PER_ACTOR) {
                throw new IllegalStateException("actor mailbox address membership quota exceeded");
            }
            route.subscribers.put(caller, self);
            actorAddressCounts.put(caller, current + 1);
        }
    }

    public void leaveCurrent(String name) {
        String address = validateName(name);
        ActorRuntime.ActorId caller = group.requireCurrentMember("leave a mailbox address");
        synchronized (lock) {
            requireOpen();
            Address route = requireAddress(address);
            if (route.subscribers.remove(caller) != null) decrementMembership(caller);
        }
    }

    /** Read-only IDs: addresses never confer ActorRef or supervisor authority. */
    public List<ActorRuntime.ActorId> members(String name) {
        String address = validateName(name);
        group.currentMemberOrSystem("inspect mailbox address membership");
        synchronized (lock) {
            requireOpen();
            return List.copyOf(requireAddress(address).subscribers.keySet());
        }
    }

    /**
     * Route to exactly one current subscriber, using round-robin selection.
     * Admission is delegated to ActorRef.send and may reject on backpressure,
     * isolation or cancellation. No silent retries or fallback recipients.
     */
    public <M> Optional<ActorRuntime.ActorId> send(String name, M message) {
        String address = validateName(name);
        group.currentMemberOrSystem("send to a mailbox address");
        ActorRuntime.ActorRef<?> target;
        synchronized (lock) {
            requireOpen();
            Address route = requireAddress(address);
            requirePayload(route, message);
            if (route.subscribers.isEmpty()) return Optional.empty();
            List<ActorRuntime.ActorRef<?>> refs = new ArrayList<>(route.subscribers.values());
            target = refs.get((int) Math.floorMod(route.next++, (long) refs.size()));
        }
        // Never hold the registry monitor across transport admission/freezing.
        // Membership may change after selection. Reject stale routes rather than
        // delivering to an actor that has left this group.
        synchronized (lock) {
            requireOpen();
            if (requireAddress(address).subscribers.get(target.id()) != target) {
                throw new IllegalStateException("mailbox address membership changed");
            }
        }
        dispatch(target, message);
        return Optional.of(target.id());
    }

    /**
     * Best-effort broadcast to a stable membership snapshot. A rejected
     * recipient is counted, never silently buffered; other recipients still
     * get an admission attempt. This acknowledges mailbox admission, not
     * processing, persistence, ordering across publishers, or exactly-once.
     */
    public <M> BroadcastResult publish(String name, M message) {
        String address = validateName(name);
        group.currentMemberOrSystem("publish to a mailbox address");
        List<ActorRuntime.ActorRef<?>> targets;
        synchronized (lock) {
            requireOpen();
            Address route = requireAddress(address);
            requirePayload(route, message);
            targets = List.copyOf(route.subscribers.values());
        }
        int admitted = 0;
        int rejected = 0;
        for (ActorRuntime.ActorRef<?> target : targets) {
            if (group.closed()) {
                // Count the remaining snapshot entries as rejected.
                rejected = targets.size() - admitted;
                break;
            }
            try {
                dispatch(target, message);
                admitted++;
            } catch (IllegalStateException rejection) {
                // Includes terminated target and bounded mailbox admission.
                rejected++;
            }
        }
        return new BroadcastResult(targets.size(), admitted, rejected);
    }

    private static void requirePayload(Address route, Object message) {
        if (message == null || !route.messageType.isInstance(message)) {
            throw new IllegalArgumentException(
                    "mailbox address payload must be " + route.messageType.getTypeName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void dispatch(ActorRuntime.ActorRef<?> target, Object message) {
        ((ActorRuntime.ActorRef<Object>) target).send(message);
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "address");
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || !name.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException("invalid mailbox address: " + name);
        }
        return name;
    }

    private Address requireAddress(String name) {
        Address route = addresses.get(name);
        if (route == null) {
            throw new IllegalArgumentException("undefined ActorGroup mailbox address: " + name);
        }
        return route;
    }

    private void requireOpen() {
        if (closed || group.closed()) {
            throw new IllegalStateException("ActorGroup mailbox routing is closed");
        }
    }

    private void decrementMembership(ActorRuntime.ActorId actor) {
        int old = actorAddressCounts.getOrDefault(actor, 0);
        if (old <= 0) throw new IllegalStateException("address membership accounting underflow");
        if (old == 1) actorAddressCounts.remove(actor);
        else actorAddressCounts.put(actor, old - 1);
    }

    /** Cleanup runs before actor termination becomes observable to hosts. */
    void removeActor(ActorRuntime.ActorId actor) {
        synchronized (lock) {
            for (Address route : addresses.values()) {
                if (route.subscribers.remove(actor) != null) {
                    decrementMembership(actor);
                }
            }
        }
    }

    void closeFromGroup() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            addresses.clear();
            actorAddressCounts.clear();
        }
    }
}
