package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Package-private, one-shot staging gate for a future source-actor init ABI.
 *
 * <p>This class is deliberately NOT wired to source spawn yet. The
 * scalar-only init compiler candidate instead validates a separate temporary
 * state map during actor startup. This gate is a host model for a future
 * complete-state/no-early-publication protocol; it is not proof that current
 * provisional ActorRef creation satisfies that protocol.
 * It is not an actor heap, a capability validator, or a substitute for the
 * native actor runtime's physical allocation and generation admission.
 *
 * <p>Only a trusted runtime publisher may receive the staged values, and
 * that publisher must publish atomically or not at all on failure. Guest
 * code must never be handed this gate or its Java map.
 */
final class ActorInitPublicationGate {

    enum Phase { STAGING, SEALED, ISSUING, PUBLISHED, FAILED }

    private final List<String> schema;
    private final Set<String> keys;
    private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    private Phase phase = Phase.STAGING;

    ActorInitPublicationGate(List<String> orderedStateKeys) {
        Objects.requireNonNull(orderedStateKeys, "state schema");
        List<String> names = new ArrayList<>(orderedStateKeys.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String name : orderedStateKeys) {
            if (name == null || name.isBlank() || !unique.add(name)) {
                throw new IllegalArgumentException("invalid or duplicate actor state key: " + name);
            }
            names.add(name);
        }
        this.schema = List.copyOf(names);
        this.keys = Set.copyOf(unique);
    }

    synchronized Phase phase() { return phase; }

    synchronized void stage(String key, Object ownedValue) {
        require(Phase.STAGING, "stage");
        if (key == null || !keys.contains(key)) {
            throw fail("unknown actor state key '" + key + "'");
        }
        if (ownedValue == null) {
            throw fail("actor state cannot contain null: '" + key + "'; use Option<T>");
        }
        if (fields.containsKey(key)) {
            throw fail("duplicate actor init state key '" + key + "'");
        }
        fields.put(key, ownedValue);
    }

    synchronized void seal() {
        require(Phase.STAGING, "seal");
        if (fields.size() != schema.size()) {
            for (String key : schema) {
                if (!fields.containsKey(key)) {
                    throw fail("actor init did not initialize state key '" + key + "'");
                }
            }
            throw fail("actor init state does not match declared schema");
        }
        phase = Phase.SEALED;
    }

    /**
     * The trusted runtime issues a reference only after complete-state seal.
     * The publisher must provide an atomic publication boundary and must not
     * expose a partial reference before it successfully returns.
     */
    synchronized <R> R publish(Function<Map<String, Object>, R> trustedPublisher) {
        require(Phase.SEALED, "publish");
        Objects.requireNonNull(trustedPublisher, "trusted runtime publisher");
        phase = Phase.ISSUING;
        try {
            LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
            for (String key : schema) ordered.put(key, fields.get(key));
            R issued = Objects.requireNonNull(
                    trustedPublisher.apply(Collections.unmodifiableMap(ordered)),
                    "actor publisher returned null reference");
            fields.clear();
            phase = Phase.PUBLISHED;
            return issued;
        } catch (RuntimeException | Error failure) {
            fields.clear();
            phase = Phase.FAILED;
            throw failure;
        }
    }

    synchronized void abort() {
        // A trusted publisher is invoked synchronously while ISSUING.
        // Reentrant abort must not retroactively invalidate its in-flight
        // commit and then let publish() overwrite FAILED with PUBLISHED.
        if (phase == Phase.ISSUING) {
            throw new IllegalStateException(
                    "cannot abort actor init while publication is issuing");
        }
        if (phase == Phase.PUBLISHED) {
            throw new IllegalStateException(
                    "published actor must be stopped, not init-aborted");
        }
        fields.clear();
        phase = Phase.FAILED;
    }

    private void require(Phase expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException(
                    "cannot " + operation + " actor init in phase " + phase);
        }
    }

    private IllegalStateException fail(String message) {
        fields.clear();
        phase = Phase.FAILED;
        return new IllegalStateException(message);
    }
}
