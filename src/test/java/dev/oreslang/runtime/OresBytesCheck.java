package dev.oreslang.runtime;

import java.util.Arrays;

/** Standalone compact-bytes immutability and 1 MiB capacity contract. */
public final class OresBytesCheck {
    private OresBytesCheck() { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] unused) {
        byte[] source = new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES];
        source[0] = (byte) 255;
        source[source.length - 1] = (byte) 254;

        OresBytes bytes = OresBytes.copyOf(source);
        source[0] = 0;

        check(bytes.size() == UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                "full unary wire budget must fit compact bytes");
        check(bytes.unsignedAt(0) == 255
                        && bytes.unsignedAt(bytes.size() - 1) == 254,
                "public construction must defensively copy and expose unsigned access");

        byte[] exported = bytes.toByteArray();
        exported[0] = 1;
        check(bytes.unsignedAt(0) == 255,
                "exported arrays must not mutate compact bytes");

        OresBytes same = OresBytes.copyOf(bytes.toByteArray());
        check(bytes.equals(same) && bytes.hashCode() == same.hashCode(),
                "equal byte sequences require value equality and matching hash codes");
        check(!bytes.equals(OresBytes.copyOf(Arrays.copyOf(source, 7))),
                "different byte sequences must not compare equal");
        check(bytes.toString().equals(
                        "OresBytes[length=" + UnaryTransportFrame.MAX_PAYLOAD_BYTES + "]"),
                "diagnostic rendering must reveal length, not payload contents");

        try {
            bytes.byteAt(bytes.size());
            throw new AssertionError("out-of-range byte access accepted");
        } catch (IndexOutOfBoundsException expected) { }

        OresBytes constructed = OresBytes.fromUnsignedIntegers(
                java.util.List.of((byte) 0xff, 0, 127, 255L));
        check(constructed.size() == 4
                        && constructed.unsignedAt(0) == 255
                        && constructed.unsignedAt(1) == 0
                        && constructed.unsignedAt(2) == 127
                        && constructed.unsignedAt(3) == 255,
                "checked integer construction must preserve unsigned octets");

        for (Object invalid : java.util.List.of(-1, 256, 1.5, "1")) {
            try {
                OresBytes.fromUnsignedIntegers(java.util.List.of(invalid));
                throw new AssertionError(
                        "invalid source Bytes element accepted: " + invalid);
            } catch (IllegalArgumentException expected) { }
        }

        System.out.println("PASS: compact immutable OresBytes full unary wire budget");
    }
}
