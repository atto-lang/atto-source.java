package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PackageNamespaceImportSyntaxTest {
    private static Ast.ImportDecl first(String source) {
        return Parser.parse(source + "\npub routine main(): void { return; }\n").imports().getFirst();
    }

    @Test void explicitNamespaceSelectionPreservesPackageAndDottedIdentity(){
        var a=first("import namespace billing as provider from \"checkout\";");
        assertEquals(Ast.ImportKind.ALL,a.kind());assertTrue(a.wildcard());assertEquals(List.of(),a.names());
        assertEquals("provider",a.namespace());assertEquals("pkg:namespace:checkout:billing",a.path());
        var b=first("import namespace bar.shop.cart from \"checkout\";");
        assertEquals("cart",b.namespace());assertEquals("pkg:namespace:checkout:bar.shop.cart",b.path());
        assertEquals(b,first("import namespace bar.shop.cart as cart from pkg 'checkout';"));
        var e=assertThrows(IllegalArgumentException.class,()->ImportRules.validate(a));
        assertTrue(e.getMessage().startsWith("E_NAMESPACE_LINK_REQUIRED:"));
        assertThrows(IllegalArgumentException.class,()->first("import * as x from 'pkg:namespace:checkout:billing';"));
        assertThrows(IllegalArgumentException.class,()->first("import pkg 'namespace:checkout:billing' as x;"));
        assertThrows(IllegalArgumentException.class,()->first("import * as x from pkg 'namespace:checkout:billing';"));
        System.out.println("JAVA_NAMESPACE_SELECTOR|typed-selector|linker-required");
    }
    @Test void namespaceSelectorMalformedAndAliasCollisionReject(){
        for(String bad:List.of("import namespace a..b from 'checkout';","import namespace a. from 'checkout';",
            "import namespace a from '../checkout';","import namespace a from 'java:java.lang.System';",
            "import namespace __root__ from 'checkout';","import namespace a as __root__ from 'checkout';",
            "import namespace a from 'checkout'","import namespace a from '';",
            "import namespace a as x from 'checkout'; import namespace b as x from 'checkout';")){
            assertThrows(IllegalArgumentException.class,()->first(bad),bad);
        }
    }
    @Test
    void shorthandAndExplicitWildcardPkgHaveIdenticalBindingAst() {
        Ast.ImportDecl shortForm = first("import pkg 'z';");
        Ast.ImportDecl longForm = first("import * as z from pkg 'z';");
        assertEquals(longForm, shortForm);
        assertEquals(Ast.ImportKind.ALL, shortForm.kind());
        assertTrue(shortForm.wildcard());
        assertEquals(List.of(), shortForm.names());
        assertEquals("z", shortForm.namespace());
        assertEquals("pkg:z", shortForm.path());
    }

    @Test
    void aliasesAndDottedPackageNamesBindOnlyLocalNamespace() {
        assertEquals("zed", first("import pkg 'z' as zed;").namespace());
        assertEquals("telemetry", first("import pkg 'core.telemetry';").namespace());
        assertEquals("math", first("import pkg '@scope/math' as math;").namespace());
        assertEquals("math", first("import pkg '+/lib' as math;").namespace());
        assertEquals("pkg:utils", first("import module math from pkg 'utils';").path());
        assertEquals("./relative-file.atto", first("import * as x from './relative-file.atto';").path());
    }

    @Test
    void invalidAndAmbiguousNamespaceSelectorsFailAtParse() {
        for (String source : List.of(
                "import pkg 'my-lib';",
                "import pkg '@scope/math';",
                "import pkg '+/lib';",
                "import pkg 'core.class';",
                "import pkg '';",
                "import pkg 'foo..bar' as bar;",
                "import pkg 'z' as class;",
                "import pkg z;",
                "import pkg 'z' as;",
                "import pkg 'z'"
        )) {
            assertThrows(IllegalArgumentException.class, () -> first(source), source);
        }
    }

    @Test
    void collisionsAcrossPackageAndExistingImportsAreCompileTimeErrors() {
        for (String source : List.of(
                "import pkg 'z';\nimport pkg 'other' as z;",
                "import module z from './local';\nimport pkg 'z';",
                "import pkg 'z' as x;\nimport * as x from './local';"
        )) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source + "\npub routine main(): void { return; }\n"),
                    source);
        }
    }

    @Test
    void packageImportsCannotBypassUnimplementedLinkerThroughHostPath() {
        for (String source : List.of(
                "import pkg 'z';",
                "import pkg 'z' as zed;",
                "import * as z from pkg 'z';",
                "import module math from pkg 'z';",
                "import * as z from 'pkg:z';"
        )) {
            Ast.ImportDecl declaration = first(source);
            IllegalArgumentException rejection =
                    assertThrows(IllegalArgumentException.class,
                            () -> ImportRules.validate(declaration));
            assertTrue(rejection.getMessage().contains("validated package-export graph linker"));
        }
    }
}
