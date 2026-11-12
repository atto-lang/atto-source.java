package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.NamespaceActorScalarRegion;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Closed, compiler-owned scalar actor turn lowering for the existing ActorRuntime.
 *
 * <p>Exactly one declared actor, one signed-i64 literal state field, private
 * return-only init, private receive with one direct self.increment() call,
 * and one lexical critical method are accepted. The critical body is matched
 * in its entirety against a nonescaping, single-write MIR instruction.
 * Anything outside that closed subset fails BEFORE an actor can be spawned.
 *
 * <p>This is an internal VM bridge, not general Atto guest lowering. It does
 * not expose an ActorRef state API, public critical dispatch, cross-file
 * linking, arbitrary statements, read loans, Native Image proof or broker I/O.
 * The normal TypeChecker still fails closed for standalone namespace actors.
 */
final class NamespaceActorScalarTurnCompiler {
    private NamespaceActorScalarTurnCompiler() { }

    static ActorRuntime.ActorCodeExecutor compile(SharedCodeImageStore.CodeImage image) {
        Objects.requireNonNull(image, "trusted compiler image");
        Ast.Program source = image.program();
        if (source.namespace() == null || source.namespace().isBlank()
                || !source.imports().isEmpty()
                || source.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(source.modules().getFirst().name())) {
            throw unsupported("requires one closed namespace source with no imports");
        }
        List<Ast.Decl> declarations = source.modules().getFirst().declarations();
        if (declarations.size() != 5) {
            throw unsupported("requires exactly header, sealed state, init, receive, critical");
        }
        Ast.StandaloneActorDecl actor = null;
        Ast.ActorStateDecl schema = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        Ast.CriticalActorMethodDecl critical = null;
        for (Ast.Decl decl : declarations) {
            if (decl instanceof Ast.StandaloneActorDecl a && actor == null) actor = a;
            else if (decl instanceof Ast.ActorStateDecl s && schema == null) schema = s;
            else if (decl instanceof Ast.CriticalActorMethodDecl c && critical == null) critical = c;
            else if (decl instanceof Ast.ExternalActorMethodDecl m) {
                if ("init".equals(m.name()) && init == null) init = m;
                else if ("receive".equals(m.name()) && receive == null) receive = m;
                else throw unsupported("additional lifecycle or other callable");
            } else throw unsupported("additional, duplicate, or unsupported declaration");
        }
        if (actor == null || schema == null || init == null || receive == null || critical == null
                || actor.receiverName() != null || actor.actorKind() != Ast.ActorKind.SHARED
                || !actor.implementedContracts().isEmpty()
                || !actor.name().equals(schema.actorName())
                || !actor.name().equals(critical.actorName())) {
            throw unsupported("actor/state/critical nominal identity mismatch");
        }
        // Do not infer authority from lexical spellings or textual namespace.
        // These binders reject different actors, method shadowing and invalid schemas.
        ActorReceiverBinder.bind(source);
        var bindings = ActorStateBinder.bindSourceSet(
                "compiler-internal:single-image", Map.of("src/actor.atto", source));
        var slot = new ActorStateBinder.Slot(
                "compiler-internal:single-image", source.namespace(), actor.name());
        if (bindings.size() != 1 || !bindings.containsKey(slot)
                || schema.fields().size() != 1) {
            throw unsupported("requires exactly one attached private scalar field");
        }
        Ast.ProtectedStateField field = schema.fields().getFirst();
        if (!field.type().arguments().isEmpty() || field.type().isBorrow()
                || field.type().inferArguments()
                || !(field.initializer() instanceof Ast.LiteralExpr)) {
            throw unsupported("only typed scalar literal state can execute");
        }
        Object initialValue = ((Ast.LiteralExpr) field.initializer()).value();
        boolean integerField = "int".equals(field.type().name())
                && initialValue instanceof Long;
        boolean booleanField = "bool".equals(field.type().name())
                && initialValue instanceof Boolean;
        if (!integerField && !booleanField) {
            throw unsupported("int/bool initializers require exactly matching literal types");
        }
        if (!validReturnOnly(actor.name(), init) || !init.parameters().isEmpty()
                || !validRuntimeReceive(actor.name(), receive, critical)) {
            throw unsupported("private init/receive ABI or bounded call body invalid");
        }
        if (critical.visibility() != Ast.Visibility.PRIVATE
                || !critical.parameters().isEmpty()
                || !"void".equals(critical.returnType().name())
                || !critical.returnType().arguments().isEmpty()
                || !critical.actorName().equals(actor.name())) {
            throw unsupported("critical requires private zero-arg void method");
        }
        // Require authenticated lexer provenance, then compile the ENTIRE body.
        // Never use source substring searches or skip unknown statements.
        if (critical.bodyTokens().size() != critical.bodyTokenKinds().size()
                || critical.bodyTokenKinds().stream()
                        .anyMatch(k -> k == null || k.isBlank() || "UNVERIFIED".equals(k))) {
            throw unsupported("critical source lacks trusted lexical provenance");
        }
        if (integerField) closedIncrement(critical, field.name());
        else closedBoolWrite(critical, field.name());
        return new Plan(image, actor.name(),
                integerField ? (Long) initialValue : 0L,
                booleanField && (Boolean) initialValue,
                booleanField);
    }

