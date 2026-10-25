package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First native conformance step for the shared ores-clients-core header.
 * This asserts native parser/type/ownership admission of declaration syntax.
 * It deliberately does NOT claim client implementation, TJSV wire parity, or LLVM support.
 */
final class GeneratedClientContractCompilerTest {
    private static final Path GENERATED =
            Path.of("contracts/v1/fixtures/clients-core-client-contract.ores");

    @Test
    void nativeCompilerAdmitsRealClientContractHeaderSyntax() throws Exception {
        String code = Files.readString(GENERATED);
        assertTrue(code.contains("pub define contract ClientContract as"));
        assertTrue(code.contains("fnc list_items(String page_size) => String;"));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
    }

    @Test
    void malformedGeneratedContractsFailClosedBeforeRuntimeAdmission() throws Exception {
        String original = Files.readString(GENERATED);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(original.replace(
                        "fnc list_items(String page_size)",
                        "fnc list_items(String page_size, String page_size)")));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(original.replace(
                        "fnc list_items", "fnc suspends")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(original.replace(
                        "fnc list_items(String page_size) => String;",
                        "fnc list_items(String page_size) => ;")));
    }

    @Test
    void canonicalOresInterfacesRouteContractCompilesAsDeclaration() throws Exception {
        String contract = Files.readString(Path.of(
                "contracts/v1/fixtures/ores-interfaces-route-contract.ores"));
        var parsed = OresCompiler.parseAndTypeCheck(contract);
        assertEquals(1, parsed.modules().size());
        assertEquals(Parser.ROOT_MODULE, parsed.modules().get(0).name());
        assertEquals(1, parsed.modules().get(0).declarations().size());
        assertInstanceOf(dev.oreslang.ast.Ast.InterfaceDecl.class,
                parsed.modules().get(0).declarations().get(0));
    }

    @Test
    void generatedContractIsNotMistakenForAnExecutableClientOrRuntime() throws Exception {
        var checked = OresCompiler.parseAndTypeCheck(Files.readString(GENERATED));
        assertEquals(1, checked.modules().size());
        assertEquals(Parser.ROOT_MODULE, checked.modules().get(0).name());
        assertFalse(checked.modules().get(0).declarations().isEmpty());
        // The only represented artifact is a top-level contract declaration.
        assertTrue(checked.modules().get(0).declarations().stream()
                .allMatch(decl -> decl instanceof dev.oreslang.ast.Ast.InterfaceDecl));
    }
    @Test
    void duplicateInterfaceAndContractParameterNamesFailClosedInBothSpellingOrders() {
        for (String kind : new String[] { "interface", "contract" }) {
            for (String arguments : new String[] {
                    "String value, String value",
                    "value: String, value: String",
                    "structural String value, String value"
            }) {
                String source = "pub define " + kind
                        + " Endpoint as fnc send(" + arguments + ") => String; end";
                assertThrows(IllegalArgumentException.class,
                        () -> OresCompiler.parseAndTypeCheck(source), source);
            }
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                    "pub define " + kind
                            + " Endpoint as fnc send(String first, String second) => String; end"));
        }
    }
}
