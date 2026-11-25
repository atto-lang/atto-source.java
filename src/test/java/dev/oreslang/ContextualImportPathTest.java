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
            {"network.http", "super.super.shared", "shared"},
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
}
