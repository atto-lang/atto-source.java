package dev.oreslang;

import dev.oreslang.imports.ContextualImportPath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContextualImportPathTest {
    @Test void normalizesOnlyLogicalNamespaceRoots() {
        String[][] cases = {
            {null, "pkg.config", "config"},
            {"network.http", "self.utils", "network.http.utils"},
            {"network.http", "super.config", "network.config"},
            {"network.http", "super.super.common", "common"},
            {null, "pkg.a.b.c.d.e.f.g", "a.b.c.d.e.f.g"},
            {"network.http", "pkg.config", "config"}
        };
        for (String[] c : cases) assertEquals(c[2], ContextualImportPath.normalize(c[1], c[0]));
    }

    @Test void rejectsMalformedMetadataBeforeTraversalAndBoundsInputs() {
        String[][] cases = {
            {"network.", "super.config"},
            {"network.__atto_hidden", "super.config"},
            {"network.super", "super.config"},
            {"network.bad-name", "super.config"},
            {"a.b.c.d.e.f.g.h.i", "super.config"},
            {"network..http", "super.config"},
            {"network.http", "pkg"},
            {"network.http", "self"},
            {"network.http", "super"},
            {"network.http.client", "super.super"},
            {null, "pkg.shared"},
            {"network.shared", "super.config"},
            {null, "self.utils"},
            {null, "super.config"},
            {"network.http", "super.super.super.shared"},
            {"network.http", "self.super.config"},
            {"network.http", "pkg.a.b.c.d.e.f.g.h"},
            {"network.http", "pkg.é"},
            {"network.http", "pkg.config."},
            {"network.http", "pkg.__atto_reserved"}
        };
        for (String[] c : cases) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ContextualImportPath.normalize(c[1], c[0]), c[1]);
            assertTrue(error.getMessage().startsWith("E_IMPORT_PATH"));
        }
        assertThrows(IllegalArgumentException.class, () -> ContextualImportPath.normalize("pkg." + "a".repeat(521), null));
    }
    @Test void productionParserRejectsContextualPathsBeforeFileResolution() {
        for (String source : new String[]{
                "import self.utils;",
                "use namespace network.http; import super.super.super.shared;",
                "use namespace network.__atto_hidden; import super.config;",
                "import pkg;",
                "import pkg.config.;"}) {
            var error = assertThrows(RuntimeException.class,
                    () -> dev.oreslang.parser.Parser.parse(source + " pub fnc main(): int { return 42; }"));
            assertTrue(error.getMessage().contains("E_IMPORT_PATH"), error.getMessage());
        }
        for (String source : new String[]{
                "import pkg.config;",
                "use namespace network.http; import self.utils;",
                "use namespace network.http; import super.config;"}) {
            var error = assertThrows(RuntimeException.class,
                    () -> dev.oreslang.parser.Parser.parse(source + " pub fnc main(): int { return 42; }"));
            assertTrue(error.getMessage().contains("authenticated package-export graph"), error.getMessage());
        }
    }

    @Test void namespaceFirstBuiltinUsesExistingImportIdentity() {
        String suffix = " pub fnc main(): int { return 42; }";
        var expected = dev.oreslang.parser.Parser.parse("import * as n from \"core/num\";" + suffix).imports();
        for (String source : new String[]{"core.num", "'core.num'", "\"core.num\""}) {
            assertEquals(expected, dev.oreslang.parser.Parser.parse("import namespace n from " + source + ";" + suffix).imports());
        }
        for (String source : new String[]{"'./core.num'", "'@org/project/package'", "'core.unknown'"}) {
            var error = assertThrows(RuntimeException.class,
                    () -> dev.oreslang.parser.Parser.parse("import namespace n from " + source + ";" + suffix));
            assertTrue(error.getMessage().contains("authenticated package-export graph"), error.getMessage());
        }
    }
    @Test void aliasCyclesAreRejectedBeforeLinking() {
        for (String source : new String[]{
                "define alias a = b; define alias b = a;",
                "define alias a as b; define alias b as a;",
                "define alias a = b.child; define alias a as b;",
                "import fnc f from './missing.atto'; define alias a = a;"}) {
            var error = assertThrows(RuntimeException.class,
                    () -> dev.oreslang.parser.Parser.parse(source + " pub fnc main(): int { return 42; }"));
            assertTrue(error.getMessage().contains("E_IMPORT_CYCLE"), error.getMessage());
        }
        var duplicate = assertThrows(RuntimeException.class,
                () -> dev.oreslang.parser.Parser.parse("define alias a = b; define alias a = c;"));
        assertTrue(duplicate.getMessage().contains("E_DUPLICATE"), duplicate.getMessage());
        var unsupported = assertThrows(RuntimeException.class,
                () -> dev.oreslang.parser.Parser.parse("define alias a = core.num; pub fnc main(): int { return 42; }"));
        assertTrue(unsupported.getMessage().contains("authenticated static binding integration"), unsupported.getMessage());
    }
}
