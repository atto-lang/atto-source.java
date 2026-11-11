package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorProtectedOpsMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Source-authored contract tests; these are NOT runtime guest execution. */
final class VirtualActorProtectedOpsMirV1Test {
    private static String cart() throws Exception {
        Path path = Path.of("contracts", "virtual-actor-mir-v1", "positive",
                "cart_checked_contract.atto").toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(path));
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Ast.VirtualActorDecl parseActor(String source) {
        return assertInstanceOf(Ast.VirtualActorDecl.class,
                Parser.parse(source).modules().getFirst().declarations().getFirst());
    }

    @Test
    void emitsClosedTypedWriteLoanWithOverflowBeforeStore() throws Exception {
        var program = VirtualActorProtectedOpsMirV1.lower(parseActor(cart()));
        assertEquals("Cart", program.declaration());
        assertEquals("quantity", program.state().name());
        assertEquals("VirtualActorRef[Cart]", program.referenceType().spelling());
        var method = program.methods().getFirst();
        assertEquals("add", method.signature().method());
        assertEquals(0, method.localOrdinal());
        assertEquals(List.of(
                VirtualActorProtectedOpsMirV1.Op.BEGIN_EXCLUSIVE_WRITE_LOAN,
                VirtualActorProtectedOpsMirV1.Op.LOAD_STATE_I64,
                VirtualActorProtectedOpsMirV1.Op.LOAD_ARGUMENT_I64,
                VirtualActorProtectedOpsMirV1.Op.ADD_I64_TRAP_ON_OVERFLOW,
                VirtualActorProtectedOpsMirV1.Op.STORE_STATE_I64,
                VirtualActorProtectedOpsMirV1.Op.END_EXCLUSIVE_WRITE_LOAN,
                VirtualActorProtectedOpsMirV1.Op.RETURN_VOID),
                method.instructions().stream().map(
                        VirtualActorProtectedOpsMirV1.Instruction::op).toList());
        assertEquals(VirtualActorProtectedOpsMirV1.Register.SUM_I64,
                method.instructions().get(4).left());
        assertThrows(UnsupportedOperationException.class,
                () -> method.instructions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> program.methods().clear());
    }

    @Test
    void canonicalLocalOrdinalsDoNotDependOnDeclarationOrder() throws Exception {
        String source = cart();
        String add = """
                  add(int n) -> void ::
                    handle_state(critical-write |s| -> {
                      s.quantity = s.quantity + n;
                    });
                  end
                """;
        String bump = add.replace("add(int n)", "bump(int n)");
        String two = source.replace(add, bump + add);
        var program = VirtualActorProtectedOpsMirV1.lower(parseActor(two));
        assertEquals(List.of("add", "bump"),
                program.methods().stream().map(m -> m.signature().method()).toList());
        assertEquals(List.of(0, 1),
                program.methods().stream().map(
                        VirtualActorProtectedOpsMirV1.MethodPlan::localOrdinal).toList());
    }

    @Test
    void rejectUnsafeBodyVariantsBeforeProducingOperations() throws Exception {
        String source = cart();
        for (String replacement : List.of(
                "return s;",
                "s.quantity = s.quantity + n; return s;",
                "s.quantity = s.quantity + n; await n;",
                "s.quantity = s.quantity + n; s.quantity = 7;",
                "s.quantity = s.quantity - n;")) {
            String poisoned = source.replace("s.quantity = s.quantity + n;", replacement);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> VirtualActorProtectedOpsMirV1.lower(parseActor(poisoned)));
            assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_V1_REJECTED"),
                    error::getMessage);
        }
    }

    @Test
    void wrongTypedCallIsRejectedAndThereIsNoRuntimeReference() throws Exception {
        var p = VirtualActorProtectedOpsMirV1.lower(parseActor(cart()));
        assertThrows(IllegalArgumentException.class, () -> p.referenceType().requireCall(
                "add", List.of(Ast.TypeRef.simple("string"))));
        assertThrows(IllegalArgumentException.class, () -> p.referenceType().requireCall(
                "stop", List.of(Ast.TypeRef.simple("int"))));
        for (var ctor : VirtualActorProtectedOpsMirV1.ProgramPlan.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        for (var ctor : VirtualActorProtectedOpsMirV1.MethodPlan.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
    }

    @Test
    void sourceVirtualActorIsStillRejectedByProductionCompiler() throws Exception {
        assertNotNull(VirtualActorProtectedOpsMirV1.lower(parseActor(cart())));
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(cart())));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }
}
