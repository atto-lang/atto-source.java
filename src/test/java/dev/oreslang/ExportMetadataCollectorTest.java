package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportMetadataCollector;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExportMetadataCollectorTest {
    @Test
    void explicitAliasAndScopeAreMetadataNotHostLinkage() {
        List<ExportMetadataCollector.Entry> entries = OresCompiler.collectExportMetadata("""
                define class Internal as
                end
                export[pkg] class Internal as Public;
                """);

        assertEquals(1, entries.size());
        ExportMetadataCollector.Entry entry = entries.getFirst();
        assertEquals(ExportMetadataCollector.VERSION, entry.version());
        assertEquals(Ast.ExportKind.CLASS, entry.kind());
        assertEquals("Internal", entry.localName());
        assertEquals("Public", entry.externalName());
        assertEquals(Ast.ExportScope.PACKAGE, entry.scope());
        assertEquals(Ast.ExportOrigin.EXPLICIT_EXPORT, entry.origin());
        assertTrue(entry.localSymbolId().endsWith("::class::Internal"));
        assertEquals(64, entry.signatureSha256().length());
    }

    @Test
    void implementationBodyDoesNotChangeSourceSignatureDigest() {
        String first = OresCompiler.collectExportMetadata("""
                export fnc answer;
                fnc answer(int value): int { return value + 1; }
                """).getFirst().signatureSha256();
        String second = OresCompiler.collectExportMetadata("""
                export fnc answer;
                fnc answer(int value): int { return value + 2; }
                """).getFirst().signatureSha256();

        assertEquals(first, second);
    }

    @Test
    void namedParameterAndReturnTypeChangeSourceSignatureDigest() {
        String base = OresCompiler.collectExportMetadata("""
                export fnc answer;
                fnc answer(int value): int { return value; }
                """).getFirst().signatureSha256();
        String renamed = OresCompiler.collectExportMetadata("""
                export fnc answer;
                fnc answer(int other): int { return other; }
                """).getFirst().signatureSha256();
        String changedReturn = OresCompiler.collectExportMetadata("""
                export fnc answer;
                fnc answer(int value): float { return 1.0; }
                """).getFirst().signatureSha256();

        assertNotEquals(base, renamed);
        assertNotEquals(base, changedReturn);
    }

    @Test
    void overloadFamilyDigestIsOrderIndependent() {
        String first = OresCompiler.collectExportMetadata("""
                export fnc work;
                fnc work(int value): int { return value; }
                fnc work(string value): string { return value; }
                """).getFirst().signatureSha256();
        String reversed = OresCompiler.collectExportMetadata("""
                export fnc work;
                fnc work(string value): string { return value; }
                fnc work(int value): int { return value; }
                """).getFirst().signatureSha256();

        assertEquals(first, reversed);
    }

    @Test
    void exportedConstMetadataFailsClosedUntilValueEncodingIsCanonical() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.collectExportMetadata("""
                        export const ready;
                        const string ready = 'ready';
                        """));
        assertTrue(failure.getMessage().contains("EXPORT_CONST_ABI_VALUE_UNENCODED"),
                failure.getMessage());
    }

    @Test
    void inlinePubAndExplicitAliasProduceSeparateMetadataEntries() {
        List<ExportMetadataCollector.Entry> entries = OresCompiler.collectExportMetadata("""
                pub define class Internal as
                end
                export class Internal as Public;
                """);

        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(entry ->
                entry.externalName().equals("Internal")
                        && entry.origin() == Ast.ExportOrigin.IMPLICIT_PUB));
        assertTrue(entries.stream().anyMatch(entry ->
                entry.externalName().equals("Public")
                        && entry.origin() == Ast.ExportOrigin.EXPLICIT_EXPORT));
        assertEquals(1, entries.stream().map(ExportMetadataCollector.Entry::signatureSha256).distinct().count(),
                "aliases of one local symbol must share the same source signature digest");
    }
}
