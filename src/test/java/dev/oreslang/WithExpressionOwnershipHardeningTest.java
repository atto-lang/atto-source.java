package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Real .atto guest admission tests; no Java substitute for guest semantics. */
final class WithExpressionOwnershipHardeningTest {
    private static String guest(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/with", name),
                StandardCharsets.UTF_8);
    }

    private static String run(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest(name), name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException denied(String name) throws Exception {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest(name))), name);
    }

    @Test
    void certifiedMixedPrimitiveCopyKeepsOriginalAndReconstructs() throws Exception {
        assertEquals("45oldnew", run("class-copy-all-scalars.atto"));
    }

    @Test
    void nestedReadonlyCopyPreservesNestedImmutablePayload() throws Exception {
        assertEquals("773", run("record-copy-nested-readonly.atto"));
    }

    @Test
    void directClassCopyIsNotAnAliasedJvmHandle() throws Exception {
        assertEquals("22", run("class-direct-copy-preserves-original.atto"));
    }

    @Test
    void borrowedNominalReceiversNeverRegainOwnedCopyAuthority() throws Exception {
        assertNotNull(denied("class-borrowed-copy-rejected.atto").getMessage());
    }

    @Test
    void arithmeticUnwindInConsumedUpdateFailsClosed() throws Exception {
        IllegalArgumentException error = denied("class-divide-rhs-rejected.atto");
        assertTrue(error.getMessage().contains("effect-safe"), error::getMessage);
    }

    @Test
    void nestedNominalOwnershipRequiresExplicitFieldwiseContract() throws Exception {
        assertNotNull(denied("class-nested-owner-rejected.atto").getMessage());
    }

    @Test
    void selfIsTheOnlyImplicitInstanceReceiverNotThis() throws Exception {
        assertNotNull(denied("class-this-receiver-rejected.atto").getMessage());
    }
}
