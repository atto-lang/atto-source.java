package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorProtectedMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static MIR contract verification only; no backend/guest execution is
 * claimed. A checked plan deliberately remains unlowered in TypeChecker.
 */
final class VirtualActorProtectedMirV1Test {
    private static String checkedCart() {
        Path path = Path.of("contracts", "virtual-actor-mir-v1", "positive",
                        "cart_checked_contract.atto").toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(path), "canonical protected MIR fixture symlink");
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                "canonical protected MIR .atto fixture missing");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException io) {
            throw new java.io.UncheckedIOException(io);
        }
    }

    private static String negativeFixture(String name) {
        if (!List.of("unfenced_cart.atto", "escaped_state_cart.atto",
                "suspending_cart.atto").contains(name))
            throw new IllegalArgumentException("unreviewed negative virtual actor fixture");
        Path path = Path.of("contracts", "virtual-actor-mir-v1", "negative", name)
                .toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(path));
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException io) {
            throw new java.io.UncheckedIOException(io);
        }
    }

    private static Ast.VirtualActorDecl actor(String source) {
        Ast.Decl decl = Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        return assertInstanceOf(Ast.VirtualActorDecl.class, decl);
    }

    private static void rejected(String source, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(actor(source)));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_V1_REJECTED"),
                error::getMessage);
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void cartBuildsTypedImmutablePreloweringContract() {
        var mir = VirtualActorProtectedMirV1.analyze(actor(checkedCart()));
        assertEquals("Cart", mir.declaration());
        assertEquals(Ast.Visibility.PRIVATE, mir.visibility());
        assertEquals("quantity", mir.state().name());
        assertEquals("int", mir.state().type().name());
        assertEquals(0L, mir.state().initialValue());
        assertEquals(1, mir.methods().size());
        var method = mir.referenceType().method("add", 1);
        assertEquals("VirtualActorRef[Cart]", mir.referenceType().spelling());
        assertEquals("n", method.parameter());
        assertEquals("quantity", method.field());
        assertEquals("int", method.parameterType().name());
        assertEquals("void", method.returnType().name());
        assertEquals(method, mir.referenceType().requireCall(
                "add", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> mir.referenceType().requireCall(
                        "add", List.of(Ast.TypeRef.simple("string"))));
        assertThrows(IllegalArgumentException.class,
                () -> mir.referenceType().requireCall("add", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> mir.referenceType().requireCall(
                        "unknown", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> mir.referenceType().method("add", 2));
        assertThrows(UnsupportedOperationException.class,
                () -> mir.methods().clear(), "the plan is not a writable runtime authority");
    }

    @Test
    void typeCheckerStillDeniesExecutableVirtualActorAfterSuccessfulMirAnalysis() {
        var parsed = Parser.parse(checkedCart());
        assertNotNull(VirtualActorProtectedMirV1.analyze(actor(checkedCart())));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(parsed));
        assertTrue(failure.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }

    @Test
    void rejectsWrongScalarStateAndNonconstantInitializer() {
        rejected(checkedCart().replace("let int quantity", "let string quantity"),
                "checked i64");
        rejected(checkedCart().replace("quantity: 0", "quantity: n"),
                "constant i64");
        rejected(checkedCart().replace("state { let int quantity: 0; }",
                "state { let int quantity: 0; let int other: 0; }"),
                "exactly one");
    }

    @Test
    void rejectsUnfencedReadsAndWritesOrEscapingAliases() {
        rejected(checkedCart().replace("critical-write", "critical-read"),
                "token/provenance mismatch");
        rejected(checkedCart().replace("s.quantity = s.quantity + n;", "return s;"),
                "unrecognized protected method body");
        rejected(checkedCart().replace("s.quantity = s.quantity + n;",
                "s.quantity = s.quantity + n; s.quantity = 0;"),
                "unrecognized protected method body");
    }

    @Test
    void canonicalNegativeAttoContractsFailClosedBeforeAnyRuntimeLowering() {
        for (String fixture : List.of("unfenced_cart.atto",
                "escaped_state_cart.atto", "suspending_cart.atto")) {
            String source = negativeFixture(fixture);
            var err = assertThrows(IllegalArgumentException.class,
                    () -> VirtualActorProtectedMirV1.analyze(actor(source)));
            assertTrue(err.getMessage().contains("VIRTUAL_ACTOR_MIR_V1_REJECTED"),
                    () -> fixture + ": " + err.getMessage());
            var rejectedByCompiler = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)));
            assertTrue(rejectedByCompiler.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"),
                    () -> fixture + ": " + rejectedByCompiler.getMessage());
        }
    }

    @Test
    void rejectsUnsupportedTypeAndShadowedStateLoan() {
        rejected(checkedCart().replace("add(int n)", "add(string n)"),
                "checked i64");
        rejected(checkedCart().replace("add(int n)", "add(int s)")
                        .replace(" + n;", " + s;"),
                "shadows");
        rejected(checkedCart().replace("add(int n)", "add(int quantity)")
                        .replace(" + n;", " + quantity;"),
                "shadows");
        rejected(checkedCart().replace("-> void ::", "-> int ::"),
                "return void");
    }

    @Test
    void protectedMethodCannotSmuggleAnUnverifiedOrForgedTokenKind() {
        var source = actor(checkedCart());
        var original = source.methods().getFirst();
        var unverified = new Ast.ProtectedMethod(original.name(),
                original.parameters(), original.returnType(), original.bodyTokens());
        var forgedActor = new Ast.VirtualActorDecl(source.name(),
                source.visibility(), source.stateFields(), List.of(unverified));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(forgedActor));

        var forgedTypes = new ArrayList<>(original.bodyTokenKinds());
        forgedTypes.set(17, "UNVERIFIED");
        var forged = new Ast.ProtectedMethod(original.name(), original.parameters(),
                original.returnType(), original.bodyTokens(), forgedTypes);
        var poisoned = new Ast.VirtualActorDecl(source.name(),
                source.visibility(), source.stateFields(), List.of(forged));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(poisoned));
    }

    @Test
    void impossibleMethodShapeDoesNotBecomeExecutable() {
        var noMethods = actor("""
                define virtual actor Cart as
                  state { let int quantity: 0; }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(noMethods));
        var ordinaryGate = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        define virtual actor Cart as
                          state { let int quantity: 0; }
                        end
                        """)));
        assertTrue(ordinaryGate.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }

    @Test
    void rejectUnsafeMethodThroughProductionTypeChecker() {
        var source = checkedCart().replace("s.quantity = s.quantity + n;", "return s;");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_V1_REJECTED"));
    }
}
