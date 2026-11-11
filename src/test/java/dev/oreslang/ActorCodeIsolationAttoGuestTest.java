package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical .atto source-to-guest boundary tests. The positive test invokes
 * the linked compiler and guest VM, not a Java actor model. This does not
 * certify OS .text mapping deduplication, AOT, or a cross-backend result.
 */
final class ActorCodeIsolationAttoGuestTest {
    @TempDir Path temp;

    private Path fixture(String name) throws Exception {
        try (InputStream input = ActorCodeIsolationAttoGuestTest.class
                .getResourceAsStream("/atto/actor-code-isolation/" + name)) {
            assertNotNull(input, "missing checked-in .atto fixture " + name);
            Path target = temp.resolve(name);
            Files.copy(input, target);
            return target;
        }
    }

    @Test
    void sharedImplementationDoesNotShareActorStateAndRootUsesSameRoutine()
            throws Exception {
        Path entry = fixture("shared-implementation-private-state.atto");
        var validated = LinkedProgramRunner.validate(entry, Map.of());
        assertEquals(1, validated.units().size(),
                "the actual AttoLang source must pass linked compiler admission");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        assertEquals("3|7|11|42", out.toString(StandardCharsets.UTF_8),
                "shared Counter instances and isolated actor must retain private state");
    }

    @Test
    void ownedBoxIsNotAnImplicitActorMailSharedObject() throws Exception {
        String source = Files.readString(fixture("forbidden-object-mail.atto"));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("Box")
                        && rejected.getMessage().contains("actor boundary"),
                rejected::getMessage);
    }

    @Test
    void privateActorCannotGainSharedMemoryAuthorityThroughCommonHelper()
            throws Exception {
        String source = Files.readString(fixture("forbidden-private-shared-memory.atto"));
        SecurityException rejected = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(
                        Parser.parse(source), IsolatePolicy.developer()));
        assertTrue(rejected.getMessage().contains("SHARED_MEMORY"),
                rejected::getMessage);
    }

    @Test
    void callableCodeIdentityNeverPermitsClosureTransportAsActorMail()
            throws Exception {
        String source = Files.readString(fixture("forbidden-callable-mail.atto"));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("sendable")
                        || rejected.getMessage().contains("actor-boundary"),
                rejected::getMessage);
    }

    @Test
    void parentOrSupervisorCannotReachPrivateFieldsViaActorRef() throws Exception {
        String source = Files.readString(
                fixture("forbidden-parent-child-object-read.atto"));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("unknown ActorRef member 'total'"),
                rejected::getMessage);
    }
}
