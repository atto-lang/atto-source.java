package dev.oreslang;

import dev.oreslang.imports.NamespaceScopeV2;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NamespaceScopeV2Test {
    private static final String PATH = "src/network/http/client.atto";

    @Test void numericAndNamedKeysAgreeButFolderIsSeparate() {
        assertEquals(NamespaceScopeV2.named("http"), NamespaceScopeV2.numericDefault(PATH, 1));
        assertEquals(NamespaceScopeV2.named("network.http"), NamespaceScopeV2.numericDefault(PATH, 2));
        assertEquals(NamespaceScopeV2.named("src.network.http"), NamespaceScopeV2.numericDefault(PATH, 3));
        assertEquals(NamespaceScopeV2.numericDefault(PATH, 2),
                NamespaceScopeV2.numericDefault("other/network/http/other.atto", 2));
        assertNotEquals(NamespaceScopeV2.named("src.network.http"), NamespaceScopeV2.folder(PATH));
        assertEquals("src/network/http", NamespaceScopeV2.folder(PATH).identity());
        assertEquals("", NamespaceScopeV2.folder("main.atto").identity());
    }

    @Test void fileKeysRemainDistinctAndFolderMembershipIsDirect() {
        assertEquals(NamespaceScopeV2.file(PATH), NamespaceScopeV2.file(PATH));
        assertNotEquals(NamespaceScopeV2.file(PATH), NamespaceScopeV2.file("src/network/http/other.atto"));
        assertEquals(NamespaceScopeV2.folder(PATH), NamespaceScopeV2.folder("src/network/http/other.atto"));
        assertNotEquals(NamespaceScopeV2.folder(PATH), NamespaceScopeV2.folder("src/db/http/other.atto"));
        assertNotEquals(NamespaceScopeV2.folder(PATH), NamespaceScopeV2.folder("src/network/http/child/other.atto"));
    }

    @Test void rejectsInvalidLogicalPathsAndDepthBeforeLinking() {
        for (String path : new String[]{null, "../main.atto", "/main.atto", "src/./main.atto",
                "src//main.atto", "src\\main.atto", "src/.atto", "src/main.txt"}) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> NamespaceScopeV2.folder(path))
                    .getMessage().startsWith("E_NAMESPACE_PATH"));
        }
        for (int depth : new int[]{-1,0,4,33}) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> NamespaceScopeV2.numericDefault(PATH,depth))
                    .getMessage().startsWith("E_NAMESPACE_DEPTH"));
        }
        assertThrows(IllegalArgumentException.class, () -> NamespaceScopeV2.named("network..http"));
        assertThrows(IllegalArgumentException.class, () -> NamespaceScopeV2.named("network.parent"));
    }
}
