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

    @Test void changingOnlyProtocolKindInvalidatesExportedAbi() {
        String trait = """
                define pub trait Operation as
                    fnc apply(int value): int;
                end
                """;
        String iface = """
                define pub interface Operation as
                    fnc apply(int value): int;
                end
                """;
        var traitAbi = OresCompiler.emitPublicInterface(trait, ENV);
        var interfaceAbi = OresCompiler.emitPublicInterface(iface, ENV);
        assertNotEquals(traitAbi.sha256(), interfaceAbi.sha256(),
                "identical method shapes cannot let trait/interface changes reuse a trusted ABI");
        assertArrayEquals(traitAbi.bytes(), ExportInterfaceCodec.decode(traitAbi.bytes()).bytes());
        assertArrayEquals(interfaceAbi.bytes(),
                ExportInterfaceCodec.decode(interfaceAbi.bytes()).bytes());
    }

    @Test void mismatchedNamespaceIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.emitPublicInterface(
                        "pub fnc answer(): int { return 42; }",
                        new Header("another-pkg", "oreslang-test", "host", "safe")));
    }
}
