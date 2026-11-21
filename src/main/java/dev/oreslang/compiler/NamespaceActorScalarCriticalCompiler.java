package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Closed, compiler-internal prototype for a namespace actor with one
 * actor-owned signed-i64 field and one lexically checked critical method.
 *
 * <p>Not part of the general guest compiler. Only the exact verified AST
 * pattern in critical-scalar-turn.atto is lowerable; no arbitrary guest
 * expressions, state loans or source-visible host callbacks are admitted.
 * The ordinary TypeChecker remains fail-closed for namespace actors.
 */
final class NamespaceActorScalarCriticalCompiler {
    private NamespaceActorScalarCriticalCompiler() { }

    static ActorRuntime.ActorCodeExecutor compile(SharedCodeImageStore.CodeImage image) {
        Objects.requireNonNull(image, "source image");
        Ast.Program source = image.program();
        if (source.namespace() == null || source.namespace().isBlank()
                || source.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(source.modules().getFirst().name())) {
            throw unsupported("requires one explicitly namespaced root unit");
        }

        List<Ast.Decl> declarations = source.modules().getFirst().declarations();
        if (declarations.size() != 5) {
            throw unsupported("requires a declared actor, one state schema and three methods");
        }

        Ast.StandaloneActorDecl actor = null;
        Ast.ActorStateDecl schema = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        Ast.ExternalActorMethodDecl criticalAdd = null;
        for (Ast.Decl declaration : declarations) {
            if (declaration instanceof Ast.StandaloneActorDecl a) {
                if (actor != null) throw unsupported("duplicate actor");
                actor = a;
            } else if (declaration instanceof Ast.ActorStateDecl a) {
                if (schema != null) throw unsupported("duplicate state schema");
                schema = a;
            } else if (declaration instanceof Ast.ExternalActorMethodDecl m) {
                switch (m.name()) {
                    case "init" -> {
                        if (init != null) throw unsupported("duplicate init");
                        init = m;
                    }
                    case "receive" -> {
                        if (receive != null) throw unsupported("duplicate receive");
                        receive = m;
                    }
                    case "add" -> {
                        if (criticalAdd != null) throw unsupported("duplicate critical add");
                        criticalAdd = m;
                    }
                    default -> throw unsupported("extra actor method is not lowerable");
                }
            } else {
                throw unsupported("only the closed actor/state/method source pattern is lowerable");
            }
        }
        if (actor == null || schema == null || init == null || receive == null
                || criticalAdd == null || actor.receiverName() != null
                || actor.actorKind() != Ast.ActorKind.SHARED
                || !actor.implementedContracts().isEmpty()
                || schema.fields().size() != 1
                || !schema.actorName().equals(actor.name())) {
            throw unsupported("requires one plain shared actor with its own scalar schema");
        }
        ActorReceiverBinder.bind(source);
        ActorStateBinder.bindSourceSet("internal-bootstrap@1",
                Map.of("src/critical-scalar.atto", source));

        Ast.ProtectedStateField field = schema.fields().getFirst();
        if (!field.name().equals("count") || !simpleType(field.type(), "int")
                || !(field.initializer() instanceof Ast.LiteralExpr lit)
                || !(lit.value() instanceof Long initial)) {
            throw unsupported("requires one private signed-i64 count literal");
        }
        if (!isRuntimeIngress(actor.name(), init, "init")
                || !init.parameters().isEmpty() || !onlyReturn(init.body())
                || !isRuntimeIngress(actor.name(), receive, "receive")
                || receive.parameters().size() != 1) {
            throw unsupported("private runtime-only init and receive are mandatory");
        }
        Ast.Param mail = receive.parameters().getFirst();
        if (!mail.type().name().equals("ActorMail")
                || mail.type().arguments().size() != 1
                || mail.type().isBorrow() || mail.type().inferArguments()
                || !simpleType(mail.type().arguments().getFirst(), "int")) {
            throw unsupported("receive must accept one owned ActorMail<int>");
        }
        if (!criticalAdd.criticalMethod() || criticalAdd.runtimeIngress()
                || criticalAdd.visibility() != Ast.Visibility.PRIVATE
                || !criticalAdd.actorName().equals(actor.name())
                || !simpleType(criticalAdd.returnType(), "void")
                || criticalAdd.parameters().size() != 1
                || !simpleType(criticalAdd.parameters().getFirst().type(), "int")) {
            throw unsupported("add must be a private critical mtd with one int parameter");
        }

        // Every mutable statement is structurally checked. A direct write
        // in receive, altered field path, extra call, await, capture, index,
        // method reference, or changed arithmetic fails at compile admission.
        String receiver = criticalAdd.receiverName();
        String delta = criticalAdd.parameters().getFirst().name();
        if (criticalAdd.body().size() != 1
                || !(criticalAdd.body().getFirst() instanceof Ast.ExprStmt expression)
                || !(expression.expression() instanceof Ast.AssignExpr assign)
                || !isActorField(assign.target(), receiver, field.name())
                || !(assign.value() instanceof Ast.BinaryExpr plus)
                || !plus.operator().equals("+")
                || !isActorField(plus.left(), receiver, field.name())
                || !isName(plus.right(), delta)) {
            throw unsupported("critical add body must be exactly count = count + delta");
        }

        if (receive.body().size() != 2
                || !(receive.body().getFirst() instanceof Ast.ExprStmt callStatement)
                || !(callStatement.expression() instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                || call.arguments().size() != 1
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !isName(member.receiver(), receive.receiverName())
                || !member.member().equals("add")
                || !(call.arguments().getFirst() instanceof Ast.MemberExpr value)
                || !isName(value.receiver(), mail.name())
                || !value.member().equals("value")
                || !(receive.body().get(1) instanceof Ast.ReturnStmt ret)
                || ret.value() != null) {
            throw unsupported("receive must call own critical add(mail.value), then return");
        }
        return new Lowered(image, actor.name(), initial);
    }

    private static boolean simpleType(Ast.TypeRef type, String name) {
        return type.name().equals(name) && type.arguments().isEmpty()
                && !type.isBorrow() && !type.inferArguments();
    }

    private static boolean isRuntimeIngress(
            String actor, Ast.ExternalActorMethodDecl method, String name) {
        return method.name().equals(name)
                && method.actorName().equals(actor)
                && method.runtimeIngress() && !method.criticalMethod()
                && method.visibility() == Ast.Visibility.PRIVATE
                && simpleType(method.returnType(), "void");
    }

    private static boolean onlyReturn(List<Ast.Stmt> body) {
        return body.size() == 1 && body.getFirst() instanceof Ast.ReturnStmt ret
                && ret.value() == null;
    }

    private static boolean isName(Ast.Expr expr, String name) {
        return expr instanceof Ast.NameExpr n && n.name().equals(name);
    }

    private static boolean isActorField(Ast.Expr expr, String receiver, String field) {
        return expr instanceof Ast.MemberExpr slot
                && slot.member().equals(field)
                && slot.receiver() instanceof Ast.MemberExpr state
                && state.member().equals("state")
                && isName(state.receiver(), receiver);
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(
                "NAMESPACE_ACTOR_SCALAR_CRITICAL_UNSUPPORTED: " + reason);
    }

    private static final class Owned implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorRef<Object> owner;
        private long count;

        Owned(ActorRuntime.ActorRef<Object> owner, long count) {
            this.owner = Objects.requireNonNull(owner);
            this.count = count;
        }

        @Override public ActorRuntime.ActorId ownerActorId() {
            return owner.id();
        }

        private void criticalAdd(
                long delta, Permit permit, ActorRuntime.ActorContext<Object> context) {
            if (!permit.live || permit.owner != this
                    || permit.actor != owner
                    || permit.carrier != Thread.currentThread()
                    || permit.runtime != context.runtime()
                    || context.self() != owner
                    || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !owner.id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("critical write requires current exclusive actor turn");
            }
            // Checked source '+' must not silently wrap the actor's count.
            count = Math.addExact(count, delta);
        }
    }

