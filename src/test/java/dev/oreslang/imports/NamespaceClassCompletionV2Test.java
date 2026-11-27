package dev.oreslang.imports;

import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.oreslang.imports.NamespaceClassCompletionV2.*;
import static org.junit.jupiter.api.Assertions.*;

final class NamespaceClassCompletionV2Test {
    private static OwnerKey key(String pkg, NamespaceScopeV2.Scope scope, String module, String name) {
        return new OwnerKey(pkg, scope, module, name);
    }
    private static final String PKG = "pinned-package";
    private static final Slot METHOD = new Slot(SlotKind.METHOD, "answer():int");
    private static final Slot CTOR = new Slot(SlotKind.CONSTRUCTOR, "()");

    @Test void namedAndFolderContributorsCompleteInAnyOrder() {
        for (var scope : List.of(NamespaceScopeV2.named("network.http"),
                NamespaceScopeV2.folder("src/network/http/a.atto"))) {
            OwnerKey owner = key(PKG, scope, "", "Foo");
            OwnerKey contributor = key(PKG, scope, "", "contributor");
            var owners = List.of(new Owner(owner, Form.DECLARED));
            var ctor = new Attachment(contributor, owner, CTOR);
            var method = new Attachment(contributor, owner, METHOD);
            var a = close(owners, List.of(ctor, method));
            assertEquals(a, close(owners, List.of(method, ctor)));
            assertEquals(List.of(CTOR, METHOD), a.get(owner).slots());
            assertThrows(UnsupportedOperationException.class, () -> a.clear());
        }
    }

    @Test void fileFolderPackageAndModuleBoundariesAreNotAttachmentAuthority() {
        var scope = NamespaceScopeV2.file("src/a.atto");
        OwnerKey owner = key(PKG, scope, "", "Foo");
        var owners = List.of(new Owner(owner, Form.DECLARED));
        for (var c : List.of(key(PKG, NamespaceScopeV2.file("src/b.atto"), "", "contributor"),
                key(PKG, NamespaceScopeV2.folder("src/a.atto"), "", "contributor"),
                key("another-package", scope, "", "contributor"),
                key(PKG, scope, "nested", "contributor"))) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> close(owners, List.of(new Attachment(c, owner, METHOD))))
                    .getMessage().startsWith("E_NAMESPACE"));
        }
    }

    @Test void missingDuplicateAndMixedOwnersNeverPublishPartialResults() {
        OwnerKey owner = key(PKG, NamespaceScopeV2.named("example"), "", "Foo");
        OwnerKey missing = key(PKG, owner.scope(), "", "Missing");
        var declared = new Owner(owner, Form.DECLARED);
        var method = new Attachment(owner, owner, METHOD);
        assertCode("E_UNLINKED", () -> close(List.of(declared), List.of()));
        assertCode("E_UNLINKED", () -> close(List.of(declared, new Owner(missing, Form.DECLARED)), List.of(method)));
        assertCode("E_DUPLICATE", () -> close(List.of(declared, declared), List.of(method)));
        assertCode("E_DUPLICATE", () -> close(List.of(declared), List.of(method, method)));
        assertCode("E_UNBOUND", () -> close(List.of(declared), List.of(new Attachment(owner, missing, METHOD))));
        assertCode("E_UNSUPPORTED", () -> close(List.of(new Owner(owner, Form.INLINE)), List.of(method)));
    }
    private static void assertCode(String code, Runnable f) {
        assertTrue(assertThrows(IllegalArgumentException.class, f::run).getMessage().startsWith(code));
    }
}
