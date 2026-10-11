package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SupervisorFailureControl;
import dev.oreslang.runtime.SupervisorProcess;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Intentionally small first source-to-CONTROL lowering slice.
 *
 * Compiles exactly one guest-defined on_panic(Failure) with the explicit
 * self.stop() decision. All other statements, effects, hooks and suspension
 * fail at compile time; this is NOT the general Oreslang evaluator.
 *
 * The compiler produces a closed, immutable decision plan. ActorGroup owns
 * CONTROL scheduling, single-flight continuation identity, and delivery
 * after actor cleanup. The trusted host sink must acknowledge the lifecycle
 * decision; source code does not receive a host ActorRef or JVM callback.
 */
public final class SourceSupervisorLowering {
    private SourceSupervisorLowering() { }

    /** An immutable, compiler-validated guest source decision plan. */
    public static final class StopOnlySupervisor {
        private final String sourceName;

        private StopOnlySupervisor(String sourceName) {
            this.sourceName = sourceName;
        }

        public String sourceName() { return sourceName; }

        /**
         * Installs the compiled source hook on a real supervisor-owned
         * CONTROL lane. The host sink is the authority that acknowledges STOP
         * on the physically finalized child; it must not silently treat STOP
         * as restart, escalation, or a request to stop unrelated actors.
         */
        public SupervisorProcess install(
                ActorRuntime.ActorGroup group,
                int capacity,
                Consumer<SupervisorProcess.Resolution> hostDecision) {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(hostDecision, "hostDecision");
            ActorRuntime.ActorGroupId expectedGroup = group.id();
            ActorRuntime.SupervisorId expectedSupervisor = group.supervisorId();
            return group.installSupervisorProcess(capacity, notice -> {
                if (!expectedSupervisor.equals(notice.supervisorId())
                        || !expectedGroup.equals(notice.groupId())) {
                    throw new SecurityException("foreign supervisor failure");
                }
                if (notice.kind() != SupervisorFailureControl.Kind.TERMINAL_FAILURE) {
                    throw new IllegalStateException(
                            "STOP-only on_panic cannot handle cancellation");
                }
                // This instruction was emitted from the checked guest AST.
                // No guest callback runs on the failed child carrier.
                return CompletableFuture.completedFuture(
                        SupervisorProcess.Directive.STOP);
            }, resolution -> {
                if (resolution.directive() != SupervisorProcess.Directive.STOP
                        || !expectedGroup.equals(resolution.notice().groupId())
                        || !expectedSupervisor.equals(resolution.notice().supervisorId())) {
                    throw new SecurityException("invalid compiled supervisor decision");
                }
                hostDecision.accept(resolution);
            });
        }
    }

    /**
     * Separate, opt-in entry point: normal program/type/isolate admission must
     * continue rejecting source supervisors until all hooks/effects are lowered.
     */
    public static StopOnlySupervisor compile(String source) {
        return compile(Parser.parse(Objects.requireNonNull(source, "source")));
    }

    public static StopOnlySupervisor compile(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        if (!program.imports().isEmpty() || program.namespace() != null) {
            throw unsupported("imports and namespaces require linked supervisor module lowering");
        }
        if (program.modules().size() != 1) {
            throw unsupported("exactly one supervisor module is required");
        }
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()
                || module.declarations().size() != 1
                || !(module.declarations().getFirst() instanceof Ast.SupervisorDecl)) {
            throw unsupported("module metadata and other declarations are not in the STOP slice");
        }
        Ast.SupervisorDecl target =
                (Ast.SupervisorDecl) module.declarations().getFirst();
        if (target == null) throw unsupported("missing supervisor declaration");
        if (target.hooks().size() != 1) {
            throw unsupported("only on_panic is executable in the STOP slice");
        }
        Ast.MethodDecl hook = target.hooks().getFirst();
        if (!hook.name().equals("on_panic")
                || hook.isStatic() || hook.isAbstract() || hook.async()
                || hook.suspends() || hook.structural()
                || hook.explicitReceiverType() != null
                || !hook.genericParameters().isEmpty()
                || hook.parameters().size() != 1
                || hook.parameters().getFirst().structural()
                || hook.parameters().getFirst().mutable()
                || hook.parameters().getFirst().name().isBlank()
                || hook.parameters().getFirst().name().equals("self")
                || !hook.parameters().getFirst().type().equals(Ast.TypeRef.simple("Failure"))
                || !hook.returnType().equals(Ast.TypeRef.simple("void"))) {
            throw unsupported("on_panic requires synchronous (f: Failure): void");
        }
        for (Ast.Annotation annotation : hook.annotations()) {
            if (!annotation.name().equals("Implements") || !annotation.arguments().isEmpty()) {
                throw unsupported("hook annotations require general compiler lowering");
            }
        }
        List<Ast.Stmt> body = hook.body();
        if (body.size() != 2
                || !(body.getFirst() instanceof Ast.ExprStmt statement)
                || !(body.getLast() instanceof Ast.ReturnStmt returned)
                || returned.value() != null
                || !(statement.expression() instanceof Ast.CallExpr call)
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                || !call.arguments().isEmpty()
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr receiver)
                || !receiver.name().equals("self")
                || !member.member().equals("stop")) {
            throw unsupported("only 'self.stop(); return;' is executable");
        }
        // No dynamic interpretation, reflection, heap capture or ambient host
        // authority. An explicit source operation lowers to one host directive.
        return new StopOnlySupervisor(target.name());
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(
                "source supervisor STOP-only lowering: " + reason
                        + "; other source hooks remain unimplemented");
    }
}