    /** Compiler-issued, turn-local and non-escaping write capability. */
    private static final class Permit {
        private final Owned owner;
        private final ActorRuntime.ActorRef<Object> actor;
        private final ActorRuntime runtime;
        private final Thread carrier;
        private boolean live = true;

        Permit(Owned owner, ActorRuntime.ActorContext<Object> context) {
            this.owner = owner;
            this.actor = context.self();
            this.runtime = context.runtime();
            this.carrier = Thread.currentThread();
        }

        void release() { live = false; }
    }

    private record Lowered(SharedCodeImageStore.CodeImage codeImage,
                           String actorName, long initial)
            implements ActorRuntime.ActorCodeExecutor {
        private void requireTurn(
                String requested, ActorRuntime.ActorContext<Object> context) {
            if (!actorName.equals(requested)
                    || context.codeImage().orElse(null) != codeImage
                    || context.kind() != ActorRuntime.ActorKind.SHARED
                    || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !context.self().id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("namespace state actor ingress requires active owner turn");
            }
        }

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String requested, ActorRuntime.ActorContext<Object> context) {
            requireTurn(requested, context);
            if (context.currentMail().isPresent()) {
                throw new SecurityException("init is activation-only");
            }
            return new Owned(context.self(), initial);
        }

        @Override
        public void receiveActor(String requested, ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            requireTurn(requested, context);
            if (!(state instanceof Owned owned) || owned.owner != context.self()
                    || !context.self().id().equals(mail.recipient())
                    || !mail.equals(context.currentMail().orElse(null))
                    || !(mail.value() instanceof Long value)) {
                throw new SecurityException("typed mailbox ingress/actor generation mismatch");
            }
            Permit permit = new Permit(owned, context);
            try {
                owned.criticalAdd(value, permit, context);
            } finally {
                permit.release();
            }
        }
    }
}
