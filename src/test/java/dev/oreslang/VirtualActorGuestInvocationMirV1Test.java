package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorGuestInvocationMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parsed .atto guest-source MIR tests: never executable runtime evidence. */
final class VirtualActorGuestInvocationMirV1Test {
    private static String cart() throws Exception {
        Path path = Path.of("contracts", "virtual-actor-guest-invoke-v1", "positive",
                        "cart_resolve_and_invoke.atto").toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(path));
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static VirtualActorGuestInvocationMirV1.ProgramPlan lower(String source) {
        return VirtualActorGuestInvocationMirV1.lower(Parser.parse(source));
    }

    private static void rejected(String source) {
        var failure = assertThrows(IllegalArgumentException.class, () -> lower(source));
        assertTrue(failure.getMessage().contains(
                "VIRTUAL_ACTOR_GUEST_INVOKE_MIR_UNSUPPORTED"),
                failure::getMessage);
    }

    @Test
    void parsedGuestResolveAndAwaitedMethodsProduceTypedLinearPlan()
            throws Exception {
        var plan = lower(cart());
        assertEquals("cart-123", plan.logicalKey());
        assertEquals("cart", plan.referenceBinding());
        assertEquals("VirtualActorRef[Cart]",
                plan.checkedActor().reference().spelling());
        assertEquals(2, plan.invocations().size());
        assertEquals(2L, plan.invocations().get(0).argument());
        assertEquals(5L, plan.invocations().get(1).argument());
        assertEquals(plan.invocations().get(0).selector(),
                plan.invocations().get(1).selector());
        assertEquals(plan.nominalId(),
                plan.invocations().get(0).checkedCall().requiredNominalId());
        assertEquals(List.of(
                VirtualActorGuestInvocationMirV1.Op.RESOLVE_LOGICAL_IDENTITY_ONLY,
                VirtualActorGuestInvocationMirV1.Op.INVOKE_GENERATION_FENCED_I64,
                VirtualActorGuestInvocationMirV1.Op.AWAIT_ASYNC_RECEIPT,
                VirtualActorGuestInvocationMirV1.Op.INVOKE_GENERATION_FENCED_I64,
                VirtualActorGuestInvocationMirV1.Op.AWAIT_ASYNC_RECEIPT,
                VirtualActorGuestInvocationMirV1.Op.RETURN_I64), plan.operations());
    }

    @Test
    void invocationPlansAreImmutableAndCompilerOwned() throws Exception {
        var plan = lower(cart());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.operations().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.invocations().clear());
        for (Class<?> type : List.of(
                VirtualActorGuestInvocationMirV1.ProgramPlan.class,
                VirtualActorGuestInvocationMirV1.Invoke.class)) {
            for (var constructor : type.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        }
    }

    @Test
    void rejectsTypeConfusionAndIncorrectResolvedActor() throws Exception {
        String source = cart();
        rejected(source.replace("VirtualActorRef<Cart> cart",
                "VirtualActorRef<Other> cart"));
        rejected(source.replace("VirtualActorRef.resolve<Cart>",
                "VirtualActorRef.resolve<Other>"));
        rejected(source.replace("const VirtualActorRef<Cart>",
                "let VirtualActorRef<Cart>"));
        rejected(source.replace("VirtualActorRef.resolve<Cart>",
                "OtherFactory.resolve<Cart>"));
    }

    @Test
    void rejectsUnknownMethodsWrongArgumentsAndDynamicArguments()
            throws Exception {
        String source = cart();
        rejected(source.replace("await cart.add(2)", "await cart.missing(2)"));
        rejected(source.replace("await cart.add(2)", "await cart.stop(2)"));
        rejected(source.replace("await cart.add(2)", "await cart.add()"));
        rejected(source.replace("await cart.add(2)", "await cart.add(2, 3)"));
        rejected(source.replace("await cart.add(2)", "await cart.add(\"2\")"));
        rejected(source.replace("await cart.add(2)", "await cart.add(cart)"));
    }

    @Test
    void rejectsDetachedOrUnawaitedCallsAndHandleEscapes() throws Exception {
        String source = cart();
        rejected(source.replace("await cart.add(2)", "cart.add(2)"));
        rejected(source.replace("await cart.add(2)", "await another.add(2)"));
        rejected(source.replace("await cart.add(2);", "return cart;"));
        rejected(source.replace("await cart.add(2);", "cart = cart;"));
        rejected(source.replace("await cart.add(2);",
                "await cart.add(2); await cart.add(3); return cart;"));
    }

    @Test
    void rejectsKeysWithControlCharactersAndUnverifiedIdentity()
            throws Exception {
        String source = cart();
        rejected(source.replace("\"cart-123\"", "\"\""));
        rejected(source.replace("\"cart-123\"", "\"a/b\""));
        rejected(source.replace("\"cart-123\"", "\"a\\nZ\""));
        rejected(source.replace("\"cart-123\"", "cart"));
    }

    @Test
    void rejectsExtraDeclarationsAndUnverifiedControlFlow() throws Exception {
        String source = cart();
        rejected(source.replace("pub async fnc main()", "pub fnc main()"));
        rejected(source.replace("return 0;", "return 1;"));
        rejected(source.replace("await cart.add(2);", "if true { await cart.add(2); }"));
        rejected(source + "\npub fnc other(): int { return 1; }\n");
        rejected(source.replace("state { let int quantity: 0; }",
                "state { let int quantity: 0; let int spare: 1; }"));
    }

    @Test
    void namespaceIsPartOfTheNominalCodeImageContract() throws Exception {
        var program = Parser.parse(cart());
        var first = VirtualActorGuestInvocationMirV1.lower(
                new Ast.Program("shop.alpha", program.imports(), program.modules()));
        var second = VirtualActorGuestInvocationMirV1.lower(
                new Ast.Program("shop.beta", program.imports(), program.modules()));
        assertNotEquals(first.nominalId(), second.nominalId());
        assertNotEquals(first.invocations().getFirst().selector(),
                second.invocations().getFirst().selector());
    }

    @Test
    void canonicalStandaloneNegativeAttoFilesRejectInBothMirAndProduction()
            throws Exception {
        for (String fixture : List.of(
                "unawaited_cart.atto",
                "wrong_nominal_cart.atto",
                "unknown_method_cart.atto")) {
            Path path = Path.of("contracts", "virtual-actor-guest-invoke-v1",
                    "negative", fixture).toAbsolutePath().normalize();
            assertFalse(Files.isSymbolicLink(path));
            assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
            String source = Files.readString(path, StandardCharsets.UTF_8);
            rejected(source);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)));
            assertTrue(failure.getMessage().contains(
                    "VIRTUAL_ACTOR_MIR_UNSUPPORTED"), failure::getMessage);
            assertTrue(failure.getMessage().contains(
                    "VIRTUAL_ACTOR_GUEST_INVOKE_MIR_UNSUPPORTED"),
                    failure::getMessage);
        }
    }

    @Test
    void productionTypeCheckerStillRejectsExecutableGuestVirtualActor()
            throws Exception {
        assertNotNull(lower(cart()));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(cart())));
        assertTrue(failure.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"),
                failure::getMessage);
    }
}