    private static boolean validReturnOnly(
            String actor, Ast.ExternalActorMethodDecl method) {
        return method.runtimeIngress() && method.visibility() == Ast.Visibility.PRIVATE
                && actor.equals(method.actorName())
                && "void".equals(method.returnType().name())
                && method.returnType().arguments().isEmpty()
                && method.body().size() == 1
                && method.body().getFirst() instanceof Ast.ReturnStmt result
                && result.value() == null;
    }

    private static boolean validRuntimeReceive(
            String actor, Ast.ExternalActorMethodDecl receive,
            Ast.CriticalActorMethodDecl critical) {
        if (!receive.runtimeIngress() || receive.visibility() != Ast.Visibility.PRIVATE
                || !actor.equals(receive.actorName())
                || !"void".equals(receive.returnType().name())
                || receive.parameters().size() != 1 || receive.body().size() != 2) return false;
        Ast.TypeRef t = receive.parameters().getFirst().type();
        if (t.isBorrow() || t.inferArguments() || !"ActorMail".equals(t.name())
                || t.arguments().size() != 1
                || !"int".equals(t.arguments().getFirst().name())
                || !t.arguments().getFirst().arguments().isEmpty()
                || t.arguments().getFirst().isBorrow()) return false;
        if (!(receive.body().getFirst() instanceof Ast.ExprStmt expr)
                || !(expr.expression() instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT || call.typeArgumentsPresent()
                || !call.typeArguments().isEmpty() || !call.arguments().isEmpty()
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !member.member().equals(critical.name())
                || !(member.receiver() instanceof Ast.NameExpr name)
                || !name.name().equals(receive.receiverName())) return false;
        return receive.body().get(1) instanceof Ast.ReturnStmt ret && ret.value() == null;
    }

    private static void closedIncrement(
            Ast.CriticalActorMethodDecl method, String field) {
        // Lexeme identity is backed by original lexer types. The only admitted
        // write is s.field = s.field + 1 under an immediate critical state loan.
        // No await, branch, receiver capture, callback, pointer, or loan escape.
        List<String> exact = new ArrayList<>(List.of(
                "using", "state", ".", "write", "(", "critical",
                "|", "s", "|", "->", "{", "s", ".", field,
                "=", "s", ".", field, "+", "1", ";",
                "}", ")", ";", "return", ";"));
        if (!method.bodyTokens().equals(exact)) {
            throw unsupported("critical body requires a single checked state increment");
        }
    }

    private static void closedBoolWrite(
            Ast.CriticalActorMethodDecl method, String field) {
        List<String> exact = List.of(
                "using", "state", ".", "write", "(", "critical",
                "|", "s", "|", "->", "{", "s", ".", field,
                "=", "true", ";", "}", ")", ";", "return", ";");
        if (!method.bodyTokens().equals(exact)) {
            throw unsupported("critical bool body requires one checked state assignment");
        }
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(
                "NAMESPACE_ACTOR_SCALAR_MIR_UNSUPPORTED: " + reason);
    }

    /** Runtime-backed actor-owned region, not a compiler-scoped Java value. */
    private record Owned(NamespaceActorScalarRegion scalar)
            implements ActorRuntime.ActorOwnedGuestState {
        @Override public ActorRuntime.ActorId ownerActorId() {
            return scalar.ownerActorId();
        }
    }

    private record Plan(
            SharedCodeImageStore.CodeImage codeImage,
            String actorName,
            long initialInt,
            boolean initialBool,
            boolean booleanField) implements ActorRuntime.ActorCodeExecutor {
        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String requestedActor, ActorRuntime.ActorContext<Object> context) {
            requireTurn(requestedActor, context);
            if (context.currentMail().isPresent()) {
                throw new SecurityException("init must run on activation only");
            }
            return new Owned(NamespaceActorScalarRegion.initialize(
                    context, initialInt, initialBool, booleanField));
        }

        @Override
        public void receiveActor(
                String requestedActor,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            requireTurn(requestedActor, context);
            if (!(state instanceof Owned owned)
                    || !owned.ownerActorId().equals(context.self().id())
                    || !mail.recipient().equals(context.self().id())
                    || !mail.equals(context.currentMail().orElse(null))
                    || !(mail.value() instanceof Long)) {
                throw new SecurityException("cross-actor or forged scalar mailbox ingress");
            }
            // This executes the compiler-admitted closed MIR through the
            // physical #2796 RW loan manager (zero pending capacity). No
            // suspension or public state capability is created.
            try {
                if (booleanField) owned.scalar().setTrue(context);
                else owned.scalar().increment(context);
            } catch (RuntimeException | Error failure) {
                // Runtime may bypass graceful teardown after a failed turn.
                // Revoke this allocation now so it cannot be reused or leaked.
                try {
                    owned.scalar().retire(context);
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void tearDownActor(
                String requestedActor,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorContext<Object> context) {
            requireTurn(requestedActor, context);
            if (!(state instanceof Owned owned)
                    || !owned.ownerActorId().equals(context.self().id())) {
                throw new SecurityException("cross-actor scalar teardown");
            }
            owned.scalar().retire(context); // close and physically retire loans
        }

        private void requireTurn(
                String requestedActor, ActorRuntime.ActorContext<Object> context) {
            if (!actorName.equals(requestedActor)
                    || context.codeImage().orElse(null) != codeImage
                    || context.kind() != ActorRuntime.ActorKind.SHARED
                    || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !context.self().id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException(
                        "critical scalar lowering requires a live image-bound actor turn");
            }
        }
    }
}
