package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageLayout;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Packages are manifest roots, namespaces are folders (dotted names), and
 * edits re-check only the namespace they touch unless its interface changes.
 */
final class PackageNamespaceLayoutTest {
    private static final PackageLayout SHOP = new PackageLayout(Map.of("src", "shop"));

    private static Map<String, String> shop(String httpBody) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/net/http/client.atto", """
                define module client as
                  pub fnc status(int code): bool { return %s; }
                end
                """.formatted(httpBody));
        sources.put("src/app/main.atto", """
                import module client from "../net/http/client.atto";
                pub fnc main(): int { if client.status(200) then return 0; fi return 1; }
                """);
        sources.put("src/root.atto", "pub fnc version(): int { return 1; }\n");
        return sources;
    }

    @Test
    void dottedAndDerivedHeadersResolveToDottedNamespaces() {
        assertEquals("commerce.storefront",
                Parser.parse("use namespace commerce.storefront;\npub fnc f(): int { return 0; }\n").namespace());
        Map<String, String> sources = Map.of(
                "src/commerce/storefront/a.atto", "use default namespace(2);\npub fnc a(): int { return 0; }\n",
                "src/commerce/storefront/b.atto", "use default namespace;\npub fnc b(): int { return 0; }\n",
                "src/commerce/storefront/c.atto", "use default namespace(3);\npub fnc c(): int { return 0; }\n");
        var build = new IncrementalCompiler().compile(sources);
        assertEquals("commerce.storefront", build.units().get("src/commerce/storefront/a.atto").namespace());
        assertEquals("storefront", build.units().get("src/commerce/storefront/b.atto").namespace());
        assertEquals("src.commerce.storefront", build.units().get("src/commerce/storefront/c.atto").namespace());
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "a.atto", "use default namespace(2);\npub fnc a(): int { return 0; }\n")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use default namespace(33);\npub fnc a(): int { return 0; }\n"));
    }

    @Test
    void packageRootsGiveFolderPathNamespacesAndPackageIdentity() {
        var build = new IncrementalCompiler().compile(shop("code >= 200"), Map.of(), SHOP);
        var http = build.units().get("src/net/http/client.atto");
        assertEquals("net.http", http.namespace());
        assertEquals("pkg:shop", http.packageId());
        assertEquals("app", build.units().get("src/app/main.atto").namespace());
        assertEquals("shop", build.units().get("src/root.atto").namespace(),
                "files at the package root take the package name");
        assertEquals(Set.of("pkg:shop#net.http", "pkg:shop#app", "pkg:shop#shop"),
                build.namespaceInterfaces().keySet());
        assertEquals(Set.of("pkg:shop"), build.packageInterfaces().keySet());

        var outside = new IncrementalCompiler().compile(
                Map.of("tools/x.atto", "pub fnc x(): int { return 0; }\n"), Map.of(), SHOP);
        assertNull(outside.units().get("tools/x.atto").namespace());
        assertEquals("file:tools/x.atto", outside.units().get("tools/x.atto").packageId());
    }

    @Test
    void invalidFolderNamesNeedAnExplicitHeader() {
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(
                Map.of("src/my-dir/x.atto", "pub fnc x(): int { return 0; }\n"), Map.of(), SHOP));
        var build = new IncrementalCompiler().compile(Map.of("src/my-dir/x.atto",
                "use namespace my_dir;\npub fnc x(): int { return 0; }\n"), Map.of(), SHOP);
        assertEquals("my_dir", build.units().get("src/my-dir/x.atto").namespace());
    }

    @Test
    void namespaceEditsStayInsideTheNamespaceUntilTheInterfaceChanges() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var first = compiler.compile(shop("code >= 200"), Map.of(), SHOP);

        var bodyEdit = compiler.compile(shop("code >= 200 && code < 300"), Map.of(), SHOP);
        assertEquals(Set.of("pkg:shop#net.http"), bodyEdit.rebuiltNamespaces(),
                "an implementation edit re-checks only its own namespace");
        assertEquals(first.namespaceInterfaces(), bodyEdit.namespaceInterfaces());
        assertEquals(first.packageInterfaces(), bodyEdit.packageInterfaces(),
                "other packages see an unchanged package interface");

        Map<String, String> signatureEdit = shop("code >= 200");
        signatureEdit.put("src/net/http/client.atto", """
                define module client as
                  pub fnc status(int code): bool { return code >= 200; }
                  pub fnc is_redirect(int code): bool { return code >= 300 && code < 400; }
                end
                """);
        var widened = compiler.compile(signatureEdit, Map.of(), SHOP);
        assertTrue(widened.rebuiltNamespaces().contains("pkg:shop#app"),
                "an interface change re-checks importing namespaces");
        assertFalse(widened.rebuiltNamespaces().contains("pkg:shop#shop"),
                "namespaces that do not import it are untouched");
        assertNotEquals(first.packageInterfaces(), widened.packageInterfaces());
    }
}
