package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorLogicalCallMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure compiler metadata checks. No guest virtual actor is executable. */
final class VirtualActorLogicalCallMirV1Test {
    private static String cart() throws Exception {
        Path path = Path.of("contracts", "virtual-actor-mir-v1", "positive",
                "cart_checked_contract.atto").toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(path));
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Ast.Program parsed(String source) {
        return Parser.parse(source);
    }

    private static Ast.Program inNamespace(Ast.Program original, String namespace) {
        return new Ast.Program(namespace, original.imports(), original.modules());
    }

    @Test
    void emitsTypeCheckedLogicalCallObligationsNotExecutableActor() throws Exception {
        var plan = VirtualActorLogicalCallMirV1.lower(parsed(cart()));
        assertEquals("Cart", plan.reference().qualifiedName());
        assertEquals("VirtualActorRef[Cart]", plan.reference().spelling());
        assertEquals("quantity", plan.checkedOps().state().name());
        var call = plan.reference().checkCall("add",
                List.of(Ast.TypeRef.simple("int")));
        assertEquals(plan.reference().nominalId(), call.requiredNominalId());
        assertEquals(plan.reference().methods().getFirst().selector(),
                call.methodSelector());
        assertEquals(Ast.TypeRef.simple("void"), call.methodReturn());
        assertEquals(List.of(
                VirtualActorLogicalCallMirV1.Requirement.RESOLVE_LOGICAL_ADDRESS,
                VirtualActorLogicalCallMirV1.Requirement.VERIFY_NOMINAL_CODE_IMAGE,
                VirtualActorLogicalCallMirV1.Requirement.ADMIT_GENERATION_FENCED_MAIL,
                VirtualActorLogicalCallMirV1.Requirement.ENCODE_CHECKED_I64_ARGUMENT,
                VirtualActorLogicalCallMirV1.Requirement.RETURN_ASYNC_RECEIPT),
                call.obligations());
        assertThrows(UnsupportedOperationException.class,
                () -> call.obligations().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.reference().methods().clear());
    }

    @Test
    void stableMethodSelectorsDoNotDependOnSourceDeclarationOrder() throws Exception {
        String source = cart();
        String oldMethod = """
                  add(int n) -> void ::
                    handle_state(critical-write |s| -> {
                      s.quantity = s.quantity + n;
                    });
                  end
                """;
        assertTrue(source.contains(oldMethod));
        String anotherMethod = oldMethod.replace("add(int n)", "bump(int n)");
        var one = VirtualActorLogicalCallMirV1.lower(parsed(source.replace(
                oldMethod, oldMethod + anotherMethod)));
        var two = VirtualActorLogicalCallMirV1.lower(parsed(source.replace(
                oldMethod, anotherMethod + oldMethod)));
        assertEquals(one.reference().nominalId(), two.reference().nominalId());
        assertEquals(one.reference().methods().stream().map(
                VirtualActorLogicalCallMirV1.MethodAbi::selector).toList(),
                two.reference().methods().stream().map(
                        VirtualActorLogicalCallMirV1.MethodAbi::selector).toList());
        assertEquals(List.of("add", "bump"), one.reference().methods().stream()
                .map(VirtualActorLogicalCallMirV1.MethodAbi::name).toList());
    }

    @Test
    void namespacesAreNominallyDistinctAndSelectorsAreVersioned() throws Exception {
        var source = parsed(cart());
        var left = VirtualActorLogicalCallMirV1.lower(inNamespace(source, "shop.alpha"));
        var right = VirtualActorLogicalCallMirV1.lower(inNamespace(source, "shop.beta"));
        assertEquals("VirtualActorRef[shop.alpha.Cart]", left.reference().spelling());
        assertNotEquals(left.reference().nominalId(), right.reference().nominalId());
        assertNotEquals(left.reference().methods().getFirst().selector(),
                right.reference().methods().getFirst().selector());
        assertEquals(64, left.reference().nominalId().length());
        assertEquals(64, left.reference().methods().getFirst().selector().length());
        assertEquals(left.reference().nominalId(),
                VirtualActorLogicalCallMirV1.lower(inNamespace(source, "shop.alpha"))
                        .reference().nominalId());
    }

    @Test
    void rejectsWrongTypedCallsAndReservedSupervisorMethods() throws Exception {
        var ref = VirtualActorLogicalCallMirV1.lower(parsed(cart())).reference();
        assertThrows(IllegalArgumentException.class,
                () -> ref.checkCall("add", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ref.checkCall("add",
                        List.of(Ast.TypeRef.simple("string"))));
        assertThrows(IllegalArgumentException.class,
                () -> ref.checkCall("add",
                        List.of(Ast.TypeRef.simple("int"), Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> ref.checkCall("receive", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> ref.checkCall("stop", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(NullPointerException.class,
                () -> ref.checkCall("add", null));
    }

    @Test
    void deniesAliasesSuspensionAndUnverifiedExtraOperations() throws Exception {
        String source = cart();
        for (String unexpected : List.of("return s;",
                "await n;", "s.quantity = 3;", "s.quantity = s.quantity - n;",
                "s.quantity = s.quantity + n; return s;")) {
            String mutated = source.replace("s.quantity = s.quantity + n;", unexpected);
            assertThrows(IllegalArgumentException.class,
                    () -> VirtualActorLogicalCallMirV1.lower(parsed(mutated)),
                    unexpected);
        }
    }

    @Test
    void closedUnitRefusesImportsOtherDeclarationsAndMalformedNamespace() throws Exception {
        var program = parsed(cart());
        var module = program.modules().getFirst();
        var two = new ArrayList<>(module.declarations());
        two.add(module.declarations().getFirst());
        var duplicate = new Ast.ModuleDecl(module.name(),
                module.annotations(), module.contracts(), two);
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorLogicalCallMirV1.lower(
                        new Ast.Program(program.namespace(),
                                program.imports(), List.of(duplicate))));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorLogicalCallMirV1.lower(inNamespace(program, "a..b")));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorLogicalCallMirV1.lower(inNamespace(program, "a/b")));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorLogicalCallMirV1.lower(
                        new Ast.Program(program.namespace(), program.imports(), List.of())));
    }

    @Test
    void planConstructorsArePrivateAndProductionExecutionRemainsBlocked()
            throws Exception {
        for (Class<?> type : List.of(
                VirtualActorLogicalCallMirV1.ProgramPlan.class,
                VirtualActorLogicalCallMirV1.RefType.class,
                VirtualActorLogicalCallMirV1.CallPlan.class,
                VirtualActorLogicalCallMirV1.MethodAbi.class)) {
            for (var constructor : type.getDeclaredConstructors())
                assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                        () -> "forged compiler metadata: " + type);
        }
        assertNotNull(VirtualActorLogicalCallMirV1.lower(parsed(cart())));
        var denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(parsed(cart())));
        assertTrue(denied.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }
}
