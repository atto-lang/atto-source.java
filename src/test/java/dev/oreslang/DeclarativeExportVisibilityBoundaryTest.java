package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import dev.oreslang.parser.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Package-only declarations never acquire public provider interface authority. */
final class DeclarativeExportVisibilityBoundaryTest {
    private static final ExportInterfaceCodec.Header HEADER =
            new ExportInterfaceCodec.Header("", "test", "native64", "safe");
    private static final String FUNCTIONS = """
            fnc internal_fn(): int { return 7; }
            fnc public_fn(): int { return 42; }
            """;

    @Test void trailingPackageVisibilityDoesNotEscapeIntoPublicAbi() {
        String source = FUNCTIONS + """
                export pub[pkg] fnc internal_fn;
                export pub fnc public_fn;
                """;
        var metadata = ExportInterfaceCollector.collect(Parser.parse(source), HEADER);
        List<String> exported = metadata.entries().stream()
                .filter(e -> e.kind() == ExportInterfaceCodec.Kind.FUNCTION)
                .map(ExportInterfaceCodec.Entry::name).toList();
        assertEquals(List.of("public_fn"), exported);
        assertFalse(exported.contains("internal_fn"));
    }

    @Test void duplicatePublicAndPackageExportsFailInBothOrders() {
        for (String directives : List.of(
                "export pub fnc internal_fn; export pub[pkg] fnc internal_fn;",
                "export pub[pkg] fnc internal_fn; export pub fnc internal_fn;")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(FUNCTIONS + directives));
        }
    }
}
