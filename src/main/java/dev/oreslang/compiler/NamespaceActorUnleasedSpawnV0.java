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
        private GuestRoot(OresLanguage language, Plan plan) {
            super(language);
            this.plan = plan;
        }
        @Override public String getName() { return "atto-namespace-actor-spawn-v0"; }
        @Override public boolean isInternal() { return true; }
        @Override public Object execute(VirtualFrame frame) {
            return executeBody(OresContext.get(this), frame.getArguments());
        }

        @TruffleBoundary
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
                Map<String, ActorRuntime.ActorRef<Object>> refs = new LinkedHashMap<>();
                Map<String, Long> counts = new LinkedHashMap<>();
                try {
                    for (Action action : plan.actions()) {
                        if (action instanceof Spawn spawn) {
                            var ref = context.actors().spawnCodeActor(
                                    ActorRuntime.ActorKind.SHARED, code, descriptor.actorName());
                            ref.ready().get(5, TimeUnit.SECONDS);
                            refs.put(spawn.variable(), ref);
                            counts.put(spawn.variable(), 0L);
                        } else if (action instanceof Send send) {
                            var ref = refs.get(send.variable());
                            if (ref == null) throw new SecurityException(
                                    "source attempted send before actor spawn");
                            for (int i = 0; i < send.count(); i++) ref.send(send.value());
                            counts.put(send.variable(), Math.addExact(
                                    counts.get(send.variable()), send.count()));
                        }
                    }
                    for (var entry : refs.entrySet()) {
                        entry.getValue().drain().get(15, TimeUnit.SECONDS);
                        if (entry.getValue().failure().isPresent()) {
                            throw new IllegalStateException(
                                    "checked source actor terminated during mailbox dispatch");
                        }
                        var receipt = NamespaceActorCriticalIrCompiler.receiptAfterDrain(
                                code, entry.getValue());
                        if (receipt.processed() != counts.get(entry.getKey())
                                || !receipt.actorId().equals(entry.getValue().id())
                                || receipt.generation() != entry.getValue().generation()) {
                            throw new SecurityException(
                                    "source actor mailbox receipt invalid or incorrect");
                        }
                    }
                    return plan.result();
                } finally {
                    for (var ref : refs.values()) {
                        ref.stop();
                    }
                    for (var ref : refs.values()) {
                        if (!ref.awaitTermination(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "actor failed to quiesce before code-image retirement");
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("source actor main interrupted", interrupted);
            } catch (Exception failure) {
                throw new IllegalStateException("checked source actor main failed", failure);
            }
        }
    }
}
