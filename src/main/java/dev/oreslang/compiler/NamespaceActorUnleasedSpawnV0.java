package dev.oreslang.compiler;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.SharedCodeImageStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Deliberately small, source-facing, unleased namespace actor execution slice.
 *
 * <p>The parser creates the same AST used for ordinary Atto programs, but
 * this stage admits only one fully checked shared actor plus an exact
 * pure-literal main script: const spawn, direct ActorRef.send(int), bounded
 * constant-count for sends, and a scalar return. No guest object, host
 * executor, mutable state loan, reflection callback or privileged descriptor
 * crosses the source boundary. The general TypeChecker stays fail closed for
 * every source form not proven by this complete AST admission.</p>
 *
 * <p>This is an internal Truffle root interpreting a closed, statically
 * compiled script, not general Graal expression-node lowering, a guest reply
 * protocol, or a native AMQP broker.</p>
 */
public final class NamespaceActorUnleasedSpawnV0 {
    private NamespaceActorUnleasedSpawnV0() { }

    private static IllegalArgumentException reject(String message) {
        return new IllegalArgumentException("NAMESPACE_ACTOR_SOURCE_SPAWN_V0_REJECTED: " + message);
    }

    private sealed interface Action permits Spawn, Send { }
    private record Spawn(String variable) implements Action { }
    private record Send(String variable, long value, int count) implements Action {
        private Send {
            if (count < 1 || count > 1024) throw reject("send repetition exceeds budget");
        }
    }
    private record Plan(Ast.Program program, String codeUnitId,
                        String actorType, List<Action> actions, long result) {
        private Plan { actions = List.copyOf(actions); }
    }

    /**
     * Real Truffle child nodes produced from the *already admitted* Atto AST,
     * not a runtime re-interpretation of the Plan's Spawn/Send records.
     * The only mutable execution scope is per-evaluation and cannot escape
     * into source/guest values, another context or an actor-owned heap.
     *
     * This is an incremental, closed-world lowering for actor actions.
     * Guest await/reply and general expression lowering are NOT implemented.
     */
    private abstract static class GuestActionNode extends Node {
        abstract void execute(GuestSession session) throws Exception;
    }

    private static final class GuestSession {
        private final OresContext context;
        private final ActorRuntime.ActorCodeExecutor code;
        private final String actorName;
        private final Map<String, ActorRuntime.ActorRef<Object>> refs =
                new LinkedHashMap<>();
        private final Map<String, Long> counts = new LinkedHashMap<>();

        private GuestSession(OresContext context,
                ActorRuntime.ActorCodeExecutor code, String actorName) {
            this.context = Objects.requireNonNull(context);
            this.code = Objects.requireNonNull(code);
            this.actorName = Objects.requireNonNull(actorName);
        }
    }

    private static final class SpawnNode extends GuestActionNode {
        private final String variable;
        private SpawnNode(String variable) { this.variable = variable; }

        @Override void execute(GuestSession session) throws Exception {
            spawnAndAwait(session);
        }

