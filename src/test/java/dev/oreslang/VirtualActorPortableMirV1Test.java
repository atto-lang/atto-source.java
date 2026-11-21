package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorPortableMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-only portable MIR tests. No runtime or guest execution claim. */
final class VirtualActorPortableMirV1Test {
    private static String cartSource() throws Exception {
        Path file = Path.of("contracts", "virtual-actor-mir-v1", "positive",
                "cart_checked_contract.atto").toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(file));
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Ast.VirtualActorDecl parseActor(String source) {
        return assertInstanceOf(Ast.VirtualActorDecl.class,
                Parser.parse(source).modules().getFirst().declarations().getFirst());
    }

    private static Ast.TypeRef virtualRef(String actor) {
        return new Ast.TypeRef("VirtualActorRef",
                List.of(Ast.TypeRef.simple(actor)), false);
    }

    @Test
    void cartLowersToExactlyOneNonSuspendingExclusiveLoan() throws Exception {
        var image = VirtualActorPortableMirV1.lower(parseActor(cartSource()));
        image.verify();
        assertEquals("Cart", image.actor());
        assertEquals("quantity", image.state().name());
        assertEquals(0L, image.state().initialValue());
        assertEquals(1, image.methods().size());
        var method = image.methods().getFirst();
        assertEquals("add", method.name());
        assertEquals(0, method.slot());
        assertEquals(List.of(
                VirtualActorPortableMirV1.Opcode.BEGIN_EXCLUSIVE_WRITE,
                VirtualActorPortableMirV1.Opcode.LOAD_STATE_I64,
                VirtualActorPortableMirV1.Opcode.LOAD_ARGUMENT_I64,
                VirtualActorPortableMirV1.Opcode.ADD_I64_CHECKED,
                VirtualActorPortableMirV1.Opcode.STORE_STATE_I64,
                VirtualActorPortableMirV1.Opcode.END_EXCLUSIVE_WRITE,
                VirtualActorPortableMirV1.Opcode.RETURN_VOID),
                method.instructions().stream().map(
                        VirtualActorPortableMirV1.Instruction::opcode).toList());
        assertEquals(List.of("quantity", "quantity", "n", "state+argument",
                "quantity", "quantity", ""),
                method.instructions().stream().map(
                        VirtualActorPortableMirV1.Instruction::subject).toList());
        assertEquals(VirtualActorPortableMirV1.ResultType.LOAN,
                method.instructions().getFirst().resultType());
        assertEquals(VirtualActorPortableMirV1.ResultType.I64,
                method.instructions().get(3).resultType());
        assertEquals(0, method.instructions().getFirst().resultRegister());
        assertEquals(0, method.instructions().get(1).firstUseRegister());
        assertEquals(1, method.instructions().get(3).firstUseRegister());
        assertEquals(2, method.instructions().get(3).secondUseRegister());
        assertEquals(3, method.instructions().get(3).resultRegister());
        assertEquals(0, method.instructions().get(4).firstUseRegister());
        assertEquals(3, method.instructions().get(4).secondUseRegister());
        assertEquals(0, method.instructions().get(5).firstUseRegister());
        assertEquals(-1, method.instructions().get(6).resultRegister());
        assertThrows(UnsupportedOperationException.class,
                () -> method.instructions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> image.methods().clear());
    }

    @Test
    void staticCallIsNominalAndNeverConvertsPhysicalReferences() throws Exception {
        var image = VirtualActorPortableMirV1.lower(parseActor(cartSource()));
        var site = image.resolveCall(virtualRef("Cart"), "add",
                List.of(Ast.TypeRef.simple("int")));
        assertEquals("Cart", site.actor());
        assertEquals("add", site.method());
        assertEquals(0, site.imageLocalSlot());
        assertEquals(Ast.TypeRef.simple("int"), site.parameterType());
        assertEquals(Ast.TypeRef.simple("void"), site.returnType());

        for (String wrong : List.of("ActorRef", "ActivationRef", "Cart")) {
            var receiver = new Ast.TypeRef(wrong,
                    List.of(Ast.TypeRef.simple("Cart")), false);
            assertThrows(IllegalArgumentException.class,
                    () -> image.resolveCall(receiver, "add",
                            List.of(Ast.TypeRef.simple("int"))));
        }
        assertThrows(IllegalArgumentException.class,
                () -> image.resolveCall(virtualRef("Other"), "add",
                        List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> image.resolveCall(Ast.TypeRef.simple("VirtualActorRef"),
                        "add", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> image.resolveCall(
                        new Ast.TypeRef("VirtualActorRef",
                                List.of(Ast.TypeRef.simple("Cart")), true),
                        "add", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> image.resolveCall(virtualRef("Cart"), "add",
                        List.of(Ast.TypeRef.simple("string"))));
        assertThrows(IllegalArgumentException.class,
                () -> image.resolveCall(virtualRef("Cart"), "add", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> image.resolveCall(virtualRef("Cart"), "receive",
                        List.of(Ast.TypeRef.simple("int"))));
    }

    @Test
    void methodSlotsAreDeterministicAndImageLocal() throws Exception {
        var original = parseActor(cartSource());
        var add = original.methods().getFirst();
        var zoom = new Ast.ProtectedMethod("zoom", add.parameters(),
                add.returnType(), add.bodyTokens(), add.bodyTokenKinds());
        var reordered = new Ast.VirtualActorDecl(original.name(), original.visibility(),
                original.stateFields(), List.of(zoom, add));
        var image = VirtualActorPortableMirV1.lower(reordered);
        assertEquals(List.of("add", "zoom"),
                image.methods().stream().map(VirtualActorPortableMirV1.Method::name).toList());
        assertEquals(0, image.resolveCall(virtualRef("Cart"), "add",
                List.of(Ast.TypeRef.simple("int"))).imageLocalSlot());
        assertEquals(1, image.resolveCall(virtualRef("Cart"), "zoom",
                List.of(Ast.TypeRef.simple("int"))).imageLocalSlot());
    }

    @Test
    void forbiddenBorrowEscapesAndSuspensionNeverProduceInstructions()
            throws Exception {
        String source = cartSource();
        for (String badBody : List.of(
                "return s;",
                "s.quantity = s.quantity + n; await work();",
                "s.quantity = s.quantity + n; s.quantity = 0;",
                "s.quantity = s.quantity + n; return s.quantity;")) {
            var corrupted = source.replace("s.quantity = s.quantity + n;", badBody);
            assertThrows(IllegalArgumentException.class,
                    () -> VirtualActorPortableMirV1.lower(parseActor(corrupted)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorPortableMirV1.lower(
                        parseActor(source.replace("critical-write", "critical-read"))));
    }

    @Test
    void forgedProvenanceAndUnsupportedStateAreRejectedBeforeLowering()
            throws Exception {
        var actor = parseActor(cartSource());
        var method = actor.methods().getFirst();
        var unverified = new Ast.ProtectedMethod(method.name(),
                method.parameters(), method.returnType(), method.bodyTokens());
        var forged = new Ast.VirtualActorDecl(actor.name(), actor.visibility(),
                actor.stateFields(), List.of(unverified));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorPortableMirV1.lower(forged));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorPortableMirV1.lower(parseActor(
                        cartSource().replace("let int quantity", "let string quantity"))));
    }

    @Test
    void productionTypeCheckerStillDeniesRuntimeExecution() throws Exception {
        var source = cartSource();
        var image = VirtualActorPortableMirV1.lower(parseActor(source));
        assertEquals("Cart", image.actor());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"),
                failure::getMessage);
    }
}
