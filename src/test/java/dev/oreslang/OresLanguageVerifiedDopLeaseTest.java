package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real OresLanguage parse/eval boundary, but only under an explicit one-shot
 * host-issued verified-source lease. NOT general inverted guest admission,
 * actor/tenant safety, Native Image, or cross-backend parity.
 */
final class OresLanguageVerifiedDopLeaseTest {
    private static final String UNIT = "verified-dop-scalar.atto";
    private static final String CODE = """
            define inverted struct Row as
                let value: int;
                token: int;
            end

            fnc main(): int {
                let rows = InvertedArray<Row>.with_capacity(2);
                rows.push(11, 100);
                rows.set(0, value, 42);
                return rows.get(0, value);
            }
            """;

    private static Source source(String code, String name) throws Exception {
        return Source.newBuilder(OresLanguage.ID, code, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
    }

    @Test
    void trustedOneShotLeaseExecutesTheRestrictedProgramThroughOresLanguage()
            throws Exception {
        Source guest = source(CODE, UNIT);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            try (OresLanguage.VerifiedDopScope scope =
                    OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
                assertEquals(42L, context.eval(guest).asLong());
            }
        }
    }

    @Test
    void identicalCachedSourceDoesNotExecuteAfterScopeEnds() throws Exception {
        Source guest = source(CODE, UNIT);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            try (OresLanguage.VerifiedDopScope scope =
                    OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
                assertEquals(42L, context.eval(guest).asLong());
            }
            // A cached CallTarget must recheck runtime authority, even when
            // Truffle never invokes the language's parse hook a second time.
            assertThrows(PolyglotException.class, () -> context.eval(guest));
        }
    }

    @Test
    void retainedParsedValueCannotEscapeItsVerifiedSourceScope() throws Exception {
        Source guest = source(CODE, UNIT);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            org.graalvm.polyglot.Value retained;
            try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
                retained = context.parse(guest);
                assertEquals(42L, retained.execute().asLong());
            }
            assertThrows(PolyglotException.class, () -> retained.execute(),
                    "a parsed function must not retain authority beyond its scope");
            // A new lease cannot revive old parsed calltargets: their token
            // is distinct from the newly verified source lease.
            try (var next = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
                assertThrows(PolyglotException.class, () -> retained.execute());
            }
        }
    }

    @Test
    void ordinaryPolyglotSourceWithoutTrustedScopeRemainsFailClosed()
            throws Exception {
        Source guest = source(CODE, UNIT);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(guest));
        }
    }

    @Test
    void mismatchedSourceAndCodeUnitConsumeLeaseBeforeAnyRetry() {
        try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> OresLanguage.consumeVerifiedDopSource(
                            UNIT, CODE + " // changed"));
            assertTrue(failure.getMessage().contains("does not match"));
            assertThrows(IllegalStateException.class,
                    () -> OresLanguage.consumeVerifiedDopSource(UNIT, CODE));
        }
        try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
            assertThrows(IllegalStateException.class,
                    () -> OresLanguage.consumeVerifiedDopSource("other.atto", CODE));
            assertThrows(IllegalStateException.class,
                    () -> OresLanguage.consumeVerifiedDopSource(UNIT, CODE));
        }
    }

    @Test
    void runtimeScopeMustBeConsumedAndNeverReusedAfterClose() {
        Object token;
        try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
            assertThrows(SecurityException.class,
                    () -> OresLanguage.requireLiveVerifiedDopLease(new Object()));
            token = OresLanguage.consumeVerifiedDopSource(UNIT, CODE);
            OresLanguage.requireLiveVerifiedDopLease(token);
            assertThrows(IllegalStateException.class,
                    () -> OresLanguage.consumeVerifiedDopSource(UNIT, CODE));
        }
        final Object expired = token;
        assertThrows(SecurityException.class,
                () -> OresLanguage.requireLiveVerifiedDopLease(expired));
    }

    @Test
    void leaseCannotBeClosedFromAnotherThread() throws Exception {
        var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = Thread.ofPlatform().start(() -> {
            try {
                scope.close();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        other.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        scope.close();
    }

    @Test
    void privateLeaseTokenCannotAuthorizeAnotherThread() throws Exception {
        try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
            Object token = OresLanguage.consumeVerifiedDopSource(UNIT, CODE);
            OresLanguage.requireLiveVerifiedDopLease(token);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = Thread.ofPlatform().start(() -> {
                try {
                    OresLanguage.requireLiveVerifiedDopLease(token);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            other.join();
            assertInstanceOf(SecurityException.class, failure.get());
            // A foreign attempt must not consume or steal the owning scope.
            OresLanguage.requireLiveVerifiedDopLease(token);
        }
    }

    @Test
    void overlappingCheckedProgramAndDopScopesAreDenied() {
        String regular = "pub fnc main(): void { return; }";
        Ast.Program parsed = Parser.parse(regular);
        try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
            assertThrows(IllegalStateException.class, () ->
                    OresLanguage.openVerifiedDopSource(UNIT, CODE));
            assertThrows(IllegalStateException.class, () ->
                    OresLanguage.openPrecheckedProgram(
                            "regular.ores", regular, parsed));
        }
        try (var scope = OresLanguage.openPrecheckedProgram(
                "regular.ores", regular, parsed)) {
            assertThrows(IllegalStateException.class, () ->
                    OresLanguage.openVerifiedDopSource(UNIT, CODE));
        }
    }

    @Test
    void thirtyTwoFreshSourceLeasesAgreeWithIndependentSwapDeleteModel()
            throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            for (int seed = 1; seed <= 32; seed++) {
                long expected = -((long) seed);
                String name = "verified-dop-iteration-" + seed + ".atto";
                String code = CODE.replace(
                        "rows.push(11, 100);\n    rows.set(0, value, 42);\n"
                      + "    return rows.get(0, value);",
                        "rows.push(11, 100);\n    rows.push(" + expected + ", 7);\n"
                      + "    rows.erase_swap(0);\n    return rows.get(0, value);");
                assertTrue(code.contains("rows.erase_swap(0)"),
                        "test mutation did not replace source for seed " + seed);
                var guest = Source.newBuilder(OresLanguage.ID, code, name)
                        .mimeType(OresLanguage.MIME_TYPE)
                        .cached(false)
                        .build();
                try (var scope = OresLanguage.openVerifiedDopSource(name, code)) {
                    assertEquals(expected, context.eval(guest).asLong(),
                            "synchronous OresLanguage execution seed=" + seed);
                }
                assertThrows(PolyglotException.class, () -> context.eval(guest),
                        "unscoped execution must be denied seed=" + seed);
            }
        }
    }

    @Test
    void twoHundredFiftySixSourceProgramsReplayCachedGuestCallsWithFreshOwners()
            throws Exception {
        final String template = """
                define inverted struct Row as
                    let value: int;
                    token: int;
                end
                fnc main(): int {
                    let rows = InvertedArray<Row>.with_capacity(2);
                    rows.push(17, 100);
                    rows.push(__VALUE__, 7);
                    rows.erase_swap(0);
                    return rows.get(0, value);
                }
                """;
        int guestCalls = 0;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            for (int seed = 1; seed <= 256; seed++) {
                long expected = switch (seed % 8) {
                    case 0 -> Long.MIN_VALUE;
                    case 1 -> Long.MAX_VALUE;
                    case 2 -> 0L;
                    case 3 -> -((long) seed);
                    default -> ((long) seed) * 37L;
                };
                String sourceText = template.replace(
                        "__VALUE__", Long.toString(expected));
                String identity = "dop-cached-seed-" + seed + ".atto";
                var guest = Source.newBuilder(OresLanguage.ID, sourceText, identity)
                        .mimeType(OresLanguage.MIME_TYPE).cached(true).build();

                long independentHost =
                        OresCompiler.compileDopInvertedScalarI64(sourceText)
                                .executeHostReference();
                assertEquals(expected, independentHost, "host model seed=" + seed);

                try (var permit =
                        OresLanguage.openVerifiedDopSource(identity, sourceText)) {
                    for (int replay = 0; replay < 4; replay++) {
                        assertEquals(expected, context.eval(guest).asLong(),
                                "parsed/typed guest seed=" + seed + " replay=" + replay);
                        guestCalls++;
                    }
                }
                assertThrows(PolyglotException.class, () -> context.eval(guest),
                        "cached target must lose scope after seed=" + seed);
            }
        }
        assertEquals(1024, guestCalls);
    }

    @Test
    void actualPolyglotParseMismatchCannotClaimAnotherUnitIdentity() throws Exception {
        Source wrongName = source(CODE, "wrong-" + UNIT);
        Source correctName = source(CODE, UNIT);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
                assertThrows(PolyglotException.class,
                        () -> context.eval(wrongName));
                // The failed match permanently consumes this particular token.
                assertThrows(PolyglotException.class,
                        () -> context.eval(correctName));
            }
            try (var newLease = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
                assertEquals(42L, context.eval(correctName).asLong(),
                        "a distinct newly compiled lease may still admit exact source");
            }
        }
    }

    @Test
    void invalidSourceFailsBeforeCreatingAnyAuthority() {
        String invalid = CODE.replace("with_capacity(2)", "with_capacity(257)");
        assertThrows(IllegalArgumentException.class, () ->
                OresLanguage.openVerifiedDopSource(UNIT, invalid));
        assertThrows(SecurityException.class, () ->
                OresLanguage.requireLiveVerifiedDopLease(new Object()));
        try (var scope = OresLanguage.openVerifiedDopSource(UNIT, CODE)) {
            assertNotNull(OresLanguage.consumeVerifiedDopSource(UNIT, CODE));
        }
    }
}
