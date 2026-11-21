package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class TaskTraitAdmissionHardeningTest {
    private static Ast.TypeRef source(String name, Ast.TypeRef... arguments) {
        return new Ast.TypeRef(name, List.of(arguments), false);
    }

    @Test
    void unprovenRuntimeNamesCannotBeApprovedByNominalCallback() {
        for (String name : List.of("Arc", "Weak", "Shared", "RwLock",
                "NativePointer", "ActorState", "UnsafeCell", "AtomicRef",
                "TRef", "Ref", "RcWeak", "ReadGuard", "WriteGuard",
                "SharedMutex", "Channel", "Task", "FfiHandle")) {
            var nominal = new Types.Named(name, List.of(Types.Primitive.INT));
            for (var trait : ThreadSafetyTraits.Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(nominal, trait, (type, requested) -> true),
                        name + " must not acquire " + trait + " from a nominal callback");
                assertFalse(ThreadSafetyTraits.has(
                        new Types.Named("Option", List.of(nominal)), trait,
                        (type, requested) -> true), "nested " + name);
            }
        }
    }

    @Test
    void namedOptionResultAndIteratorWrappersNeedResolvedIntrinsicIdentity() {
        for (String name : List.of("Option", "Result", "IteratorResult",
                "OptionUnwrapError")) {
            List<Types.Type> arguments = switch (name) {
                case "Result" -> List.of(Types.Primitive.INT, Types.Primitive.STRING);
                case "OptionUnwrapError" -> List.of();
                default -> List.of(Types.Primitive.INT);
            };
            var impersonator = new Types.Named(name, arguments);
            for (var trait : ThreadSafetyTraits.Trait.values()) {
                // The nominal callback is intentionally over-permissive:
                // a matching *name* is not compiler-authenticated identity.
                assertFalse(ThreadSafetyTraits.has(
                        impersonator, trait, (type, requested) -> true),
                        name + " must not self-authorize " + trait);
                assertFalse(ThreadSafetyTraits.has(
                        new Types.Named("OpaqueEnvelope", List.of(impersonator)),
                        trait, (type, requested) -> true),
                        "nested " + name + " must also fail");
            }
        }
        // This hardening is specific to unverified builtin spellings.
        // Independently proven ordinary nominal values still have Send/Sync.
        var ordinary = new Types.Named(
                "OrdinaryValue", List.of(Types.Primitive.INT));
        assertTrue(ThreadSafetyTraits.has(
                ordinary, ThreadSafetyTraits.Trait.SEND,
                (type, requested) -> type.name().equals("OrdinaryValue")));
    }

    @Test
    void sourceClassesCannotImpersonateUnloweredCapabilityAliases() {
        for (String name : List.of("SharedMutex", "NativePointer", "UnsafeCell",
                "AtomicRef", "TRef", "Ref", "RcWeak", "ReadGuard", "WriteGuard")) {
            var spoof = new Ast.ClassDecl(name, false, Ast.ActorKind.NONE,
                    List.of(), List.of(), List.of(), List.of(), List.of());
            var checker = new SendSyncTraits(new Ast.Program(
                    List.of(new Ast.ModuleDecl("audit", List.of(spoof)))), Set.of("Option"));
            assertEquals(SendSyncTraits.Traits.NONE, checker.derive(source(name)), name);
            assertEquals(SendSyncTraits.Traits.NONE,
                    checker.derive(source("Option", source(name))), "nested " + name);
        }
    }

    @Test
    void rejectedDeepTypeDiagnosticsDoNotRecursivelyFormatTypeGraph() {
        var checker = new SendSyncTraits(new Ast.Program(List.of()));
        Ast.TypeRef nested = source("int");
        for (int i = 0; i < 12_000; i++) nested = source("Option", nested);
        final Ast.TypeRef rejected = nested;
        var send = assertThrows(IllegalArgumentException.class,
                () -> checker.requireSend(rejected));
        var sync = assertThrows(IllegalArgumentException.class,
                () -> checker.requireSync(rejected));
        assertTrue(send.getMessage().contains("Send"));
        assertTrue(sync.getMessage().contains("Sync"));
        assertThrows(IllegalArgumentException.class, () -> checker.requireSend(null));
        assertThrows(IllegalArgumentException.class, () -> checker.requireSync(null));
    }

    @Test
    void ordinaryValueProofsAndExplicitSourceIntrinsicRulesRemainAvailable() {
        var checker = new SendSyncTraits(new Ast.Program(List.of()));
        assertEquals(SendSyncTraits.Traits.BOTH, checker.derive(source("int")));
        // Source and resolved policies agree: void is a return effect, not an owned value.
        assertEquals(SendSyncTraits.Traits.NONE, checker.derive(source("void")));
        assertFalse(ThreadSafetyTraits.has(Types.Primitive.VOID,
                ThreadSafetyTraits.Trait.SEND, (type, trait) -> true));
        assertFalse(ThreadSafetyTraits.has(Types.Primitive.VOID,
                ThreadSafetyTraits.Trait.SYNC, (type, trait) -> true));
        var ordinary = new Types.Named("OrdinaryValue", List.of(Types.Primitive.INT));
        for (var trait : ThreadSafetyTraits.Trait.values()) {
            assertTrue(ThreadSafetyTraits.has(ordinary, trait, (type, requested) -> true));
            assertFalse(ThreadSafetyTraits.has(ordinary, trait, (type, requested) -> false));
        }
        assertFalse(checker.isSend(source("Arc", source("int"))));
        var trusted = new SendSyncTraits(new Ast.Program(List.of()), Set.of("Arc"));
        assertEquals(SendSyncTraits.Traits.BOTH,
                trusted.derive(source("Arc", source("int"))));
    }
}
