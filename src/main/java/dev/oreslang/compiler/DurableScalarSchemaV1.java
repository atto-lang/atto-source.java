package dev.oreslang.compiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Statically generated schema descriptor and canonical i64 command codec for
 * the verified PersistentScalarMirV1 subset. No reflection, dynamic class
 * loading, runtime code generation or implicit schema coercion is permitted.
 *
 * <p>Stable ID is derived from the nominal actor type and message/state slot,
 * NOT its code-generation hash or source filename. Renames need explicit
 * migration approval: this v1 compiler profile does not invent migrations.
 */
public final class DurableScalarSchemaV1 {
    private static final int MAGIC = 0x4154544f; // ATTO
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = Integer.BYTES * 2 + 32;
    private static final int TOTAL_SIZE = HEADER_SIZE + Long.BYTES;

    private DurableScalarSchemaV1() { }

    public record Descriptor(String schemaId, int schemaVersion, int fieldId,
                             String fieldName, String fieldType,
                             String fingerprint) {
        public Descriptor {
            requireName(schemaId);
            requireName(fieldName);
            if (schemaVersion != VERSION || fieldId < 1 || !"i64".equals(fieldType))
                throw new IllegalArgumentException("unsupported durable scalar schema");
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid durable schema fingerprint");
        }
    }

    /** One stable field ID for the only compiler-admitted scalar state slot. */
    public static Descriptor forState(String actorName, String fieldName) {
        requireName(actorName);
        requireName(fieldName);
        return descriptor("atto.actor." + actorName + ".state", fieldName);
    }

    /** The verified increment command has one i64 parameter tagged #1. */
    public static Descriptor forIncrement(String actorName) {
        requireName(actorName);
        return descriptor("atto.actor." + actorName + ".increment", "amount");
    }

    private static Descriptor descriptor(String id, String fieldName) {
        // The same bytes across Java, Native Image, and all future backends.
        String canonical = "atto-durable-scalar-descriptor-v1\n"
                + id + "\n1\n1\n" + fieldName + "\ni64\n";
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
        return new Descriptor(id, VERSION, 1, fieldName, "i64", fingerprint);
    }

    /** Versioned command body: big-endian ATTO | v1 | SHA256(schema) | i64. */
    public static byte[] encode(Descriptor descriptor, long value) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.schemaVersion() != VERSION)
            throw new IllegalArgumentException("unsupported command schema version");
        return ByteBuffer.allocate(TOTAL_SIZE).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .putInt(descriptor.schemaVersion())
                .put(HexFormat.of().parseHex(descriptor.fingerprint()))
                .putLong(value).array();
    }

    public static long decode(Descriptor expected, byte[] originalWire) {
        Objects.requireNonNull(expected, "descriptor");
        if (originalWire == null || originalWire.length != TOTAL_SIZE)
            throw new IllegalStateException("DURABLE_MESSAGE_WIRE_LENGTH");
        ByteBuffer data = ByteBuffer.wrap(originalWire).order(ByteOrder.BIG_ENDIAN);
        if (data.getInt() != MAGIC || data.getInt() != expected.schemaVersion())
            throw new IllegalStateException("DURABLE_MESSAGE_SCHEMA_VERSION");
        byte[] fingerprint = new byte[32];
        data.get(fingerprint);
        if (!MessageDigest.isEqual(fingerprint,
                HexFormat.of().parseHex(expected.fingerprint())))
            throw new IllegalStateException("DURABLE_MESSAGE_SCHEMA_FINGERPRINT");
        return data.getLong();
    }

    private static void requireName(String s) {
        if (s == null || !s.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("durable schema name must be a bounded identifier");
    }
}
