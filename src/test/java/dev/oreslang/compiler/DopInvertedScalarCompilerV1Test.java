package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Production Parser -> closed typed plan -> private SoA host execution.
 * This is not a Truffle guest call target or native Graal AOT execution.
 */
final class DopInvertedScalarCompilerV1Test {
    private static String contract(String name) throws Exception {
        return Files.readString(Path.of("contracts", "dop-inverted-v1", name));
    }

    private static String source(String statements) {
        return "define inverted struct Row as let value: int; token: int; end\n"
                + "fnc main(): int {\n"
                + "let rows = InvertedArray<Row>.with_capacity(3);\n"
                + statements + "\n}\n";
    }

    private static IllegalArgumentException rejectCompilation(String source) {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileDopInvertedScalarI64(source));
        assertTrue(thrown.getMessage().contains("DOP_INVERTED_V1_REJECTED"),
                "restricted typed checker must reject unsupported source");
        return thrown;
    }

    @Test
    void canonicalLlvmSwapReuseCompilesAndExecutesAgainstPrivateSoA() throws Exception {
        String atto = contract("swap-reuse.atto");
        DopInvertedScalarCompilerV1.Plan plan =
                OresCompiler.compileDopInvertedScalarI64(atto);
        assertEquals("Row", plan.schemaName());
        assertEquals("rows", plan.owner());
        assertEquals(3, plan.capacity());
        assertEquals(5, plan.instructions().size());
        assertEquals(DopInvertedScalarCompilerV1.Op.RETURN_READ,
                plan.instructions().getLast().op());
        assertEquals(42L, plan.executeHostReference());
        assertEquals(42L, plan.executeHostReference());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.instructions().clear());
    }

    @Test
    void exactSharedColumnWriteFixtureLowersToTypedStorageOperations() throws Exception {
        String atto = contract("column-write-42.atto");
        var plan = OresCompiler.compileDopInvertedScalarI64(atto);
        assertEquals(4, plan.instructions().size());
        assertEquals(DopInvertedScalarCompilerV1.Op.WRITE,
                plan.instructions().get(2).op());
        assertEquals("value", plan.instructions().get(2).field());
        assertEquals(42L, plan.executeHostReference());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(atto))
                .getMessage().contains("DOP_INVERTED_BACKEND_UNSUPPORTED"));
    }

    @Test
    void canonicalRemovedIndexTriggersActualLengthGuard() throws Exception {
        DopInvertedScalarCompilerV1.Plan plan =
                OresCompiler.compileDopInvertedScalarI64(contract("removed-index-trap.atto"));
        assertThrows(IllegalArgumentException.class, plan::executeHostReference,
                "the deleted former dense index cannot be interpreted as a generational handle");
    }

    @Test
    void immutableFieldRemainsReadableButCannotBeWrittenOrMutablyBorrowed() throws Exception {
        rejectCompilation(contract("immutable-write-rejected.atto"));
        assertEquals(100L, OresCompiler.compileDopInvertedScalarI64(source(
                "rows.push(7, 100); return rows.get(0, token);"
        )).executeHostReference());
        rejectCompilation(source(
                "rows.push(7, 100); rows.set(0, token, 42); return rows.get(0, value);"
        ));
    }

    @Test
    void ordinaryParserAndTypeCheckerRemainFailClosed() throws Exception {
        String canonical = contract("swap-reuse.atto");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(canonical))
                .getMessage().contains("DOP_INVERTED_BACKEND_UNSUPPORTED"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(canonical))
                .getMessage().contains("DOP_INVERTED_BACKEND_UNSUPPORTED"));
        Ast.Program parsed = Parser.parseDopInvertedScalarV1(canonical);
        assertEquals(2, parsed.modules().getFirst().declarations().size());
    }

    @Test
    void capacityAndSourceEffectsAreCheckedBeforeAnyExecution() {
        rejectCompilation(source(
                "rows.push(1,2); rows.push(3,4); rows.push(5,6); rows.push(7,8);"
                + "return rows.get(0,value);"
        ));
        rejectCompilation(source(
                "rows.push(7,100); rows.set(0,value,compute()); return rows.get(0,value);"
        ));
        rejectCompilation(source(
                "other.push(7,100); return rows.get(0,value);"
        ));
        rejectCompilation(source(
                "rows.push(7,100); rows.get(0,value); return rows.get(0,value);"
        ));
    }

    @Test
    void rejectsUnsupportedOwnerMethodAndUnknownFieldSelectors() {
        rejectCompilation(source(
                "rows.reserve(100); return rows.get(0,value);"
        ));
        rejectCompilation(source(
                "rows.push(7,100); return rows.get(0, unknown);"
        ));
        rejectCompilation(source(
                "rows.push(7,100); rows.set(0,value + 1,99); return rows.get(0,value);"
        ));
    }

    @Test
    void noImplicitHeapFallbackForWrongSchemaOrExtraDeclaration() {
        rejectCompilation(source(
                "rows.push(7,100); return rows.get(0,value);"
        ).replace("InvertedArray<Row>", "InvertedArray<Other>"));
        rejectCompilation(source(
                "rows.push(7,100); return rows.get(0,value);"
        ) + "fnc hidden(): int { return 0; }");
    }

    @Test
    void runtimeBoundsAndCapacityNeverAutoExpand() {
        var plan = OresCompiler.compileDopInvertedScalarI64(source(
                "rows.push(7,100); return rows.get(1,value);"
        ));
        assertThrows(IllegalArgumentException.class, plan::executeHostReference);
    }
}
