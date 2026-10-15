package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Imported class layouts alone cannot authorize additions to a sealed tree.
 * Only the declaring compilation unit can prove its complete subtype set.
 */
final class SealedImportedHierarchyBoundaryTest {
    @Test
    void foreignImportCannotExtendSealedParentEvenWhenExplicitlyPermittedLocally() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/base.ores", """
                pub define sealed class Root permits Legit as end
                pub define final class Legit extends Root as end
                """);
        files.put("src/foreign.ores", """
                import class Root from './base';
                define final class Intruder extends Root as end
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
        assertTrue(failure.getMessage().contains("same-module")
                        || failure.getMessage().contains("does not permit"),
                failure.getMessage());
    }

    @Test
    void importedFinalClassCannotBeExtended() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/base.ores", """
                pub define final class Terminal as end
                """);
        files.put("src/foreign.ores", """
                import class Terminal from './base';
                define final class Intruder extends Terminal as end
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
        assertTrue(failure.getMessage().contains("cannot be extended"),
                failure.getMessage());
    }
}
