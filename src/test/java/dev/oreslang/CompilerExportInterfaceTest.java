package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCodec.Header;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

final class CompilerExportInterfaceTest {
    private static final Header ENV = new Header("", "oreslang-test", "host", "safe");

    @Test void emittingChecksSourceButNeverExecutesGuestBodies() {
        var first = OresCompiler.emitPublicInterface("""
                pub fnc answer(int x): int { return x + 1; }
                """, ENV);
        var implementationOnly = OresCompiler.emitPublicInterface("""
                pub fnc answer(int x): int { return x + 2; }
                """, ENV);
        assertEquals(first.sha256(), implementationOnly.sha256(),
                "implementation-only changes should preserve exported interface");
        assertArrayEquals(first.bytes(), ExportInterfaceCodec.decode(first.bytes()).bytes());
    }

    @Test void changingNamedParameterInvalidatesInterface() {
        var first = OresCompiler.emitPublicInterface("""
                pub fnc answer(int x): int { return x; }
                """, ENV);
        var changed = OresCompiler.emitPublicInterface("""
                pub fnc answer(int y): int { return y; }
                """, ENV);
        assertNotEquals(first.sha256(), changed.sha256());
    }

    @Test void cannotSerializeTypeInvalidProgramAsTrustedInterface() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.emitPublicInterface("""
                        pub fnc answer(): int { return "wrong"; }
                        """, ENV));
    }

    @Test void mismatchedNamespaceIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.emitPublicInterface(
                        "pub fnc answer(): int { return 42; }",
                        new Header("another-pkg", "oreslang-test", "host", "safe")));
    }
}
