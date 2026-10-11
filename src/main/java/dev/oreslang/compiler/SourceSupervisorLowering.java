package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SupervisorFailureControl;
import dev.oreslang.runtime.SupervisorProcess;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Intentionally small first source-to-CONTROL lowering slice.
 *
 * Compiles exactly one guest-defined on_panic(Failure) with the explicit
 * self.stop() or self.stop(f) decision, where f is the typed Failure binder.
 * Other statements, effects, hooks and suspension
 * fail at compile time; this is NOT the general Oreslang evaluator.
 *
 * The optional typed Failure argument is bound to the current delivered
 * notice. A stable logical child reference and general guest expression
 * evaluation are not implied by this restricted source slice.
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
        private final boolean explicitlyBoundFailure;
        private final boolean awaitsStopAcknowledgment;

        private StopOnlySupervisor(
                String sourceName, boolean explicitlyBoundFailure,
                boolean awaitsStopAcknowledgment) {
            this.sourceName = sourceName;
            this.explicitlyBoundFailure = explicitlyBoundFailure;
            this.awaitsStopAcknowledgment = awaitsStopAcknowledgment;
        }

        public String sourceName() { return sourceName; }
        /** True only when the guest named its own typed Failure parameter. */
        public boolean explicitlyBoundFailure() { return explicitlyBoundFailure; }
        /** Whether `await self.stop(f)` waits for an authoritative host acknowledgment. */
        public boolean awaitsStopAcknowledgment() { return awaitsStopAcknowledgment; }

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
            if (awaitsStopAcknowledgment) {
                throw unsupported("async on_panic requires installAwaitingStop with a CompletionStage host acknowledgment");
            }
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
                // The embedder first acknowledges the requested effect.
                // Only then permanently retire the *same finalized*
                // incarnation in the supervisor-owned logical-child registry.
                // A throwing or ambiguous host callback does not record STOP.
                hostDecision.accept(resolution);
                group.acknowledgeFinalizedStop(resolution);
            });
        }
    }

    /**
     * Restricted executable async guest hook:
     * `pub async on_panic(f: Failure): void { await self.stop(f); return; }`
     * The caller-provided CompletionStage MUST represent the host's actual
     * STOP effect acknowledgment; mere enqueue is not success. The logical
     * CONTROL lease remains owned until this stage completes. The compiler
     * admits no post-await guest statements other than `return;`.
     */
    public SupervisorProcess installAwaitingStop(
            ActorRuntime.ActorGroup group,
            int capacity,
            Function<SupervisorProcess.Resolution, CompletionStage<Void>> hostAcknowledgment) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(hostAcknowledgment, "hostAcknowledgment");
        if (!awaitsStopAcknowledgment) {
            throw unsupported("synchronous on_panic requires the synchronous STOP installer");
        }
        ActorRuntime.ActorGroupId expectedGroup = group.id();
        ActorRuntime.SupervisorId expectedSupervisor = group.supervisorId();
        return group.installSupervisorProcessAwaitingDecision(capacity, notice -> {
            if (!expectedSupervisor.equals(notice.supervisorId())
                    || !expectedGroup.equals(notice.groupId())) {
                throw new SecurityException("foreign supervisor failure");
            }
            if (notice.kind() != SupervisorFailureControl.Kind.TERMINAL_FAILURE
                    || notice.logicalChild().isEmpty()) {
                throw new IllegalStateException(
                        "async STOP requires a finalized typed logical child");
            }
            // Compiler-validated self.stop(f) emits one owned STOP intent.
            return CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP);
        }, resolution -> {
            verifyStopResolution(resolution, expectedGroup, expectedSupervisor);
            // This Stage must signal the actual STOP effect completion, not
            // request admission. On exceptional completion the turn fails
            // closed and no logical child is marked STOPPED.
            return Objects.requireNonNull(hostAcknowledgment.apply(resolution),
                    "host STOP acknowledgment stage");
        }, resolution -> {
            verifyStopResolution(resolution, expectedGroup, expectedSupervisor);
            // Runs on resumed CONTROL carrier *after* host acknowledgment.
            group.acknowledgeFinalizedStop(resolution);
        });
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
                || hook.isStatic() || hook.isAbstract()
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
            throw unsupported("on_panic requires (f: Failure): void with a supported STOP body");
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
                || !(stopCall(statement.expression()) instanceof Ast.CallExpr call)
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                || call.arguments().size() > 1
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr receiver)
                || !receiver.name().equals("self")
                || !member.member().equals("stop")) {
            throw unsupported("only 'self.stop(); return;' or 'self.stop(f); return;' is executable");
        }
        boolean awaitsAcknowledgment = statement.expression() instanceof Ast.AwaitExpr;
        if (hook.async() != awaitsAcknowledgment) {
            throw unsupported("await self.stop(...) requires async on_panic; synchronous hooks cannot await");
        }
        boolean explicitlyBoundFailure = !call.arguments().isEmpty();
        if (explicitlyBoundFailure
                && (!(call.arguments().getFirst() instanceof Ast.NameExpr failure)
                    || !failure.name().equals(hook.parameters().getFirst().name()))) {
            // The compiler never accepts arbitrary ActorRef objects, host IDs,
            // nested Failure fields or a forged name as a lifecycle target.
            throw unsupported("STOP target must be the exact typed Failure parameter");
        }
        // No dynamic interpretation, reflection, heap capture or ambient host
        // authority. Both forms resolve to the *current* delivered failure.
        return new StopOnlySupervisor(target.name(), explicitlyBoundFailure, awaitsAcknowledgment);
    }

    private static void verifyStopResolution(
            SupervisorProcess.Resolution resolution,
            ActorRuntime.ActorGroupId group,
            ActorRuntime.SupervisorId supervisor) {
        if (resolution.directive() != SupervisorProcess.Directive.STOP
                || resolution.turnGeneration() < 1
                || !group.equals(resolution.notice().groupId())
                || !supervisor.equals(resolution.notice().supervisorId())
                || resolution.notice().kind() != SupervisorFailureControl.Kind.TERMINAL_FAILURE
                || resolution.notice().logicalChild().isEmpty()) {
            throw new SecurityException("invalid compiler-lowered async STOP resolution");
        }
    }

    private static Ast.Expr stopCall(Ast.Expr expression) {
        return expression instanceof Ast.AwaitExpr await
                ? await.expression() : expression;
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(
                "source supervisor STOP-only lowering: " + reason
                        + "; other source hooks remain unimplemented");
    }
}
