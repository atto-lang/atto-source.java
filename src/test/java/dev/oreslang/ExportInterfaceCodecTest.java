package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCodec.Entry;
import dev.oreslang.compiler.ExportInterfaceCodec.Header;
import dev.oreslang.compiler.ExportInterfaceCodec.Kind;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ExportInterfaceCodecTest {
    private static final Header HEADER = new Header("pkg", "compiler-1", "x86_64-linux", "safe");
    private static final Entry F = new Entry("mod", Kind.FUNCTION, "do_work", "(int value):void");
    private static final Entry C = new Entry("mod", Kind.CLASS, "Widget", "implements Serializable");

    @Test void permutationIndependentAndRoundTrips() {
        var a = ExportInterfaceCodec.encode(HEADER, List.of(F, C));
        var b = ExportInterfaceCodec.encode(HEADER, List.of(C, F));
        assertArrayEquals(a.bytes(), b.bytes());
        assertEquals(a.sha256(), b.sha256());
        assertEquals(a.sha256(), ExportInterfaceCodec.decode(a.bytes()).sha256());
        assertEquals(List.of(F, C), a.entries());
    }

    @Test void compilationEnvironmentAndNamedSignatureChangeHash() {
        var base = ExportInterfaceCodec.encode(HEADER, List.of(F));
        var target = ExportInterfaceCodec.encode(
                new Header("pkg", "compiler-1", "aarch64-linux", "safe"), List.of(F));
        var named = ExportInterfaceCodec.encode(HEADER,
                List.of(new Entry("mod", Kind.FUNCTION, "do_work", "(int renamed):void")));
        assertNotEquals(base.sha256(), target.sha256());
        assertNotEquals(base.sha256(), named.sha256());
    }

    @Test void encodingAndResultAreDefensivelyImmutable() {
        var encoded = ExportInterfaceCodec.encode(HEADER, List.of(F));
        byte[] returned = encoded.bytes();
        returned[0] = 0;
        assertEquals(encoded.sha256(), ExportInterfaceCodec.decode(encoded.bytes()).sha256());
        assertThrows(UnsupportedOperationException.class, () -> encoded.entries().clear());
    }

    @Test void rejectsInvalidAndNoncanonicalWireData() {
        var valid = ExportInterfaceCodec.encode(HEADER, List.of(C, F)).bytes();
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.decode(new byte[1]));
        byte[] wrongVersion = valid.clone();
        wrongVersion[6] = 2;
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.decode(wrongVersion));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.decode(trailing));
        byte[] cut = Arrays.copyOf(valid, valid.length - 1);
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.decode(cut));
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.encode(HEADER, List.of(F, F)));
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.encode(
                HEADER, List.of(new Entry("mod", Kind.TYPE, "Broken", "\ud800"))));
    }

    @Test void rejectsMalformedUtf8AndForgedStringLength() {
        byte[] valid = ExportInterfaceCodec.encode(HEADER, List.of(F)).bytes();
        byte[] badUtf8 = valid.clone();
        // Header namespace starts immediately after magic and its four-byte length.
        badUtf8[11] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.decode(badUtf8));
        byte[] inflated = valid.clone();
        ByteBuffer.wrap(inflated, 7, 4).putInt(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ExportInterfaceCodec.decode(inflated));
    }
}
