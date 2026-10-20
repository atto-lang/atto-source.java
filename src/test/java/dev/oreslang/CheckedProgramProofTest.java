package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CheckedProgramProofTest {
    private static final String SOURCE = "pub fnc main(): void { return; }";

    @Test
    void manualCompiledUnitDoesNotMintPrecheckedAdmissionProof() {
        Ast.Program program = OresCompiler.parseAndTypeCheck(SOURCE);
        IncrementalCompiler.CompiledUnit unit = new IncrementalCompiler.CompiledUnit(
                "/tmp/manual.ores",
                "manual",
                "__root__",
                "source",
                "abi",
                Set.of(),
                SOURCE,
                program);

        assertNull(unit.checkedProgram());
    }

    @Test
    void checkedProofCannotBePairedWithDifferentProgramIdentity() {
        OresCompiler.CheckedProgram checked =
                OresCompiler.parseAndTypeCheckChecked(SOURCE);
        Ast.Program differentProgram = OresCompiler.parseAndTypeCheck(SOURCE);

        assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler.CompiledUnit(
                        "/tmp/mismatch.ores",
                        "mismatch",
                        "__root__",
                        "source",
                        "abi",
                        Set.of(),
                        SOURCE,
                        differentProgram,
                        checked));
    }
}
