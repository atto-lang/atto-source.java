package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import dev.oreslang.parser.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verify that file exports affect the *actual* public ABI collector. */
final class DeclarativeExportInterfaceTest {
    private static final ExportInterfaceCodec.Header HEADER =
            new ExportInterfaceCodec.Header("", "test", "native64", "safe");

    private static final String BODY = """
            fnc public_fn(): int { return 42; }
            fnc package_fn(): int { return 7; }
            fnc private_fn(): int { return 3; }
            """;

    @Test void forwardAndTrailingExportsProduceTheSamePublicAbi() {
        String directives = """
                export pub fnc public_fn;
                export pub[pkg] fnc package_fn;
                """;
        var top = ExportInterfaceCollector.collect(Parser.parse(directives + BODY), HEADER);
        var bottom = ExportInterfaceCollector.collect(Parser.parse(BODY + directives), HEADER);
        assertArrayEquals(top.bytes(), bottom.bytes());
        assertEquals(top.sha256(), bottom.sha256());
        List<String> names = top.entries().stream()
                .filter(e -> e.kind() == ExportInterfaceCodec.Kind.FUNCTION)
                .map(ExportInterfaceCodec.Entry::name).toList();
        assertEquals(List.of("public_fn"), names);
    }

    @Test void packageOnlyExportDoesNotLeakToUnscopedPublicInterface() {
        var exported = ExportInterfaceCollector.collect(Parser.parse("""
                export pub[pkg] fnc package_fn;
                """ + BODY), HEADER);
        assertFalse(exported.entries().stream().anyMatch(e -> e.name().equals("package_fn")));
        assertFalse(exported.entries().stream().anyMatch(e -> e.name().equals("private_fn")));
    }
}
