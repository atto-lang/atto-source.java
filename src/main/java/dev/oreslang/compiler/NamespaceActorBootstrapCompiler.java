package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.List;
import java.util.Objects;

/**
 * Internal first-step executable bridge for a *closed* namespace actor subset.
 *
 * <p>This is NOT the general guest compiler entrypoint. Only one declared
 * actor with explicit private init/receive, both containing exactly
 * `return;`, and an immutable ActorMail<int> payload is admitted.
 * No state schema, critical method, contract witness, cross-file linker,
 * dynamic guest expression, or source-level spawn is lowered here.
 * Normal TypeChecker and OresEvalRootNode still reject these constructs.
 *
 * <p>Only the trusted VM can call this package-private compiler; it returns
 * a CodeImage-bound ActorCodeExecutor for the existing generation-fenced
 * ActorRuntime. It does not export a source-callable init/receive method.
 */
final class NamespaceActorBootstrapCompiler {
    private NamespaceActorBootstrapCompiler() { }

    static ActorRuntime.ActorCodeExecutor compile(SharedCodeImageStore.CodeImage image) {
        Objects.requireNonNull(image, "whitelisted source code image");
        Ast.Program program = image.program();
        if (program.namespace() == null || program.namespace().isBlank()
                || program.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(program.modules().getFirst().name())) {
            throw unsupported("requires one explicit namespace root module");
        }

        List<Ast.Decl> declarations = program.modules().getFirst().declarations();
        if (declarations.size() != 3) {
            throw unsupported("requires one actor header and only init/receive");
        }
        Ast.StandaloneActorDecl actor = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        for (Ast.Decl declaration : declarations) {
            if (declaration instanceof Ast.StandaloneActorDecl candidate) {
                if (actor != null) throw unsupported("duplicate actor declaration");
                actor = candidate;
            } else if (declaration instanceof Ast.ExternalActorMethodDecl method) {
                if ("init".equals(method.name())) {
                    if (init != null) throw unsupported("duplicate init");
                    init = method;
                } else if ("receive".equals(method.name())) {
                    if (receive != null) throw unsupported("duplicate receive");
                    receive = method;
                } else throw unsupported("non-lifecycle methods need checked MIR lowering");
            } else {
                throw unsupported("attached state and non-actor declarations need checked MIR lowering");
            }
        }
        if (actor == null || init == null || receive == null
                || actor.receiverName() != null
                || actor.actorKind() != Ast.ActorKind.SHARED
                || !actor.implementedContracts().isEmpty()) {
            throw unsupported("requires a plain declared shared actor, without contracts");
        }
        // Reuse the nominal binder's lifetime/visibility/ingress signature
        // rules, then enforce this smaller executable subset explicitly.
        ActorReceiverBinder.bind(program);
        if (!safeReturnOnly(actor.name(), init)
                || !safeReturnOnly(actor.name(), receive)
                || !init.parameters().isEmpty()
                || receive.parameters().size() != 1
                || !isScalarMail(receive.parameters().getFirst().type())) {
            throw unsupported("only private init(): void and receive(ActorMail<int>)"
                    + ": void with a single bare return are lowered");
        }
        return new Lowered(image, actor.name());
    }

    private static boolean safeReturnOnly(
            String actor, Ast.ExternalActorMethodDecl method) {
        return method.runtimeIngress()
                && method.visibility() == Ast.Visibility.PRIVATE
                && method.actorName().equals(actor)
                && method.returnType().name().equals("void")
                && method.body().size() == 1
                && method.body().getFirst() instanceof Ast.ReturnStmt ret
                && ret.value() == null;
    }

    private static boolean isScalarMail(Ast.TypeRef type) {
        return !type.isBorrow() && !type.inferArguments()
                && type.name().equals("ActorMail")
                && type.arguments().size() == 1
                && type.arguments().getFirst().name().equals("int")
                && type.arguments().getFirst().arguments().isEmpty()
                && !type.arguments().getFirst().isBorrow();
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(
                "NAMESPACE_ACTOR_BOOTSTRAP_UNSUPPORTED: " + reason);
    }

    private record Owned(ActorRuntime.ActorId owner)
            implements ActorRuntime.ActorOwnedGuestState {
        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }
    }

    private record Lowered(SharedCodeImageStore.CodeImage codeImage, String actorName)
            implements ActorRuntime.ActorCodeExecutor {
        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String requestedActor, ActorRuntime.ActorContext<Object> context) {
            requireTurn(requestedActor, context);
            if (context.currentMail().isPresent()) {
                throw new SecurityException("init is activation-only, never mailbox ingress");
            }
            // No state fields are supported. The owner token is minted only
            // *on* the actor's runtime-controlled initialization turn.
            return new Owned(context.self().id());
        }

        @Override
        public void receiveActor(
                String requestedActor,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            requireTurn(requestedActor, context);
            if (!(state instanceof Owned owned)
                    || !owned.owner().equals(context.self().id())
                    || !mail.recipient().equals(context.self().id())
                    || !mail.equals(context.currentMail().orElse(null))
                    || !(mail.value() instanceof Long)) {
                throw new SecurityException(
                        "actor mail requires current owner, physical turn and typed ActorMail<int>");
            }
            // Semantics of the complete accepted receive source: return;
            // No Java callbacks, payload mutation, or actor state escape.
        }

        private void requireTurn(
                String requestedActor, ActorRuntime.ActorContext<Object> context) {
            if (!actorName.equals(requestedActor)
                    || context.codeImage().orElse(null) != codeImage
                    || context.kind() != ActorRuntime.ActorKind.SHARED
                    || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !context.self().id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException(
                        "namespace actor runtime ingress requires a matching live actor turn");
            }
        }
    }
}
