package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Experimental persistence port for future durable actors. No implementation is
 * installed by ActorRuntime yet. Storage drivers must provide atomic fencing,
 * inbox consumption, state CAS and outbox creation in ONE transaction.
 * Events and durable timers are deliberately outside this v0 API until their
 * typed payload and cancellation semantics are specified.
 *
 * <p>External side effects are NOT part of that transaction. Outbox dispatch
 * requires stable idempotency keys and receipt reconciliation.
 */
public interface DurableActorStore {
    record Address(String tenantId, String workflowId, String runId, String nodeId) {
        public Address {
            requireId(tenantId, "tenantId");
            requireId(workflowId, "workflowId");
            requireId(runId, "runId");
            requireId(nodeId, "nodeId");
        }
    }

    /** Bytes are encoded state, never host pointers, open handles or borrowed memory. */
    record Snapshot(int formatVersion, int stateSchemaVersion, String codeGeneration,
                    long stateVersion, byte[] encodedState) {
        public Snapshot {
            if (formatVersion != 0 || stateSchemaVersion < 0 || stateVersion < 0) {
                throw new IllegalArgumentException("invalid snapshot version");
            }
            requireId(codeGeneration, "codeGeneration");
            encodedState = Objects.requireNonNull(encodedState, "encodedState").clone();
        }

        @Override
        public byte[] encodedState() {
            return encodedState.clone();
        }
    }

    record Inbound(String deliveryId, byte[] payload) {
        public Inbound {
            requireId(deliveryId, "deliveryId");
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    record Outbound(String intentId, String idempotencyKey, byte[] payload) {
        public Outbound {
            requireId(intentId, "intentId");
            requireId(idempotencyKey, "idempotencyKey");
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /** Snapshot and pending work read under the same acquired activation fence. */
    record Activation(long epoch, Snapshot snapshot, List<Inbound> inbox,
                      List<Outbound> pendingOutbox) {
        public Activation {
            if (epoch <= 0) throw new IllegalArgumentException("invalid activation epoch");
            Objects.requireNonNull(snapshot, "snapshot");
            inbox = List.copyOf(inbox);
            pendingOutbox = List.copyOf(pendingOutbox);
        }
    }

    /**
     * A turn is already computed under an actor-wide execution lease.
     * The store must reject old epochs, incompatible expected version,
     * and previously consumed delivery IDs.
     */
    record Turn(Address address, long epoch, long expectedStateVersion,
                String consumedDeliveryId, Snapshot nextSnapshot,
                List<Outbound> outgoing) {
        public Turn {
            Objects.requireNonNull(address, "address");
            if (epoch <= 0 || expectedStateVersion < 0 || expectedStateVersion == Long.MAX_VALUE) {
                throw new IllegalArgumentException("invalid turn fencing/version");
            }
            requireId(consumedDeliveryId, "consumedDeliveryId");
            Objects.requireNonNull(nextSnapshot, "nextSnapshot");
            if (nextSnapshot.stateVersion() != expectedStateVersion + 1) {
                throw new IllegalArgumentException("nonsequential state version");
            }
            outgoing = List.copyOf(outgoing);
            var ids = new HashSet<String>();
            var keys = new HashSet<String>();
            for (Outbound intent : outgoing) {
                if (!ids.add(intent.intentId()) || !keys.add(intent.idempotencyKey())) {
                    throw new IllegalArgumentException("duplicate outgoing intent identity/key");
                }
            }
        }
    }

    /** Atomically increments a persisted activation epoch before admitting guest work. */
    Activation activate(Address address);

    /** Idempotent durable ingestion. Must work even while the actor is dormant. */
    boolean enqueue(Address address, Inbound message);

    /**
     * Actor-incarnation admission: verify the current activation fence and
     * insert the message in one storage transaction. External callers should
     * use enqueue(...) instead; they cannot claim ownership of an actor epoch.
     * Unimplemented providers MUST fail closed, never silently use enqueue.
     */
    default boolean enqueueFenced(Address address, long epoch, Inbound message) {
        throw new UnsupportedOperationException("fenced durable enqueue is unsupported");
    }

    /**
     * Linearization check for an actor-private read: validate the active epoch
     * and exact local snapshot version against the durable owner. A valid read
     * may linearize at this check, provided the actor turn holds its own state
     * lease until it copies the scalar result. Never fall back to local-only
     * reads for adapters lacking this operation.
     */
    default void requireActive(Address address, long epoch, long stateVersion) {
        throw new UnsupportedOperationException("durable activation read fence is unsupported");
    }

    /**
     * Lookup the ORIGINAL committed receipt for the exact original inbound
     * bytes, under the current epoch fence. Empty means no committed result
     * exists yet (including an admitted-but-unprocessed delivery).
     * A conflicting reused ID must throw rather than return another result.
     * Providers without atomic receipt storage MUST fail closed.
     */
    default Optional<Snapshot> committedDelivery(
            Address address, long epoch, Inbound original) {
        throw new UnsupportedOperationException(
                "transactional durable command receipts are unsupported");
    }

    /**
     * Consume delivery ID, write the next snapshot, and insert outbox intents
     * atomically. Events/timers will be added when their contract is pinned.
     * A stale activation MUST fail without mutating storage.
     */
    long commitTurn(Turn turn);

    /** Requires scheduler quiescence before releasing the activation lease. */
    void hibernate(Address address, long epoch);

    /** Durable receipt update. Must be idempotent and reject stale epochs. */
    boolean acknowledge(Address address, long epoch, String intentId, String receiptId);

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