        @TruffleBoundary
        private void spawnAndAwait(GuestSession session) throws Exception {
            if (session.refs.containsKey(variable)) {
                throw new SecurityException("duplicate checked actor spawn binding");
            }
            var ref = session.context.actors().spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, session.code, session.actorName);
            // Register before the readiness await: a constructor/activation
            // failure must still clean up and retire the actor incarnation.
            session.refs.put(variable, ref);
            session.counts.put(variable, 0L);
            ref.ready().get(5, TimeUnit.SECONDS);
        }
    }

    private static final class SendNode extends GuestActionNode {
        private final String variable;
        private final long value;
        private final int count;
        private SendNode(String variable, long value, int count) {
            this.variable = variable;
            this.value = value;
            this.count = count;
        }

        @Override void execute(GuestSession session) {
            dispatchCheckedMessages(session);
        }

        @TruffleBoundary
        private void dispatchCheckedMessages(GuestSession session) {
            var ref = session.refs.get(variable);
            if (ref == null) {
                throw new SecurityException("source attempted send before actor spawn");
            }
            for (int i = 0; i < count; i++) {
                ref.send(value);
                // Count *admitted* sends only. An admission failure cannot
                // produce an artificial success count.
                session.counts.put(variable,
                        Math.addExact(session.counts.get(variable), 1L));
            }
        }
    }

    private static GuestActionNode[] lowerActions(List<Action> verified) {
        GuestActionNode[] children = new GuestActionNode[verified.size()];
        for (int i = 0; i < verified.size(); i++) {
            Action action = verified.get(i);
            if (action instanceof Spawn spawn) {
                children[i] = new SpawnNode(spawn.variable());
            } else if (action instanceof Send send) {
                children[i] = new SendNode(
                        send.variable(), send.value(), send.count());
            } else {
                // Deliberate fail-closed compiler rule: there is no fallback
                // interpreted or host-callable action path.
                throw reject("unlowered actor main expression");
            }
        }
        return children;
    }

    /**
     * Candidate filter is only a performance hint, NEVER a security proof.
     * The production Parser and exact AST admission are required regardless.
     */
    public static RootCallTarget tryCreate(
            OresLanguage language, String codeUnitId, String sourceText) {
        if (!sourceText.contains("declare actor") || !sourceText.contains("spawn ")) {
            return null;
        }
        Ast.Program program = Parser.parse(sourceText);
        boolean declaredActor = program.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .anyMatch(d -> d instanceof Ast.StandaloneActorDecl);
        if (!declaredActor) return null;
        Plan plan = verify(program, codeUnitId);
        // Validate the complete actor IR at parse/admission time, before a
        // guest root is installed. Each invocation gets a fresh image later.
        try (SharedCodeImageStore images = new SharedCodeImageStore()) {
            NamespaceActorCriticalIrCompiler.compileForVerifiedMain(
                    images.publish(codeUnitId, program));
        }
        return new GuestRoot(language, plan).getCallTarget();
    }

    private static Plan verify(Ast.Program program, String codeUnitId) {
        Objects.requireNonNull(program);
        if (codeUnitId == null || codeUnitId.isBlank()
                || program.namespace() == null || program.namespace().isBlank()
                || !program.imports().isEmpty()
                || program.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(program.modules().getFirst().name())) {
            throw reject("requires one explicit namespace/root source unit without imports");
        }
        Ast.FunctionDecl main = null;
        String actorType = null;
        int actorDeclarations = 0;
        for (Ast.Decl declaration : program.modules().getFirst().declarations()) {
            if (declaration instanceof Ast.FunctionDecl fn) {
                if (main != null || !fn.name().equals("main")) {
                    throw reject("only one top-level main function is admitted");
                }
                main = fn;
            } else if (declaration instanceof Ast.StandaloneActorDecl actor) {
                if (++actorDeclarations > 1) throw reject("one actor declaration only");
                actorType = actor.name();
            }
            // State and private methods are verified independently by
            // compileForVerifiedMain; nothing else is permitted there.
        }
        if (main == null || actorType == null || actorDeclarations != 1
                || main.kind() != Ast.CallableKind.FNC
                || main.visibility() != Ast.Visibility.PUBLIC
                || main.actorKind() != Ast.ActorKind.NONE
                || main.async() || main.suspends() || main.generator()
                || main.structural() || main.nonLexical() || main.trapped()
                || !main.genericParameters().isEmpty()
                || !main.parameters().isEmpty()
                || !main.annotations().isEmpty()
                || !main.effects().isEmpty()
                || !simple(main.returnType(), "int")
                || main.body().size() < 3 || main.body().size() > 64) {
            throw reject("main must be public non-suspending fnc(): int with bounded source operations");
        }
        List<Action> actions = new ArrayList<>();
        Map<String, Long> sent = new LinkedHashMap<>();
        long returnValue = 0L;
        long totalMessages = 0L;
        boolean finished = false;
        for (int index = 0; index < main.body().size(); index++) {
            Ast.Stmt statement = main.body().get(index);
            if (statement instanceof Ast.ReturnStmt ret) {
                if (index != main.body().size() - 1
                        || !(ret.value() instanceof Ast.LiteralExpr literal)
                        || !(literal.value() instanceof Long n)) {
                    throw reject("main needs exactly one final signed-i64 return");
                }
                returnValue = n;
                finished = true;
            } else if (statement instanceof Ast.BindingStmt binding) {
                if (binding.kind() != Ast.BindingKind.CONST
                        || binding.mutableReferent()
                        || binding.declaredType() != null
                        || binding.inferredTypeName() != null
                        || !(binding.initializer() instanceof Ast.RuntimeCallExpr op)
                        || !op.operation().equals("spawn") || op.arguments().size() != 1
                        || !(op.arguments().getFirst() instanceof Ast.NewExpr created)
                        || !simple(created.type(), actorType)
                        || !created.arguments().isEmpty()
                        || sent.containsKey(binding.name()) || sent.size() >= 8) {
                    throw reject("actor bindings require unique const name = spawn ActorType()");
                }
                sent.put(binding.name(), 0L);
                actions.add(new Spawn(binding.name()));
            } else if (statement instanceof Ast.ExprStmt expression) {
                Send send = verifySend(expression.expression(), sent, 1);
                actions.add(send);
                totalMessages = Math.addExact(totalMessages, send.count());
            } else if (statement instanceof Ast.ForStmt loop) {
                int count = verifyBoundedLoop(loop, sent);
                if (loop.body().size() != 1
                        || !(loop.body().getFirst() instanceof Ast.ExprStmt call)) {
                    throw reject("bounded send loop needs one direct send statement");
                }
                Send send = verifySend(call.expression(), sent, count);
                actions.add(send);
                totalMessages = Math.addExact(totalMessages, count);
            } else {
                throw reject("no arbitrary calls, actor aliases, state reads or effects in main");
            }
        }
        if (!finished || sent.isEmpty() || totalMessages < 1
                || totalMessages > 4096) {
            throw reject("main must spawn actors, send 1..4096 values and return");
        }
        return new Plan(program, codeUnitId, actorType, actions, returnValue);
    }

    private static Send verifySend(Ast.Expr expression,
                                   Map<String, Long> sent, int count) {
        if (!(expression instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                || call.arguments().size() != 1
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !member.member().equals("send")
                || !(member.receiver() instanceof Ast.NameExpr receiver)
                || !sent.containsKey(receiver.name())
                || !(call.arguments().getFirst() instanceof Ast.LiteralExpr literal)
                || !(literal.value() instanceof Long value)) {
            throw reject("only statically typed actor.send(signed-i64 literal) is permitted");
        }
        return new Send(receiver.name(), value, count);
    }

    private static int verifyBoundedLoop(
            Ast.ForStmt loop, Map<String, Long> actorBindings) {
        if (!(loop.initializer() instanceof Ast.BindingStmt start)
                || start.kind() != Ast.BindingKind.LET
                || actorBindings.containsKey(start.name())
                || !simpleOrInferredInt(start.declaredType())
                || !(start.initializer() instanceof Ast.LiteralExpr initial)
                || !(initial.value() instanceof Long first) || first != 0L
                || !(loop.condition() instanceof Ast.BinaryExpr less)
                || !less.operator().equals("<")
                || !isName(less.left(), start.name())
                || !(less.right() instanceof Ast.LiteralExpr upper)
                || !(upper.value() instanceof Long bound)
                || bound < 1 || bound > 1024
                || !(loop.update() instanceof Ast.AssignExpr update)
                || !isName(update.target(), start.name())
                || !(update.value() instanceof Ast.BinaryExpr plus)
                || !plus.operator().equals("+")
                || !isName(plus.left(), start.name())
                || !(plus.right() instanceof Ast.LiteralExpr one)
                || !(one.value() instanceof Long increment) || increment != 1L) {
            throw reject("only for (let i = 0; i < 1..1024; i = i + 1) is supported");
        }
        return Math.toIntExact(((Ast.LiteralExpr) ((Ast.BinaryExpr) loop.condition()).right())
                .value() instanceof Long n ? n : 0L);
    }

    private static boolean simple(Ast.TypeRef type, String name) {
        return type != null && type.name().equals(name)
                && type.arguments().isEmpty() && !type.inferArguments()
                && !type.isBorrow();
    }
    private static boolean simpleOrInferredInt(Ast.TypeRef type) {
        return type == null || simple(type, "int");
    }
    private static boolean isName(Ast.Expr expression, String name) {
        return expression instanceof Ast.NameExpr ref && ref.name().equals(name);
    }

    private static final class GuestRoot extends RootNode {
        private final Plan plan;
        @Children private final GuestActionNode[] loweredActions;

        private GuestRoot(OresLanguage language, Plan plan) {
            super(language);
            this.plan = plan;
            this.loweredActions = lowerActions(plan.actions());
        }
        @Override public String getName() { return "atto-namespace-actor-spawn-v0"; }
        @Override public boolean isInternal() { return true; }
        @Override public Object execute(VirtualFrame frame) {
            return executeBody(OresContext.get(this), frame.getArguments());
        }

        private long executeBody(OresContext context, Object[] arguments) {
            if (arguments.length != 0) throw new SecurityException(
                    "checked namespace-actor main accepts no host arguments");
            if (context.isolatePolicy().adversarial()) throw new SecurityException(
                    "source actor spawn v0 unavailable in adversarial contexts");
            // The normal Graal root applies the full capability policy.
            // Special source admission must not silently skip that boundary.
            CapabilityChecker.check(
                    plan.program(), context.isolatePolicy(),
                    context.permissionCheckMode());
            // This bounded v0 runner deliberately executes on the calling
            // host thread, not by parking a CONTROL/actor carrier.
            try (SharedCodeImageStore images = new SharedCodeImageStore()) {
                ActorRuntime.ActorCodeExecutor code =
                        NamespaceActorCriticalIrCompiler.compileForVerifiedMain(
                                images.publish(plan.codeUnitId(), plan.program()));
                var descriptor = NamespaceActorCriticalIrCompiler.descriptor(code);
                if (!descriptor.actorName().equals(plan.actorType())
                        || descriptor.codeImage() != code.codeImage()) {
                    throw new SecurityException("actor code image/descriptor mismatch");
                }
                GuestSession session =
                        new GuestSession(context, code, descriptor.actorName());
                try {
                    // Actions were statically lowered into actual Truffle
                    // child nodes at parse/admission time. Each invocation
                    // uses a fresh owner-bound session and CodeImage.
                    for (GuestActionNode node : loweredActions) node.execute(session);
                    verifyDrainedActors(session);
                    return plan.result();
                } finally {
                    retireActors(session);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("source actor main interrupted", interrupted);
            } catch (Exception failure) {
                throw new IllegalStateException("checked source actor main failed", failure);
            }
        }

        @TruffleBoundary
        private static void verifyDrainedActors(GuestSession session)
                throws Exception {
            for (var entry : session.refs.entrySet()) {
                var ref = entry.getValue();
                ref.drain().get(15, TimeUnit.SECONDS);
                if (ref.failure().isPresent()) {
                    throw new IllegalStateException(
                            "checked source actor terminated during mailbox dispatch");
                }
                var receipt = NamespaceActorCriticalIrCompiler.receiptAfterDrain(
                        session.code, ref);
                if (receipt.processed() != session.counts.get(entry.getKey())
                        || !receipt.actorId().equals(ref.id())
                        || receipt.generation() != ref.generation()) {
                    throw new SecurityException(
                            "source actor mailbox receipt invalid or incorrect");
                }
            }
        }

        @TruffleBoundary
        private static void retireActors(GuestSession session) throws Exception {
            Exception firstFailure = null;
            // A failing stop must not prevent siblings from being stopped.
            for (var ref : session.refs.values()) {
                try {
                    ref.stop();
                } catch (Exception failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
            for (var ref : session.refs.values()) {
                try {
                    if (!ref.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "actor failed to quiesce before code-image retirement");
                    }
                } catch (Exception failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
            if (firstFailure != null) throw firstFailure;
        }
    }
}
