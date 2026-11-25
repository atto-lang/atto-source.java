package dev.oreslang.imports;

import dev.oreslang.runtime.ExecutionProfile;

/** Test-source-only bridge; not a production CLI or native isolate receipt. */
public final class FrozenTrustedFixture {
    private FrozenTrustedFixture() {}
    public static int run(FrozenScalarImportCompiler.CheckedGuest guest) {
        return guest.runTrustedFixture(ExecutionProfile.parse("aot","server"));
    }
}
