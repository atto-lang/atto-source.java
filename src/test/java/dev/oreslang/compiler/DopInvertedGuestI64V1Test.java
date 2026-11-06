package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in restricted guest lane only: general TypeChecker must stay fail-closed. */
final class DopInvertedGuestI64V1Test {
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts", "dop-inverted-v1", name));
    }

    private static String program(String body) {
        return "define inverted struct Row as let value: int; token: int; end\n"
                + "fnc main(): int { let rows = InvertedArray<Row>.with_capacity(3);\n"
                + body + "\n}";
    }

    @Test
    void productionLexerAndRestrictedMirRunCanonicalSwapReuse() throws Exception {
        var plan = OresCompiler.compileInvertedI64Guest(fixture("swap-reuse.atto"));
        assertEquals("Row", plan.schemaName());
        assertEquals(java.util.List.of("value", "token"), plan.columns());
        assertEquals(3, plan.capacity());
        assertEquals(5, plan.instructionCount());
        assertEquals(42L, plan.execute());
        assertEquals(42L, plan.execute(), "each run creates a new isolated store");
    }

    @Test
    void immutableColumnRemainsAttachedToSurvivorAfterSwapDelete() throws Exception {
        var plan = OresCompiler.compileInvertedI64Guest(
                fixture("positive-immutable-column-swap.atto"));
        assertEquals("Sample", plan.schemaName());
        assertEquals(java.util.List.of("x", "id"), plan.columns());
        assertEquals(42L, plan.execute(),
                "immutable id column must follow the displaced surviving row");
    }

    @Test
    void canonicalFormerDenseIndexTrapsAfterSwapErase() throws Exception {
        var plan = OresCompiler.compileInvertedI64Guest(fixture("removed-index-trap.atto"));
        assertThrows(DopInvertedGuestI64V1.BoundsTrap.class, plan::execute);
    }

    @Test
    void checkedMutableFieldStoreAndImmutableColumnRejection() {
        var writable = DopInvertedGuestI64V1.compile(program(
                "rows.push(3, 4); rows.set(0, value, 42); return rows.get(0, value);"));
        assertEquals(42L, writable.execute());
        assertThrows(IllegalArgumentException.class, () -> DopInvertedGuestI64V1.compile(program(
                "rows.push(3, 4); rows.set(0, token, 42); return rows.get(0, token);")));
    }

    @Test
    void capacityAndLogicalLengthAreDistinctCheckedProperties() {
        var plan = DopInvertedGuestI64V1.compile(program(
                "rows.push(1, 2); rows.push(3, 4); rows.push(5, 6); rows.push(7, 8);"
                + "return rows.get(0, value);"));
        assertThrows(DopInvertedGuestI64V1.CapacityTrap.class, plan::execute);
        var invalid = DopInvertedGuestI64V1.compile(program(
                "rows.push(1, 2); return rows.get(2, token);"));
        assertThrows(DopInvertedGuestI64V1.BoundsTrap.class, invalid::execute);
    }

    @Test
    void strictAdmissionRejectsEffectsAndHiddenUserCode() {
        for (String body : java.util.List.of(
                "other.push(1, 2); return rows.get(0, value);",
                "rows.push(1, 2); await foo(); return rows.get(0, value);",
                "rows.push(1, 2); rows.get(0, value); return rows.get(0, value);",
                "rows.push(1, 2); return rows.get(0, missing);",
                "rows.push(1, 2); return rows.get(0, value); fnc hidden(): int { return 7; }")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DopInvertedGuestI64V1.compile(program(body)), body);
        }
    }

    @Test
    void normalGuestCompilerMustRemainFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program(
                        "rows.push(1, 2); return rows.get(0, value);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("return rows.get(0, value);")));
    }
}
