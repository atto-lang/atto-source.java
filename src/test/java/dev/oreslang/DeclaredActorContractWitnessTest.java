package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.ManifestActorContractBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Static-only actor implements witnesses: exact source identity and signatures. */
final class DeclaredActorContractWitnessTest {
    @TempDir Path root;

    private static final String NS = "namespace foo;\n";
    private static final String DECL = NS + "declare pub actor Foo implements ActorXYZ;";
    private static final String CONTRACT = NS
            + "define pub interface ActorXYZ as fnc bar(int x): int; end";
    private static final String METHOD = NS
            + "pub mtd (Foo f) bar(int x): int { return x; }";
    private static final String STATE = NS
            + "define state on actor Foo as { let int x : 5; }";

    private static Map<String, Ast.Program> units(String declaration,
                                                   String contract, String method) {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/foo/actor.atto", Parser.parse(declaration));
        units.put("src/foo/contract.atto", Parser.parse(contract));
        units.put("src/foo/method.atto", Parser.parse(method));
        return units;
    }

    private static ActorContractWitness.Result verified(
            String declaration, String contract, String method) {
        return ActorContractWitness.linkSourceSet("demo@1.0.0",
                units(declaration, contract, method));
    }

    private static void reject(String declaration, String contract,
                               String method, String expected) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> verified(declaration, contract, method));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    @Test
    void exactContractWitnessHasDistinctActorContractMethodOrigins() {
        var result = verified(DECL, CONTRACT, METHOD);
        var key = new ActorContractWitness.ActorKey("demo@1.0.0", "foo", "Foo");
        var witness = result.actors().get(key);
        assertNotNull(witness);
        assertEquals("src/foo/actor.atto", witness.actorUnit());
        assertNull(witness.stateUnit());
        assertEquals(1, witness.methods().size());
        assertEquals("src/foo/contract.atto", witness.methods().getFirst().contractUnit());
        assertEquals("src/foo/method.atto", witness.methods().getFirst().methodUnit());
        assertEquals("ActorXYZ", witness.methods().getFirst().contract());
    }

    @Test
    void ownedStateIsRecordedInActorProofNotAsGlobalValue() {
        var source = units(DECL, CONTRACT, METHOD);
        source.put("src/foo/state.atto", Parser.parse(STATE));
        var proof = ActorContractWitness.linkSourceSet("demo@1.0.0", source)
                .actors().get(new ActorContractWitness.ActorKey(
                        "demo@1.0.0", "foo", "Foo"));
        assertEquals("src/foo/state.atto", proof.stateUnit());
    }

    @Test
    void physicalFourFileCorpusProvesExactlyOneDeclaredActor() throws IOException {
        var units = new LinkedHashMap<String, Ast.Program>();
        for (String file : new String[] {
                "actor.atto", "contract.atto", "methods.atto", "state.atto"}) {
            var contents = Files.readString(
                    Path.of("contracts", "declared-actor-witness-v1", file));
            units.put("src/foo/" + file, Parser.parse(contents));
        }
        var receipt = ActorContractWitness.linkSourceSet("demo@1.0.0", units);
        assertEquals(1, receipt.actors().size());
        assertEquals(1, receipt.boundMethods().size());
        assertEquals(1, receipt.actors().values().iterator().next().methods().size());
        assertEquals("src/foo/state.atto",
                receipt.actors().values().iterator().next().stateUnit());
    }

    @Test
    void missingRequiredMethodFailsClosed() {
        reject(DECL, CONTRACT,
                NS + "pub mtd (Foo f) other(int x): int { return x; }",
                "missing actor contract implementation");
    }

    @Test
    void wrongReturnTypeFailsClosed() {
        reject(DECL, CONTRACT,
                NS + "pub mtd (Foo f) bar(int x): bool { return true; }",
                "signature mismatch");
    }

    @Test
    void wrongParameterTypeFailsClosed() {
        reject(DECL, CONTRACT,
                NS + "pub mtd (Foo f) bar(bool x): int { return 4; }",
                "signature mismatch");
    }

    @Test
    void parameterMutabilityIsPartOfTheContractWitness() {
        reject(DECL,
                NS + "define pub interface ActorXYZ as "
                        + "fnc bar(int mut x): int; end",
                METHOD,
                "signature mismatch");
    }

    @Test
    void structuralOrReferenceReturnTypeCannotEscapeAsScalarProof() {
        reject(DECL, CONTRACT,
                NS + "pub mtd (Foo f) bar(int x): Foo { return f; }",
                "requires exact scalar");
    }

    @Test
    void privateImplementationCannotWitnessPublicContract() {
        reject(DECL, CONTRACT,
                NS + "mtd (Foo f) bar(int x): int { return x; }",
                "must be public");
    }

    @Test
    void missingNominalInterfaceIsNotAnImplicitContract() {
        reject(DECL, NS + "define pub interface Other as fnc bar(int x): int; end",
                METHOD, "missing nominal actor interface contract");
    }

    @Test
    void declaredActorCannotExportPrivateInterface() {
        reject(DECL, NS + "define interface ActorXYZ as fnc bar(int x): int; end",
                METHOD, "public actor exposes private actor contract");
    }

    @Test
    void duplicateContractMethodSelectorsCannotBeDisambiguatedByTypes() {
        reject(DECL,
                NS + "define pub interface ActorXYZ as fnc bar(int x): int;"
                        + " fnc bar(bool x): int; end",
                METHOD, "ambiguous duplicate actor contract slot");
    }

    @Test
    void interfaceFieldsCannotBeMadePublicByMethodWitness() {
        reject(DECL, NS + "define pub interface ActorXYZ as int x; end",
                METHOD, "method-only members");
    }

    @Test
    void moduleContractsAreNotActorInstanceContracts() {
        reject(DECL,
                NS + "define pub contract ActorXYZ as actor Foo; end",
                METHOD, "missing nominal actor interface contract");
    }

    @Test
    void borrowLikeAndGenericPayloadContractsNeedRealOwnershipProof() {
        reject(DECL, NS
                + "define pub interface ActorXYZ as fnc bar(Map<int, int> x): int; end",
                METHOD, "requires exact scalar");
    }

    @Test
    void noMatchingNamespaceCannotContributeAnImplementation() {
        reject(DECL, CONTRACT,
                "namespace foreign;\npub mtd (Foo f) bar(int x): int { return x; }",
                "has no nominal actor");
    }

    @Test
    void declaredActorWithoutAnyConstituentIsNotComplete() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ActorContractWitness.linkSourceSet("demo@1.0.0",
                        Map.of("src/foo/actor.atto",
                                Parser.parse(NS + "declare actor Foo;"))));
        assertTrue(failure.getMessage().contains("no implementation constituents"),
                failure.getMessage());
    }

    @Test
    void untrustedImportCannotSupplyAContractOrActorMethod() {
        reject(DECL,
                "namespace foo;\nimport interface ActorXYZ from \"foreign.atto\";"
                        + "define pub interface ActorXYZ as fnc bar(int x): int; end",
                METHOD, "forbids imports");
    }

    private void write(String relative, String value) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private OresProjectConfig manifest(String name) throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "%s"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """.formatted(name));
        return OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of());
    }

    private void writeValidSources() throws IOException {
        write("src/foo/actor.atto", DECL);
        write("src/foo/contract.atto", CONTRACT);
        write("src/foo/method.atto", METHOD);
        write("src/foo/state.atto", STATE);
    }

    @Test
    void manifestBuildFindsAllConstituentsAndHashesExactSourceFiles() throws IOException {
        writeValidSources();
        var build = new ManifestActorContractBuild(manifest("demo"));
        var receipt = build.build();
        assertEquals("demo@0.1.0", build.packageIdentity());
        assertEquals(4, receipt.sourceSha256().size());
        assertEquals(64, receipt.manifestSha256().length());
        assertEquals(1, receipt.staticWitness().actors().size());
        var proof = receipt.staticWitness().actors().values().iterator().next();
        assertEquals("src/foo/actor.atto", proof.actorUnit());
        assertEquals("src/foo/state.atto", proof.stateUnit());
        assertEquals("src/foo/method.atto", proof.methods().getFirst().methodUnit());
    }

    @Test
    void changedManifestInvalidatesExistingBuildInstance() throws IOException {
        writeValidSources();
        var build = new ManifestActorContractBuild(manifest("demo"));
        build.build();
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "other"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("manifest changed"),
                failure.getMessage());
    }

    @Test
    void deletedMethodUnitInvalidatesPackageWitness() throws IOException {
        writeValidSources();
        var build = new ManifestActorContractBuild(manifest("demo"));
        build.build();
        Files.delete(root.resolve("src/foo/method.atto"));
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("missing actor contract implementation"),
                failure.getMessage());
    }

    @Test
    void emptyOrMissingSourcesCannotCreateActorWitness() throws IOException {
        var build = new ManifestActorContractBuild(manifest("demo"));
        Files.createDirectories(root.resolve("src"));
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("has no source units"),
                failure.getMessage());
    }

    @Test
    void foreignNamespaceOnlyCannotClaimActorContract() throws IOException {
        writeValidSources();
        write("src/foo/extra.atto",
                "namespace wrong;\ndeclare actor Foo implements ActorXYZ;");
        var build = new ManifestActorContractBuild(manifest("demo"));
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("missing nominal actor interface contract"),
                failure.getMessage());
    }

    @Test
    void manifestMustContainAtLeastOneDeclaredActor() throws IOException {
        write("src/foo/contract.atto", CONTRACT);
        var build = new ManifestActorContractBuild(manifest("demo"));
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("no declaration-only actors"),
                failure.getMessage());
    }

    @Test
    void invalidUtf8CannotBeNormalizedIntoSourceIdentity() throws IOException {
        writeValidSources();
        var build = new ManifestActorContractBuild(manifest("demo"));
        Files.write(root.resolve("src/foo/extra.atto"),
                new byte[] {(byte) 0xC3, (byte) 0x28});
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("valid UTF-8"),
                failure.getMessage());
    }

    @Test
    void symlinkedSourceNeverBecomesNominalProof() throws IOException {
        writeValidSources();
        var build = new ManifestActorContractBuild(manifest("demo"));
        Path link = root.resolve("src/foo/other.atto");
        try {
            Files.createSymbolicLink(link, root.resolve("src/foo/method.atto"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException denied) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink unavailable: " + denied);
        }
        var failure = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(failure.getMessage().contains("symlink"),
                failure.getMessage());
    }
}
