package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit lossless compatibility boundary between the older closed-record
 * nominal Message enum descriptor (64-bit wire tags) and the staged v2 enum
 * syntax AST (positive monotonic 32-bit wire tags).
 *
 * <p>This is an isolated schema migration helper. It cannot authenticate
 * defining-source identities, imports, ownership, guest values, or executable
 * method bindings. Callers must retain TypeChecker's enum fail-closed gate.
 */
public final class EnumV2Migration {
    private EnumV2Migration() { }

    public static final int MAX_CASES = 4096;

    /** Mimics the older #907 declaration shape without depending on old AST. */
    public record LegacyVariant(String name, Ast.TypeRef closedRecordPayload, long wireTag) {
        public LegacyVariant {
            if (name == null || name.isBlank() || wireTag <= 0L) {
                throw new IllegalArgumentException("invalid legacy enum case or tag");
            }
            validateLegacyRecordPayload(closedRecordPayload);
        }
    }

    /** This record is a compatibility *descriptor*, never linked provenance. */
    public record LegacyEnum(
            String name, Ast.Visibility visibility, List<LegacyVariant> variants) {
        public LegacyEnum {
            if (name == null || name.isBlank() || visibility == null
                    || variants == null || variants.isEmpty()
                    || variants.size() > MAX_CASES) {
                throw new IllegalArgumentException("invalid legacy nominal enum descriptor");
            }
            variants = List.copyOf(variants);
            Set<String> names = new HashSet<>();
            Set<Long> tags = new HashSet<>();
            for (LegacyVariant variant : variants) {
                if (!names.add(variant.name()) || !tags.add(variant.wireTag())) {
                    throw new IllegalArgumentException("duplicate legacy enum name or tag");
                }
            }
        }
    }

    /**
     * Lossless only for ordered, positive INT32 tags. Reject rather than
     * truncate or reorder older 64-bit tags; reject any payload transformation
     * that could change ownership, memory layout or serialized bytes.
     */
    public static Ast.EnumDecl toV2(LegacyEnum old) {
        Objects.requireNonNull(old, "old");
        List<Ast.EnumCase> converted = new ArrayList<>(old.variants().size());
        long previous = 0L;
        for (LegacyVariant variant : old.variants()) {
            long tag = variant.wireTag();
            if (tag <= previous || tag > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "legacy enum wire tag exceeds ordered positive int32 v2 domain");
            }
            previous = tag;
            converted.add(new Ast.EnumCase(
                    variant.name(), variant.closedRecordPayload(), Math.toIntExact(tag)));
        }
        return new Ast.EnumDecl(old.name(), old.visibility(), converted);
    }

    /**
     * Reverse only the record-only subset understood by old Message enums;
     * scalar or generic v2 payloads cannot be silently rewritten as records.
     */
    public static LegacyEnum toLegacyRecordSubset(Ast.EnumDecl modern) {
        Objects.requireNonNull(modern, "modern");
        List<LegacyVariant> converted = new ArrayList<>(modern.cases().size());
        for (Ast.EnumCase variant : modern.cases()) {
            validateLegacyRecordPayload(variant.payload());
            converted.add(new LegacyVariant(
                    variant.name(), variant.payload(), (long) variant.tag()));
        }
        return new LegacyEnum(modern.name(), modern.visibility(), converted);
    }

    private static void validateLegacyRecordPayload(Ast.TypeRef payload) {
        if (payload == null) return;
        // A readonly record cannot be rewritten to a legacy mutable record
        // without changing the ownership contract, even when its fields match.
        if (!payload.isRecordType() || payload.isReadonlyRecordType()) {
            throw new IllegalArgumentException(
                    "legacy enum payload requires a mutable closed record type");
        }
        try {
            payload.recordMembers();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "malformed record payload cannot be migrated", malformed);
        }
    }

    /** Compare schemas without relying on runtime tag inspection. */
    public static boolean losslesslyEquivalent(LegacyEnum old, Ast.EnumDecl modern) {
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(modern, "modern");
        try {
            return toV2(old).equals(modern)
                    && toLegacyRecordSubset(modern).equals(old);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
