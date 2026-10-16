package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.RuntimePermissions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Existing syntax and fail-closed policy gates for the proposed built-in
 * ChildProcManager actor base. Does not claim native subprocess support.
 */
final class ChildProcManagerActorContractTest {
    @Test
    void actorExtendsHeaderPreservesParentWithoutInventingNewActorKind() {
        Ast.Program ast = Parser.parse("""
                define actor CPM extends ChildProcManager as
                end
                """);
        Ast.ClassDecl actor = (Ast.ClassDecl) ast.modules().getFirst()
                .declarations().getFirst();

        assertEquals("CPM", actor.name());
        assertEquals(Ast.ActorKind.PRIVATE, actor.actorKind());
        assertEquals(1, actor.parents().size());
        assertEquals("ChildProcManager", actor.parents().getFirst().name());
        assertTrue(actor.parents().getFirst().arguments().isEmpty());
    }

    @Test
    void genericActorAndExplicitParentKeepIndependentTypePositions() {
        Ast.Program ast = Parser.parse("""
                define actor CPM<T> extends ChildProcManager as
                end
                """);
        Ast.ClassDecl actor = (Ast.ClassDecl) ast.modules().getFirst()
                .declarations().getFirst();

        assertEquals(java.util.List.of("T"), actor.genericParameters());
        assertEquals("ChildProcManager", actor.parents().getFirst().name());
    }

    @Test
    void runtimePolicyDoesNotGrantProcessAuthorityToOrdinaryOrUntrustedActors() {
        assertFalse(IsolatePolicy.developer().capabilities()
                .contains(IsolatePolicy.Capability.CHILD_PROCESS));
        assertFalse(IsolatePolicy.untrustedActor().capabilities()
                .contains(IsolatePolicy.Capability.CHILD_PROCESS));
        assertFalse(RuntimePermissions.fromCapabilities(
                IsolatePolicy.untrustedActor().capabilities())
                .allows(RuntimePermissions.Permission.RUN, "/bin/sh"));
    }

    @Test
    void scopedRunFlagCannotUpgradeCoarseIsolateAuthority() {
        assertThrows(SecurityException.class, () ->
                RuntimePermissions.fromApplicationArguments(
                        new String[]{"--ores-permission-run-allow=*"},
                        IsolatePolicy.developer()));
    }

    @Test
    void explicitShellDenyOverridesWildcardRunGrant() {
        RuntimePermissions policy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.RUN, "*")
                .withDenied(RuntimePermissions.Permission.RUN, "/bin/zsh");
        assertFalse(policy.allows(RuntimePermissions.Permission.RUN, "/bin/zsh"));
        assertTrue(policy.allows(RuntimePermissions.Permission.RUN, "/bin/sh"));
    }
    @Test
    void unimplementedBuiltinParentCannotBeTypeCheckedOrSpawned() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        define actor CPM extends ChildProcManager as
                        end
                        """)));
        assertTrue(error.getMessage().contains("not implemented yet"));
    }

    @Test
    void ordinaryActorCannotImpersonateReservedRuntimeBase() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        define actor ChildProcManager as
                        end
                        """)));
        assertTrue(error.getMessage().contains("reserved built-in actor base"));
    }

    @Test
    void typeAliasCannotImpersonateReservedRuntimeBase() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        type ChildProcManager = int;
                        """)));
        assertTrue(error.getMessage().contains("reserved built-in actor base"));
    }

    @Test
    void processBaseCannotBeLaunderedThroughActorType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        define actor UserDefinedManager extends ChildProcManager as
                        end
                        """)));
        assertTrue(error.getMessage().contains("no process-spawning actor"));
    }

    @Test
    void interfaceCannotImpersonateReservedRuntimeBase() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        define interface ChildProcManager as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("interfaces/contracts"));
    }

    @Test
    void contractCannotImpersonateReservedRuntimeBase() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        define contract ChildProcManager as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("interfaces/contracts"));
    }

    @Test
    void moduleCannotImpersonateReservedRuntimeBase() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        define module ChildProcManager as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("user-defined modules"));
    }

    @Test
    void explicitRunGrantStillRequiresCoarseCapability() {
        RuntimePermissions scoped = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.RUN, "/bin/echo");
        assertTrue(scoped.allows(RuntimePermissions.Permission.RUN, "/bin/echo"));
        assertFalse(IsolatePolicy.untrustedActor().capabilities()
                .contains(IsolatePolicy.Capability.CHILD_PROCESS));
        assertFalse(IsolatePolicy.developer().capabilities()
                .contains(IsolatePolicy.Capability.CHILD_PROCESS));
    }

}
