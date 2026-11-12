package dev.oreslang;

import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.ExternalActorScalarBodyCheck;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical multi-file, manifest-scoped contracted mtd BODY analysis.
 * This opt-in proof subset is NOT an actor dispatcher or general TypeChecker.
 */
final class ExternalActorScalarBodyCheckTest {
    @TempDir Path root;

    private static final String NS = "use namespace foo;\n";
    private static final String ACTOR = NS
            + "declare pub actor Counter implements CounterApi;";
    private static final String CONTRACT = NS
            + "define pub contract CounterApi for actor as "
            + "mtd identity(int input): int; "
            + "mtd ready(): bool; "
            + "mtd word(): string; "
            + "mtd noop(): void; end";
    private static final String IDENTITY = NS
            + "pub mtd (Counter c) identity(int input): int { return input; }";
    private static final String READY = NS
            + "pub mtd (Counter c) ready(): bool { return true; }";
    private static final String WORD = NS
            + "pub mtd (Counter c) word(): string { return \"hello\"; }";
    private static final String NOOP = NS
            + "pub mtd (Counter c) noop(): void { return; }";

    private void write(String name, String source) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private PackageNamespaceBuild setup() throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "scalar"
                version = "1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        write("src/one/actor.atto", ACTOR);
        write("src/contracts/contract.atto", CONTRACT);
        write("src/impl/identity.atto", IDENTITY);
        write("src/impl/ready.atto", READY);
        write("src/impl/word.ores", WORD);
        write("src/impl/noop.atto", NOOP);
        return new PackageNamespaceBuild(OresProjectConfig.load(
                root.resolve(".atto.pkg.toml"), Map.of()));
    }

    private static ActorMethodIndex.MethodKey slot(String actor, String name, int arity) {
        return new ActorMethodIndex.MethodKey(
                new ActorMethodIndex.ActorKey("scalar@1.0", "foo", actor), name, arity);
    }

    private static void rejected(IllegalArgumentException failure, String fragment) {
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }

    @Test
    void sixPhysicalFilesHaveFourScalarCheckedContractMethodBodies() throws IOException {
        var build = setup();
        var result = build.auditScalarContractBodies();
        assertEquals(6, result.actorAudit().sourceSha256().size());
        assertEquals(1, result.actorAudit().descriptors().size());
        assertEquals(4, result.verifiedBodies().size());
        assertEquals(ExternalActorScalarBodyCheck.ReturnKind.PARAMETER,
                result.verifiedBodies().get(slot("Counter", "identity", 1)).returnKind());
        assertEquals(ExternalActorScalarBodyCheck.ReturnKind.LITERAL,
                result.verifiedBodies().get(slot("Counter", "ready", 0)).returnKind());
        assertEquals(ExternalActorScalarBodyCheck.ReturnKind.LITERAL,
                result.verifiedBodies().get(slot("Counter", "word", 0)).returnKind());
        assertEquals(ExternalActorScalarBodyCheck.ReturnKind.VOID,
                result.verifiedBodies().get(slot("Counter", "noop", 0)).returnKind());
        assertEquals("src/impl/identity.atto",
                result.verifiedBodies().get(slot("Counter", "identity", 1))
                        .declaringUnit());
        assertEquals("src/contracts/contract.atto",
                result.verifiedBodies().get(slot("Counter", "identity", 1))
                        .contractUnit());
        assertEquals("src/one/actor.atto",
                result.verifiedBodies().get(slot("Counter", "identity", 1))
                        .actorUnit());
    }

    @Test
    void scalarBodiesAreAlwaysNonExecutableInTheCurrentPackageBuild()
            throws IOException {
        var build = setup();
        assertEquals(4, build.auditScalarContractBodies().verifiedBodies().size());
        rejected(assertThrows(IllegalArgumentException.class, build::build),
                "DECLARED_ACTOR_CODEGEN_UNSUPPORTED");
    }

    @Test
    void literalWithWrongTypeIsRejectedEvenIfSignatureStillMatches()
            throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return true; }");
        assertEquals(1, build.auditDeclaredActors().descriptors().size(),
                "static contract witness alone is not a body type check");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH");
    }

    @Test
    void wrongParameterTypeDoesNotBecomeAPureReturnProof() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return missing; }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH");
    }

    @Test
    void actorReceiverCannotEscapeAsDeclaredIntReturn() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return c; }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "actor receiver cannot be a scalar result");
    }

    @Test
    void directStateFieldReadNeedsSealedCriticalLoanLowering() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return c.count; }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED");
    }

    @Test
    void invocationInsideExternalMethodNeedsVerifiedCallLowering() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return helper(); }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED");
    }

    @Test
    void uncheckedIntegerArithmeticDoesNotGetScalarProof() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return input + 1; }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED");
    }

    @Test
    void returnValueFromVoidMethodIsRejected() throws IOException {
        var build = setup();
        write("src/impl/noop.atto", NS
                + "pub mtd (Counter c) noop(): void { return 7; }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH");
    }

    @Test
    void noReturnInNonVoidMethodIsRejected() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED");
    }

    @Test
    void multipleStatementsNeedRealControlFlowChecking() throws IOException {
        var build = setup();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { "
                + "return input; return input; }");
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED");
    }

    @Test
    void requiredBodyDisappearanceInvalidatesAudit() throws IOException {
        var build = setup();
        assertEquals(4, build.auditScalarContractBodies().verifiedBodies().size());
        Files.delete(root.resolve("src/impl/identity.atto"));
        rejected(assertThrows(IllegalArgumentException.class,
                build::auditScalarContractBodies),
                "missing actor contract implementation");
    }

    @Test
    void bodyEditsRecomputeDescriptorSnapshotWithoutModifyingSignature()
            throws IOException {
        var build = setup();
        var before = build.auditScalarContractBodies();
        var previous = before.actorAudit().descriptors().values().iterator()
                .next().descriptorSha256();
        write("src/impl/identity.atto", NS
                + "pub mtd (Counter c) identity(int input): int { return 42; }");
        var after = build.auditScalarContractBodies();
        assertEquals(ExternalActorScalarBodyCheck.ReturnKind.LITERAL,
                after.verifiedBodies().get(slot("Counter", "identity", 1))
                        .returnKind());
        assertNotEquals(previous, after.actorAudit().descriptors().values()
                .iterator().next().descriptorSha256());
        assertEquals(before.actorAudit().descriptors().values().iterator()
                .next().requiredMethods(),
                after.actorAudit().descriptors().values().iterator()
                .next().requiredMethods());
    }

    @Test
    void verifiedBodyResultCannotBeMutatedIntoNewActorAuthority()
            throws IOException {
        var audit = setup().auditScalarContractBodies();
        assertThrows(UnsupportedOperationException.class,
                () -> audit.verifiedBodies().clear());
        assertEquals(List.of("identity", "noop", "ready", "word"),
                audit.verifiedBodies().keySet().stream().map(
                        ActorMethodIndex.MethodKey::methodName).sorted().toList());
    }

    @Test
    void sameNamespaceTwoActorsWithOverlappingSelectorsStayIndependent()
            throws IOException {
        var build = setup();
        write("src/other/actor.atto", NS
                + "declare pub actor Logger implements LoggerApi;");
        write("src/other/contract.atto", NS
                + "define pub contract LoggerApi for actor as "
                + "mtd ready(): bool; end");
        write("src/other/ready.atto", NS
                + "pub mtd (Logger l) ready(): bool { return false; }");
        var audit = build.auditScalarContractBodies();
        assertEquals(5, audit.verifiedBodies().size());
        assertEquals(2, audit.actorAudit().descriptors().size());
        assertNotNull(audit.verifiedBodies().get(slot("Counter", "ready", 0)));
        assertNotNull(audit.verifiedBodies().get(slot("Logger", "ready", 0)));
    }
}
