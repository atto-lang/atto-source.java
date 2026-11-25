package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compulsory manifest gate for declaration-only actors. Static conformance
 * proofs must precede compilation, but may NEVER become runtime authority
 * before actor-method/critical-loan MIR lowering is proven.
 */
final class DeclaredActorCompilerManifestGateTest {
    @TempDir Path root;
    private static final String NS = "namespace foo;\n";
    private static final String CONTRACT = NS +
            "define pub contract ActorXYZ for actor as mtd bar(int x): int; end";
    private static final String ACTOR = NS + "declare pub actor Foo implements ActorXYZ;";
    private static final String METHOD = NS +
            "pub mtd (Foo f) bar(int x): int { return x; }";
    private static final String STATE = NS +
            "define state on actor Foo as { let int x : 5; }";

    private void write(String relative, String value) throws IOException {
        var path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private OresProjectConfig setup() throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "demo"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        write("src/foo/contract.atto", CONTRACT);
        write("src/foo/actor.atto", ACTOR);
        write("src/foo/method.atto", METHOD);
        write("src/foo/state.atto", STATE);
        return OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of());
    }

    @Test
    void mixedNamespaceHeaderSpellingsShareManifestActorWitnessAndKeepCodegenClosed()
            throws IOException {
        var config = setup();
        // A source header spelling must not change package / nominal authority.
        write("src/foo/actor.atto", ACTOR.replace("namespace foo;", "use namespace foo;"));
        write("src/foo/method.atto", METHOD.replace("namespace foo;", "use namespace foo;"));
        var audit = new PackageNamespaceBuild(config).auditDeclaredActors();
        var key = new ActorContractWitness.ActorKey("demo@0.1.0", "foo", "Foo");
        var actor = audit.contractWitness().actors().get(key);
        assertNotNull(actor);
        assertEquals("src/foo/actor.atto", actor.actorUnit());
        assertEquals("src/foo/method.atto", actor.methods().getFirst().methodUnit());

        // Static contract admission must not become executable actor authority.
        var unsupported = assertThrows(IllegalArgumentException.class,
                () -> new PackageNamespaceBuild(config).build());
        assertTrue(unsupported.getMessage().contains("DECLARED_ACTOR_CODEGEN_UNSUPPORTED"),
                unsupported.getMessage());
    }

    @Test
    void actorTargetContractParsingIsDistinctFromModuleAndOrdinaryInterface() {
        Ast.InterfaceDecl actor = assertInstanceOf(Ast.InterfaceDecl.class,
                Parser.parse(CONTRACT).modules().getFirst().declarations().getFirst());
        assertTrue(actor.actorContract());
        assertFalse(actor.moduleContract());
        assertEquals("bar", assertInstanceOf(Ast.InterfaceFunctionDecl.class,
                actor.members().getFirst()).name());

        Ast.InterfaceDecl regular = assertInstanceOf(Ast.InterfaceDecl.class,
                Parser.parse(NS + "define pub interface ActorXYZ as "
                        + "fnc bar(int x): int; end")
                        .modules().getFirst().declarations().getFirst());
        assertFalse(regular.actorContract());
        assertFalse(regular.moduleContract());

        Ast.InterfaceDecl module = assertInstanceOf(Ast.InterfaceDecl.class,
                Parser.parse(NS + "define pub contract ActorXYZ as actor Foo; end")
                        .modules().getFirst().declarations().getFirst());
        assertTrue(module.moduleContract());
        assertFalse(module.actorContract());
    }

    @Test
    void actorTargetContractRejectsOrdinaryFunctionAndStateMembers() {
        var fn = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(NS + "define contract ActorXYZ for actor as "
                        + "fnc bar(int x): int; end"));
        assertTrue(fn.getMessage().contains("require 'mtd'"), fn.getMessage());

        var field = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(NS + "define contract ActorXYZ for actor as int x; end"));
        assertTrue(field.getMessage().contains("only mtd signatures"), field.getMessage());
    }

    @Test
    void verifiedManifestPackageProvidesActorContractAndExternalMethodIndex()
            throws IOException {
        var build = new PackageNamespaceBuild(setup());
        var audit = build.auditDeclaredActors();
        var key = new ActorContractWitness.ActorKey("demo@0.1.0", "foo", "Foo");
        var actor = audit.contractWitness().actors().get(key);
        assertNotNull(actor);
        assertEquals("src/foo/actor.atto", actor.actorUnit());
        assertEquals("src/foo/state.atto", actor.stateUnit());
        assertEquals("src/foo/contract.atto", actor.methods().getFirst().contractUnit());
        assertEquals("src/foo/method.atto", actor.methods().getFirst().methodUnit());

        var indexKey = new ActorMethodIndex.ActorKey("demo@0.1.0", "foo", "Foo");
        assertEquals(actor.actorUnit(), audit.actorMethods().declaringUnit(indexKey));
        var slot = audit.actorMethods().method(
                new ActorMethodIndex.MethodKey(indexKey, "bar", 1));
        assertNotNull(slot);
        assertEquals("src/foo/method.atto", slot.declaringUnit());
        assertTrue(slot.declarationEligible());
        assertEquals(4, audit.sourceSha256().size());
        for (String hash : audit.sourceSha256().values()) assertEquals(64, hash.length());
        assertEquals(64, audit.manifestSha256().length());
    }

    @Test
    void manifestCompilationChecksWitnessButNeverEmitsExecutableActor() throws IOException {
        var build = new PackageNamespaceBuild(setup());
        var error = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(error.getMessage().contains("DECLARED_ACTOR_CODEGEN_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void missingImplementationFailsBeforeUnsupportedCodegen() throws IOException {
        var build = new PackageNamespaceBuild(setup());
        Files.delete(root.resolve("src/foo/method.atto"));
        var error = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(error.getMessage().contains("missing actor contract implementation"),
                error.getMessage());
        assertFalse(error.getMessage().contains("CODEGEN_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void mismatchFailsBeforeCodegenAndNeverMatchesByArityAlone() throws IOException {
        var build = new PackageNamespaceBuild(setup());
        write("src/foo/method.atto", NS +
                "pub mtd (Foo f) bar(bool x): int { return 0; }");
        var error = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(error.getMessage().contains("signature mismatch"),
                error.getMessage());
    }

    @Test
    void changedContractInvalidatesStaticWitnessEvenAfterSuccessfulAudit() throws IOException {
        var build = new PackageNamespaceBuild(setup());
        var verified = build.auditDeclaredActors();
        assertEquals(1, verified.contractWitness().actors().size());
        write("src/foo/contract.atto", NS +
                "define pub contract ActorXYZ for actor as mtd missing(int x): int; end");
        var error = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(error.getMessage().contains("missing actor contract implementation"),
                error.getMessage());
    }

    @Test
    void duplicateNominalActorAcrossFilesFailsBeforeCompilation() throws IOException {
        var build = new PackageNamespaceBuild(setup());
        write("src/foo/actor2.atto", ACTOR);
        var error = assertThrows(IllegalArgumentException.class, build::auditDeclaredActors);
        assertTrue(error.getMessage().contains("duplicate actor/type identity"),
                error.getMessage());
    }

    @Test
    void inMemoryIncrementalCompilerCannotSelfAttestCallerSelectedSources() {
        var raw = new IncrementalCompiler();
        var sources = new LinkedHashMap<String, String>();
        sources.put("src/foo/contract.atto", CONTRACT);
        sources.put("src/foo/actor.atto", ACTOR);
        sources.put("src/foo/method.atto", METHOD);
        sources.put("src/foo/state.atto", STATE);
        var error = assertThrows(IllegalArgumentException.class,
                () -> raw.compile(sources));
        assertTrue(error.getMessage().contains("DECLARED_ACTOR_MANIFEST_GATE_REQUIRED"),
                error.getMessage());
    }

    @Test
    void rawTypeCheckerCannotConvertStaticDeclarationIntoActor() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(ACTOR)));
        assertTrue(error.getMessage().contains("DECLARED_ACTOR_LINKING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void actorContractCannotBeImplementedByAnOrdinaryClass() {
        var source = NS + "define pub contract ActorXYZ for actor as "
                + "mtd bar(int x): int; end\n"
                + "define pub class Foo implements ActorXYZ as "
                + "pub bar(int x): int { return x; } end";
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("requires non-generic nominal actor"),
                error.getMessage());
    }

    @Test
    void actorContractDisallowsRuntimeControlHookWitness() {
        var source = NS + "define pub contract ActorXYZ for actor as "
                + "mtd handle_state(): void; end\n"
                + "declare pub actor Foo implements ActorXYZ;\n"
                + "pub mtd (Foo f) bar(): void { return; }";
        var sourceSet = Map.of("src/foo/actor.atto", Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorContractWitness.linkSourceSet("demo@1.0.0", sourceSet));
        assertTrue(error.getMessage().contains("reserved hook"),
                error.getMessage());
    }

    @Test
    void fullInlineActorRemainsLegalUnderItsOwnContract() {
        var source = NS +
                "define pub contract ActorXYZ for actor as mtd bar(int x): int; end\n"
                + "define pub actor Foo implements ActorXYZ as\n"
                + "  pub bar(int x): int { return x; }\nend";
        var checked = TypeChecker.check(Parser.parse(source));
        assertNotNull(checked);
    }
}
