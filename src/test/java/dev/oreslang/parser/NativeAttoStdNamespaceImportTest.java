package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-grammar proof only. The linked guest canary in NativeAttoHttpStdlibTest
 * independently verifies actual static namespace member resolution.
 */
final class NativeAttoStdNamespaceImportTest {
    private static final String BODY = "pub fnc probe(): bool { return true; }";

    @Test
    void shorthandDesugarsToAnIdenticalStaticNamespaceImport() {
        Ast.ImportDecl shorthand = Parser.parse(
                "import http from \"std/http.atto\";\n" + BODY).imports().getFirst();
        Ast.ImportDecl explicit = Parser.parse(
                "import * as http from \"std/http.atto\";\n" + BODY).imports().getFirst();

        assertEquals(explicit, shorthand);
        assertEquals(Ast.ImportKind.ALL, shorthand.kind());
        assertTrue(shorthand.wildcard());
        assertEquals("http", shorthand.namespace());
        assertEquals("std/http.atto", shorthand.path());
        assertTrue(shorthand.names().isEmpty());
        assertEquals(java.util.List.of("http"), ImportRules.exposedBindings(shorthand));
        ImportRules.validate(shorthand);
    }

    @Test
    void namespaceHeaderAndAliasHaveDistinctStaticIdentities() {
        Ast.Program source = Parser.parse(
                "use namespace http;\npub fnc status_valid(): bool { return true; }");
        assertEquals("http", source.namespace());
        assertTrue(source.imports().isEmpty());

        Ast.ImportDecl renamed = Parser.parse(
                "import web from \"std/http.atto\";\n" + BODY).imports().getFirst();
        assertEquals("web", renamed.namespace());
        assertEquals("std/http.atto", renamed.path());
    }

    @Test
    void namespaceAliasesMustNotSilentlyCollide() {
        Ast.Program program = Parser.parse(
                "import http from \"std/http.atto\";\\n"
                + "import * as http from \"std/http.atto\";\\n" + BODY);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithDiagnostics(program));
    }

    @Test
    void invalidShortFormsCannotBeReinterpretedAsAmbientImports() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("import http from \"\";\n" + BODY));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("import http from foo;\n" + BODY));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("import http from \"std/http.atto\"\n" + BODY));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("import http from \"std/http.atto\" as other;\n" + BODY));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("import * from \"std/http.atto\";\n" + BODY));
    }
}
