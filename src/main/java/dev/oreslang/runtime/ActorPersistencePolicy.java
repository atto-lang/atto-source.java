package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Stage-one, host-side policy metadata for persistence of real actor state.
 * This does not implement source syntax, authorization, storage, or sealed StateAccess;
 * the Oreslang compiler must enforce critical-read/critical-write and effect safety.
 */
public record ActorPersistencePolicy(Mode mode, Mode underlying, Ack ack) {
    public enum Mode { NONE, SNAPSHOT, EVENT_SOURCED, BATCHED_SIDECAR }
    public enum Ack { ADMITTED, COMMITTED }
    public enum StorageKind { NONE, SNAPSHOT, JOURNAL }

    public ActorPersistencePolicy {
        Objects.requireNonNull(mode, "mode");
        switch (mode) {
            case NONE -> {
                if (underlying != null || ack != null) throw new IllegalArgumentException("NONE_HAS_NO_STORAGE");
            }
            case SNAPSHOT, EVENT_SOURCED -> {
                if (underlying != null || (ack != null && ack != Ack.COMMITTED))
                    throw new IllegalArgumentException("INVALID_DURABLE_POLICY");
                ack = Ack.COMMITTED;
            }
            case BATCHED_SIDECAR -> {
                if (underlying != Mode.SNAPSHOT && underlying != Mode.EVENT_SOURCED)
                    throw new IllegalArgumentException("BATCH_REQUIRES_UNDERLYING");
                if (ack == null) ack = Ack.COMMITTED;
            }
        }
    }

    public static ActorPersistencePolicy of(Mode mode) {
        return new ActorPersistencePolicy(mode, null, null);
    }

    /** Symbols are nominal, never strings that merely resemble symbols. */
    public static Mode parseModeSymbol(String symbol) {
        return switch (Objects.requireNonNull(symbol, "symbol")) {
            case "#none" -> Mode.NONE;
            case "#snapshot" -> Mode.SNAPSHOT;
            case "#event_sourced" -> Mode.EVENT_SOURCED;
            case "#batched_sidecar" -> Mode.BATCHED_SIDECAR;
            default -> throw new IllegalArgumentException("UNKNOWN_PERSISTENCE_SYMBOL");
        };
    }

    public static Ack parseAckSymbol(String symbol) {
        return switch (Objects.requireNonNull(symbol, "symbol")) {
            case "#admitted" -> Ack.ADMITTED;
            case "#committed" -> Ack.COMMITTED;
            default -> throw new IllegalArgumentException("UNKNOWN_ACK_SYMBOL");
        };
    }

    /** Adapter for already-typechecked metadata, not a substitute for source symbol typing. */
    public static ActorPersistencePolicy fromSymbols(String mode, String underlying, String ack) {
        return new ActorPersistencePolicy(parseModeSymbol(mode),
                underlying == null ? null : parseModeSymbol(underlying),
                ack == null ? null : parseAckSymbol(ack));
    }

    public StorageKind storageKind() {
        return switch (mode == Mode.BATCHED_SIDECAR ? underlying : mode) {
            case NONE -> StorageKind.NONE;
            case SNAPSHOT -> StorageKind.SNAPSHOT;
            case EVENT_SOURCED -> StorageKind.JOURNAL;
            case BATCHED_SIDECAR -> throw new IllegalStateException("UNRESOLVED_BATCH_STORAGE");
        };
    }

    public boolean isBatched() { return mode == Mode.BATCHED_SIDECAR; }
    public boolean isDurable() { return mode != Mode.NONE; }
    public boolean admittedIsDurable() { return false; }
}
