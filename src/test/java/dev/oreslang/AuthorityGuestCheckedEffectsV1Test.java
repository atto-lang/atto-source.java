package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Stacked on Java checked-effects PR #1290; admission only, not runtime unwind. */
final class AuthorityGuestCheckedEffectsV1Test {
    private record Fixture(String id, String source, String blobSha, boolean accept) {}

    private static final List<Fixture> CASES = List.of(
            new Fixture("checked-effect-propagation-accepted", "fnc risky(): int throws string raises bool panics int { return 1; }\nfnc wrapper(): int throws string raises bool panics int { return risky(); }\n", "89f0c048f738c6caa2a0425d398ba5bf77459ba6", true),
            new Fixture("checked-effect-missing-rejected", "fnc risky(): int throws string { return 1; }\nfnc wrapper(): int { return risky(); }\n", "a25cc8581afcc3ab0fe8ec9147d07d32bc649347", false),
            new Fixture("checked-effect-crosscategory-rejected", "fnc risky(): int throws string { return 1; }\nfnc wrapper(): int raises string { return risky(); }\n", "ce580d59504aaa1d085e7978ab598f24636b8860", false),
            new Fixture("checked-effect-duplicate-rejected", "fnc f(): int throws string throws int { return 1; }\n", "279c86b17bc59649d7d58bf26f1a838d826d9370", false)
    );

    private static String gitBlobSha(String source) throws Exception {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha1.digest(bytes));
    }

    @Test
    void pinnedSharedEffectSourcesMatchCanonicalGitBlobs() throws Exception {
        assertEquals(4, CASES.size());
        for (Fixture fixture : CASES) {
            assertEquals(fixture.blobSha(), gitBlobSha(fixture.source()), fixture.id());
        }
    }

    @Test
    void transitiveThrowsRaisesPanicsMustBeCoveredSeparately() {
        for (Fixture fixture : CASES) {
            if (fixture.id().equals("checked-effect-duplicate-rejected")) continue;
            if (fixture.accept()) {
                assertDoesNotThrow(
                        () -> TypeChecker.checkTypes(Parser.parse(fixture.source())), fixture.id());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.checkTypes(Parser.parse(fixture.source())), fixture.id());
            }
        }
    }

    @Test
    void duplicateEffectDeclarationIsRejectedByParser() {
        Fixture duplicated = CASES.get(3);
        assertEquals("checked-effect-duplicate-rejected", duplicated.id());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(duplicated.source()), duplicated.id());
    }
}
