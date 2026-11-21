package dev.oreslang.compiler;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.GeneratorRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import dev.oreslang.runtime.SharedCodeImageStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * First compiler-owned scheduler/await slice for native namespace actors.
 *
 * <p>This compiles a fully verified, bounded native Atto async-main AST into
 * an OresScheduler.Task state machine. Its awaits use OresFuture registration
 * and root-scheduler resumption, never get/join/awaitTermination on a carrier.
 * Only the exact admitted source forms are executable; full general typed
 * expression lowering, ServiceActor and SupervisorActor remain separate work.
 * No Java actor behavior or guest-authority-bearing callbacks are accepted.</p>
 */
public final class NamespaceActorAsyncGuestV1 {
    private NamespaceActorAsyncGuestV1() { }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("NAMESPACE_ACTOR_ASYNC_V1_REJECTED: " + reason);
    }

    private record Send(String variable, long value) { }

    private record Plan(Ast.Program program, String unitId, String actorType,
                        List<String> variables, List<Send> sends, String resultVariable) {
        private Plan {
            variables = List.copyOf(variables);
            sends = List.copyOf(sends);
        }
    }

    /** Candidate filter is never admission: every AST node is checked below. */
    public static RootCallTarget tryCreate(
            OresLanguage language, String codeUnitId, String sourceText) {
        if (!sourceText.contains("declare actor")
                || !sourceText.contains("async fnc main")
                || !sourceText.contains("spawn ")) return null;
        Ast.Program program = Parser.parse(sourceText);
        if (program.modules().stream().flatMap(module -> module.declarations().stream())
                .noneMatch(Ast.StandaloneActorDecl.class::isInstance)) return null;
        Plan plan = verify(program, codeUnitId);
        // The code image and actor ingress descriptor must be compiler-owned.
        // A guest-supplied name/implements clause cannot mint output rights.
        try (SharedCodeImageStore images = new SharedCodeImageStore()) {
            var code = NamespaceActorCriticalIrCompiler.compileForVerifiedMain(
                    images.publish(codeUnitId, program));
            if (!NamespaceActorCriticalIrCompiler.emitsScalarOutput(code)
                    || !NamespaceActorCriticalIrCompiler.descriptor(code)
                            .actorName().equals(plan.actorType())) {
                throw reject("native actor receive does not emit verified scalar output");
            }
        }
        return new AsyncRoot(language, plan).getCallTarget();
    }

    private static Plan verify(Ast.Program program, String codeUnitId) {
        Objects.requireNonNull(program);
        if (codeUnitId == null || codeUnitId.isBlank()
                || program.namespace() == null || program.namespace().isBlank()
                || !program.imports().isEmpty() || program.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(program.modules().getFirst().name())) {
            throw reject("requires a single, explicit namespace-root Atto source unit");
        }
        Ast.FunctionDecl main = null;
        String actorType = null;
        for (Ast.Decl declaration : program.modules().getFirst().declarations()) {
            if (declaration instanceof Ast.FunctionDecl fn) {
                if (main != null || !fn.name().equals("main")) {
                    throw reject("one public async main function only");
                }
                main = fn;
            } else if (declaration instanceof Ast.StandaloneActorDecl actor) {
                if (actorType != null) throw reject("one checked actor declaration only");
                actorType = actor.name();
            }
            // Actor state, private ingress and critical operations are
            // separately closed by compileForVerifiedMain.
        }
        if (main == null || actorType == null
                || main.kind() != Ast.CallableKind.FNC
                || main.visibility() != Ast.Visibility.PUBLIC
                || !main.async() || main.suspends() || main.generator()
                || main.structural() || main.nonLexical() || main.trapped()
                || main.actorKind() != Ast.ActorKind.NONE
                || !main.genericParameters().isEmpty()
                || !main.parameters().isEmpty() || !main.annotations().isEmpty()
                || !main.effects().isEmpty()
                || !simple(main.returnType(), "int")
                || main.body().size() < 3 || main.body().size() > 17) {
            throw reject("main must be public async fnc(): int with bounded statements");
        }
        Map<String, Long> sentCounts = new LinkedHashMap<>();
        List<String> variables = new ArrayList<>();
        List<Send> sends = new ArrayList<>();
        String output = null;
        boolean sawSend = false;
        for (int i = 0; i < main.body().size(); i++) {
            Ast.Stmt statement = main.body().get(i);
            if (statement instanceof Ast.BindingStmt binding) {
                if (sawSend || output != null
                        || binding.kind() != Ast.BindingKind.CONST
                        || binding.mutableReferent()
                        || binding.declaredType() != null
                        || binding.inferredTypeName() != null
                        || !(binding.initializer() instanceof Ast.RuntimeCallExpr spawn)
                        || !spawn.operation().equals("spawn")
                        || spawn.arguments().size() != 1
                        || !(spawn.arguments().getFirst() instanceof Ast.NewExpr created)
                        || !simple(created.type(), actorType)
                        || !created.arguments().isEmpty()
                        || sentCounts.containsKey(binding.name())
                        || variables.size() >= 8) {
                    throw reject("only unique const actor = spawn Type() before await sends");
                }
                variables.add(binding.name());
                sentCounts.put(binding.name(), 0L);
            } else if (statement instanceof Ast.ExprStmt statementExpr) {
                if (!(statementExpr.expression() instanceof Ast.AwaitExpr awaited)) {
                    throw reject("actor.send_async requires an explicit await");
                }
                Send send = checkedAsyncSend(awaited.expression(), sentCounts);
                if (send == null || sentCounts.get(send.variable()) != 0L) {
                    throw reject("each actor must receive exactly one verified async message");
                }
                sawSend = true;
                sends.add(send);
                sentCounts.put(send.variable(), 1L);
            } else if (statement instanceof Ast.ReturnStmt returned) {
                if (i != main.body().size() - 1 || output != null) {
                    throw reject("async main requires exactly one final return");
                }
                output = checkedAsyncOutput(returned.value(), sentCounts);
            } else {
                throw reject("arbitrary effects, aliases, loops and state reads are unsupported");
            }
        }
        if (variables.isEmpty() || sends.size() != variables.size() || output == null
                || sentCounts.values().stream().anyMatch(x -> x != 1L)) {
            throw reject("each actor needs one awaited send and one checked output projection");
        }
        return new Plan(program, codeUnitId, actorType, variables, sends, output);
    }

    private static Send checkedAsyncSend(
            Ast.Expr expression, Map<String, Long> known) {
        if (!(expression instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                || call.arguments().size() != 1
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !member.member().equals("send_async")
                || !(member.receiver() instanceof Ast.NameExpr receiver)
                || !known.containsKey(receiver.name())
                || !(call.arguments().getFirst() instanceof Ast.LiteralExpr literal)
                || !(literal.value() instanceof Long value)) return null;
        return new Send(receiver.name(), value);
    }

    /**
     * Source syntax: return (await a.outputs().next()).value.value;
     * First .value is GeneratorRuntime.Step's optional payload; second
     * .value is ActorOutput's data. The compiler validates the exact type
     * and actor incarnation during the resumed task turn.
     */
    private static String checkedAsyncOutput(
            Ast.Expr expression, Map<String, Long> known) {
        if (!(expression instanceof Ast.MemberExpr outer)
                || !outer.member().equals("value")
                || !(outer.receiver() instanceof Ast.MemberExpr first)
                || !first.member().equals("value")
                || !(first.receiver() instanceof Ast.AwaitExpr awaited)
                || !(awaited.expression() instanceof Ast.CallExpr next)
                || next.origin() != Ast.CallOrigin.DIRECT
                || next.typeArgumentsPresent() || !next.typeArguments().isEmpty()
                || !next.arguments().isEmpty()
                || !(next.callee() instanceof Ast.MemberExpr nextMember)
                || !nextMember.member().equals("next")
                || !(nextMember.receiver() instanceof Ast.CallExpr outputs)
                || outputs.origin() != Ast.CallOrigin.DIRECT
                || outputs.typeArgumentsPresent() || !outputs.typeArguments().isEmpty()
                || !outputs.arguments().isEmpty()
                || !(outputs.callee() instanceof Ast.MemberExpr outputsMember)
                || !outputsMember.member().equals("outputs")
                || !(outputsMember.receiver() instanceof Ast.NameExpr actor)
                || !known.containsKey(actor.name())) {
            throw reject("return must be (await actor.outputs().next()).value.value");
        }
        return actor.name();
    }

    private static boolean simple(Ast.TypeRef type, String name) {
        return type != null && type.name().equals(name)
                && type.arguments().isEmpty() && !type.inferArguments()
                && !type.isBorrow();
    }

    private static final class AsyncRoot extends RootNode {
        private final Plan plan;
        private AsyncRoot(OresLanguage language, Plan plan) {
            super(language);
            this.plan = plan;
        }
        @Override public String getName() { return "atto-namespace-actor-async-v1"; }
        @Override public boolean isInternal() { return true; }
        @Override public Object execute(VirtualFrame frame) {
            return executeAsync(OresContext.get(this), frame.getArguments());
        }

        @TruffleBoundary
        private Object executeAsync(OresContext context, Object[] args) {
            if (args.length != 0) {
                throw new SecurityException("native Atto async main takes no host arguments");
            }
            // runRootMain may synchronously wait ONLY on the embedding host.
            // Never re-enter this bridge from an actor, Mailman or a nested
            // root scheduler turn even though all inner awaits are nonparking.
            if (ActorRuntime.inActorExecution()
                    || ActorRuntime.inActorGroupMailmanExecution()
                    || context.vm().isRootSchedulerCurrent()) {
                throw new SecurityException(
                        "native async actor main cannot re-enter a guest carrier");
            }
            if (context.isolatePolicy().adversarial()) {
                throw new SecurityException("native async actors unavailable in adversarial contexts");
            }
            CapabilityChecker.check(plan.program(), context.isolatePolicy(),
                    context.permissionCheckMode());
            // OresContext's root scheduler drives this task and awaits the
            // resulting Future; it only parks the embedding host, never an
            // actor or scheduler carrier. Every guest await returns a Step.
            return context.runRootMain(() ->
                    context.vm().rootScheduler().start(new AsyncActorTask(context, plan)));
        }
    }

    /** Compiler-generated state machine: no guest-supplied Runnable, reflection or callback. */
    private static final class AsyncActorTask implements OresScheduler.Task<Long> {
        private enum Phase { INITIAL, READY, SEND, OUTPUT, DRAIN, RETIRE }
        private final OresContext context;
        private final Plan plan;
        private final Map<String, ActorRuntime.ActorRef<Object>> refs = new LinkedHashMap<>();
        private final List<ActorRuntime.ActorRef<Object>> actors = new ArrayList<>();
        private SharedCodeImageStore images;
        private ActorRuntime.ActorCodeExecutor code;
        private Phase phase = Phase.INITIAL;
        private int index;
        private Long result;
        private Throwable failure;

        private AsyncActorTask(OresContext context, Plan plan) {
            this.context = context;
            this.plan = plan;
        }

        @Override
        public OresScheduler.Step<Long> resume(OresScheduler.Resume resume) throws Exception {
            // Terminal retirement is an unwinding state, not a recoverable
            // execution state. In particular finish() must not recursively
            // retry on its own terminal failure or close code images twice.
            if (phase == Phase.RETIRE) return advance(resume);
            try {
                return advance(resume);
            } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                // Fatal VM defects must never be disguised as guest failures.
                try { stopActors(); } catch (Throwable ignored) { fatal.addSuppressed(ignored); }
                throw fatal;
            } catch (Throwable error) {
                if (failure == null) failure = error;
                return beginRetirement();
            }
        }

        private OresScheduler.Step<Long> advance(OresScheduler.Resume resume) throws Exception {
            switch (phase) {
                case INITIAL -> {
                    if (!resume.initial()) throw new SecurityException("actor task initial resume forged");
                    images = new SharedCodeImageStore();
                    code = NamespaceActorCriticalIrCompiler.compileForVerifiedMain(
                            images.publish(plan.unitId(), plan.program()));
                    var descriptor = NamespaceActorCriticalIrCompiler.descriptor(code);
                    if (descriptor.codeImage() != code.codeImage()
                            || !descriptor.actorName().equals(plan.actorType())
                            || !NamespaceActorCriticalIrCompiler.emitsScalarOutput(code)) {
                        throw new SecurityException("actor code image/output proof mismatch");
                    }
                    for (String name : plan.variables()) {
                        ActorRuntime.ActorRef<Object> actor = context.actors().spawnCodeActor(
                                ActorRuntime.ActorKind.SHARED, code, descriptor.actorName());
                        refs.put(name, actor);
                        actors.add(actor);
                    }
                    phase = Phase.READY;
                    index = 0;
                    return OresScheduler.await(actors.get(index).ready());
                }
                case READY -> {
                    requireSuccess(resume);
                    if (++index < actors.size())
                        return OresScheduler.await(actors.get(index).ready());
                    phase = Phase.SEND;
                    index = 0;
                    return sendAtIndex();
                }
                case SEND -> {
                    requireSuccess(resume);
                    if (++index < plan.sends().size()) return sendAtIndex();
                    phase = Phase.OUTPUT;
                    var selected = refs.get(plan.resultVariable());
                    return OresScheduler.await(selected.outputs().next());
                }
                case OUTPUT -> {
                    requireSuccess(resume);
                    var selected = refs.get(plan.resultVariable());
                    if (!(resume.value() instanceof GeneratorRuntime.Step<?> step)
                            || step.done() || !(step.value() instanceof ActorRuntime.ActorOutput<?> out)
                            || !out.actorId().equals(selected.id()) || out.sequence() != 0L
                            || !(out.value() instanceof Long number)) {
                        throw new SecurityException("invalid owned actor output/sequence or type");
                    }
                    result = number;
                    phase = Phase.DRAIN;
                    index = 0;
                    return OresScheduler.await(actors.get(index).drain());
                }
                case DRAIN -> {
                    requireSuccess(resume);
                    var actor = actors.get(index);
                    var receipt = NamespaceActorCriticalIrCompiler.receiptAfterDrain(code, actor);
                    if (receipt.processed() != 1L
                            || !receipt.actorId().equals(actor.id())
                            || receipt.generation() != actor.generation()) {
                        throw new SecurityException("actor output returned before verified mailbox drain");
                    }
                    if (++index < actors.size())
                        return OresScheduler.await(actors.get(index).drain());
                    return beginRetirement();
                }
                case RETIRE -> {
                    if (resume.failure() != null && failure == null)
                        failure = OresFuture.unwrap(resume.failure());
                    if (++index < actors.size())
                        return OresScheduler.await(actors.get(index).getDone());
                    return finish();
                }
            }
            throw new SecurityException("invalid actor async continuation phase");
        }

        private OresScheduler.Step<Long> sendAtIndex() {
            Send send = plan.sends().get(index);
            var actor = refs.get(send.variable());
            if (actor == null) throw new SecurityException("missing compiler-owned actor sender");
            return OresScheduler.await(actor.sendAsync(send.value()));
        }

        private static void requireSuccess(OresScheduler.Resume resume) throws Exception {
            if (resume.initial()) throw new SecurityException("actor continuation resumed as initial");
            if (resume.failure() != null) {
                Throwable error = OresFuture.unwrap(resume.failure());
                if (error instanceof Exception checked) throw checked;
                if (error instanceof Error fatal) throw fatal;
                throw new IllegalStateException("actor await failed", error);
            }
        }

        private void stopActors() {
            for (var actor : actors) {
                try {
                    actor.stop();
                } catch (RuntimeException failedStop) {
                    if (failure == null) failure = failedStop;
                    else failure.addSuppressed(failedStop);
                }
            }
        }

        private OresScheduler.Step<Long> beginRetirement() throws Exception {
            stopActors();
            phase = Phase.RETIRE;
            index = 0;
            if (actors.isEmpty()) return finish();
            return OresScheduler.await(actors.get(index).getDone());
        }

        private OresScheduler.Step<Long> finish() throws Exception {
            if (images != null) {
                try { images.close(); }
                catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
                images = null;
            }
            if (failure != null) {
                if (failure instanceof Exception checked) throw checked;
                if (failure instanceof Error fatal) throw fatal;
                throw new IllegalStateException("native Atto actor await failed", failure);
            }
            if (result == null) throw new SecurityException("native actor output never settled");
            return OresScheduler.done(result);
        }
    }
}
