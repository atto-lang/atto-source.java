package dev.oreslang.compiler;

import com.oracle.truffle.api.RootCallTarget;
import dev.oreslang.parser.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Truffle RootCallTarget execution of a restricted production-parsed,
 * typed DOP plan. This is NOT general OresLanguage guest admission, tenant
 * isolation, native-image, AOT or independent LLVM parity.
 */
final class DopInvertedScalarTruffleRootV1Test {
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts","dop-inverted-v1",name));
    }

    private static String source(String operations) {
        return "define inverted struct Row as let value: int; token: int; end\n"
             + "fnc main(): int { let rows = InvertedArray<Row>.with_capacity(2);\n"
             + operations + "\n}\n";
    }

    private static RootCallTarget compile(String source) {
        return OresCompiler.compileDopInvertedScalarI64TruffleRoot(source);
    }

    @Test
    void byteIdenticalLlvmCapacityFixtureExecutesThroughTruffleRoot() throws Exception {
        String code = fixture("capacity-256-42.atto");
        RootCallTarget target = compile(code);
        assertEquals("atto-dop-inverted-i64-v1", target.getRootNode().getName());
        assertEquals(42L, target.call());
        assertEquals(42L, target.call());
        // The host bridge and Truffle root must agree on the same checked plan.
        assertEquals(OresCompiler.compileDopInvertedScalarI64(code)
                .executeHostReference(), target.call());
    }

    @Test
    void swapDeleteSourceExecutesOnTruffleRootWithoutStaleOwnerState() throws Exception {
        String code = fixture("swap-reuse.atto");
        RootCallTarget target = compile(code);
        assertEquals(42L, target.call());
        assertEquals(42L, target.call());
    }

    @Test
    void typedWritesAndImmutableReadsUseTheDeclaredColumn() {
        RootCallTarget target = compile(source(
                "rows.push(11, 100); rows.push(20, 101); "
              + "rows.set(1, value, 42); return rows.get(1, value);"));
        assertEquals(42L, target.call());
        RootCallTarget readOnly = compile(source(
                "rows.push(11, 100); return rows.get(0, token);"));
        assertEquals(100L, readOnly.call());
    }

    @Test
    void malformedAndOutOfRangeProgramsFailBeforeAnyRootIsConstructed()
            throws Exception {
        for (String negative : new String[]{
                "immutable-write-rejected.atto",
                "capacity-zero-rejected.atto",
                "capacity-257-rejected.atto"}) {
            String code = fixture(negative);
            assertThrows(IllegalArgumentException.class, () -> compile(code),
                    negative);
        }
    }

    @Test
    void erasedOrBeyondEndDenseIndicesFailAtRealRootExecution() throws Exception {
        RootCallTarget erased = compile(fixture("removed-index-trap.atto"));
        assertThrows(IllegalArgumentException.class, erased::call);
        assertThrows(IllegalArgumentException.class, erased::call);
        RootCallTarget outside = compile(source(
                "rows.push(42, 7); return rows.get(2, value);"));
        assertThrows(IllegalArgumentException.class, outside::call);
        assertEquals(42L, compile(source(
                "rows.push(42, 7); return rows.get(0, value);")).call());
    }

    @Test
    void unsupportedArgumentsAndForeignExecutionAreNeverAdmitted() {
        RootCallTarget target = compile(source(
                "rows.push(42, 7); return rows.get(0, value);"));
        assertThrows(IllegalArgumentException.class, () -> target.call("guest-input"));
        String code = source("rows.push(42, 7); return rows.get(0, value);");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Parser.parse(code))
                .getMessage().contains("DOP_INVERTED_BACKEND_UNSUPPORTED"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(code))
                .getMessage().contains("DOP_INVERTED_BACKEND_UNSUPPORTED"));
    }

    @Test
    void sixtyFourCompiledSourcesRetainIndependentValuesAcrossRepeatedRootCalls() {
        long totalCalls = 0;
        for (int seed = 1; seed <= 64; seed++) {
            long value = seed % 2 == 0 ? -((long) seed) : (long) seed;
            String code = source(
                    "rows.push(11, 100); rows.set(0, value, " + value + "); "
                  + "return rows.get(0, value);");
            RootCallTarget target = compile(code);
            long hostValue = OresCompiler.compileDopInvertedScalarI64(code)
                    .executeHostReference();
            assertEquals(value, hostValue, "host reference seed=" + seed);
            for (int replay = 0; replay < 5; replay++) {
                assertEquals(hostValue, target.call(),
                        "isolated Truffle root seed=" + seed + " replay=" + replay);
                totalCalls++;
            }
        }
        assertEquals(320L, totalCalls);
    }

    @Test
    void parseTimeContextUuidRejectsForeignFirstCallBeforeAnyOwnerExists() {
        UUID parseContext =
                UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID foreignFirstCaller =
                UUID.fromString("22222222-2222-4222-8222-222222222222");
        assertDoesNotThrow(() ->
                DopInvertedScalarTruffleRootV1.requireParsedContext(
                        parseContext, parseContext));
        for (UUID actual : new UUID[]{foreignFirstCaller, null}) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> DopInvertedScalarTruffleRootV1.requireParsedContext(
                            parseContext, actual));
            assertTrue(denied.getMessage().contains("DOP_VERIFIED_CONTEXT_MISMATCH"));
        }
        assertThrows(SecurityException.class, () ->
                DopInvertedScalarTruffleRootV1.requireParsedContext(
                        null, parseContext));
        // A first foreign attempted invocation never gets to "claim" or
        // initialize the parse-context identity; a valid call remains valid.
        assertDoesNotThrow(() ->
                DopInvertedScalarTruffleRootV1.requireParsedContext(
                        parseContext, parseContext));
    }

    @Test
    void sharedBoundsRemainSeparateFromOrdinaryTruffleLanguage() {
        String code = source("rows.push(42, 7); return rows.get(0, value);");
        RootCallTarget a = compile(code);
        RootCallTarget b = compile(code);
        assertNotSame(a, b, "explicit roots cannot share an owned mutable SoA");
        assertEquals(42L, a.call());
        assertEquals(42L, b.call());
    }
}
