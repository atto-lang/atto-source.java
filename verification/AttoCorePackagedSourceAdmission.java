import dev.oreslang.stdlib.CoreLibrarySources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compiler-owned source loader proof, not a claim of JVM/Truffle guest execution. */
public final class AttoCorePackagedSourceAdmission {
    private static int assertions;

    private static void expect(boolean value, String label) {
        if (!value) throw new AssertionError("ATTO_JAVA_PACKAGED_CORE: " + label);
        assertions++;
    }

    private static void failClosed(String specifier) throws java.io.IOException {
        expect(CoreLibrarySources.isReservedOresLibraryImport(specifier),
            "reserved import path " + specifier);
        try {
            CoreLibrarySources.oresCoreSource(specifier);
        } catch (IllegalArgumentException failure) {
            assertions++;
            return;
        }
        throw new AssertionError("untrusted core path unexpectedly resolved: " + specifier);
    }

    public static void main(String[] args) throws Exception {
        for (String stem : List.of("slice", "iter", "cmp")) {
            String canonical = "atto.core/" + stem;
            String legacy = "ores.core/" + stem;
            String owned = CoreLibrarySources.oresCoreSource(canonical);
            expect(CoreLibrarySources.isOresCoreImport(canonical), stem + ": compiler-owned prefix");
            expect(CoreLibrarySources.oresCoreUnitId(canonical).equals(
                CoreLibrarySources.oresCoreUnitId(legacy)), stem + ": canonical+legacy identity");
            expect(owned.equals(CoreLibrarySources.oresCoreSource(legacy)),
                stem + ": canonical+legacy exact bytes");
            expect(owned.equals(CoreLibrarySources.oresCoreSource(canonical + ".atto")),
                stem + ": explicit extension same source");
            byte[] source = Files.readAllBytes(Path.of("core", stem + ".atto"));
            expect(java.util.Arrays.equals(source, owned.getBytes(StandardCharsets.UTF_8)),
                stem + ": packaged source matches backend-owned source file");
        }
        for (String invalid : List.of("atto.core", "atto.core/missing",
                "atto.core/../slice", "atto.core//slice", "atto.core/cmp.ores",
                "atto.core/cmp.java", "atto.core/slice.atto.ores", "atto.core/iter%2eatto")) {
            failClosed(invalid);
        }
        System.out.println("ATTO_JAVA_CORE_PACKAGED_LOADER_ASSERTIONS=" + assertions);
    }
}
