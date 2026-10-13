package dev.oreslang.types;

import java.util.Objects;

/**
 * Closed, versioned host transport for Result&lt;void, ArrayError&gt;.
 * This is not the generic Oreslang guest Result representation.
 * The native LLVM peer accepts only 01 00 00, 01 01 01, 01 01 02.
 */
public final class NativeArrayResultWire {
    public static final int VERSION = 1;
    public static final int SIZE = 3;

    public enum Variant { OK, ERR }
    public enum ArrayError { LIMIT, NO_MEMORY }

    public record Result(Variant variant, ArrayError error) {
        public Result {
            Objects.requireNonNull(variant, "variant");
            if (variant == Variant.ERR) {
                Objects.requireNonNull(error, "error");
            } else if (error != null) {
                throw new IllegalArgumentException("Ok cannot contain an error payload");
            }
        }

        public static Result ok() {
            return new Result(Variant.OK, null);
        }

        public static Result err(ArrayError error) {
            return new Result(Variant.ERR, error);
        }
    }

    private NativeArrayResultWire() {}

    public static byte[] encode(Result result) {
        Objects.requireNonNull(result, "result");
        if (result.variant() == Variant.OK) {
            return new byte[] { 1, 0, 0 };
        }
        return switch (result.error()) {
            case LIMIT -> new byte[] { 1, 1, 1 };
            case NO_MEMORY -> new byte[] { 1, 1, 2 };
        };
    }

    public static Result decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != SIZE || Byte.toUnsignedInt(bytes[0]) != VERSION) {
            throw new IllegalArgumentException("invalid Result<void, ArrayError> wire version or size");
        }
        final int variant = Byte.toUnsignedInt(bytes[1]);
        final int error = Byte.toUnsignedInt(bytes[2]);
        if (variant == 0 && error == 0) {
            return Result.ok();
        }
        if (variant == 1 && error == 1) {
            return Result.err(ArrayError.LIMIT);
        }
        if (variant == 1 && error == 2) {
            return Result.err(ArrayError.NO_MEMORY);
        }
        throw new IllegalArgumentException("invalid or noncanonical Result<void, ArrayError> payload");
    }
}
