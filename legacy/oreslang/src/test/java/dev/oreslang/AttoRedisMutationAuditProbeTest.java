package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** True Java/GraalVM compiler and Truffle guest regressions, not host-only. */
final class AttoRedisMutationAuditProbeTest {
    private static void rejects(String reason, String source) {
        IllegalArgumentException problem = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(problem.getMessage().contains(reason), problem::getMessage);
    }

    @Test
    void mutableMapMethodValuesCannotEscapeTheirOwner() {
        rejects("direct-call-only", "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); const f = db.set; f(\"key\",41); return 42; }");
        rejects("direct-call-only", "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); const f = db.delete; f(\"key\"); return 42; }");
    }

    @Test
    void closedMapDomainIsMandatoryEvenForUnreachableParameters() {
        rejects("proven String/Symbol/Bytes key", "pub fnc update(Map<int,int> db): void { db.set(4,41); return; } pub fnc main(): int { return 42; }");
        rejects("proven String/Symbol/Bytes key", "pub fnc update(Map<string,Map<string,int>> db): void { db.set(\"k\", ~map<string,int>()); return; } pub fnc main(): int { return 42; }");
    }

    @Test
    void immutableAndUnknownOwnerCannotMutateGuestMap() {
        rejects("directly owned writable let", "pub fnc main(): int { const Map<string,int> db = ~map<string,int>(); db.set(\"key\",41); return 42; }");
        rejects("directly owned writable let", "pub fnc main(): int { val Map<string,int> db = ~map<string,int>(); db.delete(\"key\"); return 42; }");
    }

    @Test
    void wrongArgumentAndMovedOwnerStillFailAtCompiler() {
        rejects("collection member argument", "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); db.delete(999); return 42; }");
        rejects("moved value", "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); rt take(db); db.set(\"key\",41); return 42; }");
    }

    @Test
    void validDeleteOfMissingAndOverwriteRemainExecutable() throws Exception {
        String source = "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); "
                + "if db.delete(\"missing\") then return 1; fi "
                + "db.set(\"key\",41); db.set(\"key\",42); return db[\"key\"]; }";
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source artifact = Source.newBuilder(OresLanguage.ID, source, "map-atomic-smoke.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(42, context.eval(artifact).asInt());
        }
    }

    @Test
    void optionalMapLookupEnforcesStaticKeyDomainAndNoEscapedCallable() {
        rejects("collection member argument",
                "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); "
                + "db.get_optional(41); return 42; }");
        rejects("direct-call-only",
                "pub fnc main(): int { let Map<string,int> db = ~map<string,int>(); "
                + "const f = db.get_optional; return 42; }");
        rejects("proven String/Symbol/Bytes key",
                "pub fnc probe(Map<int,int> db): bool { "
                + "return db.get_optional(42).is_some(); } "
                + "pub fnc main(): int { return 42; }");
    }
}
