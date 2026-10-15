package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
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
}
