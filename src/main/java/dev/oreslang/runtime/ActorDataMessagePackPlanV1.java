package dev.oreslang.runtime;

import dev.oreslang.types.PortableCodecRegistry;
import dev.oreslang.types.PortableCodecRegistry.Descriptor;
import dev.oreslang.types.PortableCodecRegistry.Format;
import dev.oreslang.types.PortableCodecRegistry.Purpose;
import java.util.Objects;

/**
 * Immutable AOT-compatible plan from an already validated compiler descriptor.
 * No reflection, parsing descriptor text, wire-selected schema or fallback.
 * This restricted host plan is not yet a compiler-generated actor binding.
 * List values are detached decoder-owned Java lists, not guest collection ABI.
 */
final class ActorDataMessagePackPlanV1 {
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final long MAX_WORK = 4L * 1024 * 1024;
    private static final int MAX_WIRE = 1024 * 1024;
    private final Descriptor descriptor;
    private final MessagePackWireV2.Shape wireShape;

    ActorDataMessagePackPlanV1(Descriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor);
        if (descriptor.purpose() != Purpose.ACTOR_DATA
                || descriptor.format() != Format.MESSAGEPACK_V2
                || descriptor.version() != 1 || descriptor.schemaId().length() > 256)
            throw new IllegalArgumentException("unsupported actor DATA descriptor");
        wireShape = map(descriptor.shape(), 0);
    }

    private static MessagePackWireV2.Shape map(PortableCodecRegistry.Shape shape, int depth) {
        if (depth > 32) throw new IllegalArgumentException("actor codec shape depth");
        if (shape instanceof PortableCodecRegistry.Scalar scalar) {
            return switch (scalar.kind()) {
                case BOOL -> MessagePackWireV2.Shape.bool();
                case I64 -> MessagePackWireV2.Shape.i64();
                case F64 -> MessagePackWireV2.Shape.f64();
                case UTF8 -> MessagePackWireV2.Shape.string();
                case BYTES -> throw new IllegalArgumentException("unsupported bytes representation");
            };
        }
        if (shape instanceof PortableCodecRegistry.Sequence sequence
                && sequence.kind() == PortableCodecRegistry.SequenceKind.LIST) {
            return MessagePackWireV2.Shape.list(map(sequence.element(), depth + 1));
        }
        // Fixed arrays/lists, records, tuples and ADTs require a verified
        // guest representation and bounded compiler mapping. Never coerce.
        throw new IllegalArgumentException("unsupported actor codec shape");
    }

    // Conservative logical budget: every materialized value consumes at
    // least one wire byte. Reserve 128/node plus 16/wire byte and fixed
    // overhead before decoding; covers this restricted logical profile, not
    // a physical JVM allocation guarantee. Work includes a fixed allowance
    // for the bounded descriptor traversal.
    static long logicalBytes(int length) {
        requireLength(length);
        return 256L + 144L * length;
    }

    static long logicalWork(int length) {
        requireLength(length);
        return 128L + 4L * length;
    }

    private static void requireLength(int length) {
        if (length <= 0 || length > MAX_WIRE)
            throw new IllegalArgumentException("wire length denied");
    }

    Object decodeOwned(Format format, String schemaId, int version, String fingerprint,
                       byte[] ownedWire, long bytes, long work) {
        if (format != descriptor.format() || version != descriptor.version()
                || !descriptor.schemaId().equals(schemaId)
                || !descriptor.fingerprintHex().equals(fingerprint))
            throw new IllegalArgumentException("actor codec identity mismatch");
        Objects.requireNonNull(ownedWire);
        if (bytes > MAX_BYTES || work > MAX_WORK
                || logicalBytes(ownedWire.length) > bytes || logicalWork(ownedWire.length) > work)
            throw new IllegalArgumentException("actor codec budget denied");
        return MessagePackWireV2.decode(descriptor.schemaId(), wireShape, ownedWire);
    }
}
