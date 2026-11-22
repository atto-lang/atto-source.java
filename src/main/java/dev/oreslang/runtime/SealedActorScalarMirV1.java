package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Opt-in, closed-world AST -> executable actor-state scalar MIR proof.
 *
 * <p>Only one actor with one int field, one exact critical increment body and
 * one ordinary receive/return body is accepted. This does not admit general
 * critical bodies, typed network ingress, cross-actor borrows or guest main().
 * Ordinary TypeChecker/OresEvalRootNode deliberately remain fail-closed.
 *
 * <p>Unlike a host-only counter oracle, the resulting code executor initializes
 * the declaration's actual SealedActorStateRegion in a source actor turn and
 * carries its loan over real ActorRuntime mailbox dispatch. The observer is
 * a host-side test witness called only AFTER the critical loan is released.
 */
public final class SealedActorScalarMirV1 {
    private SealedActorScalarMirV1() { }

    private static IllegalArgumentException unsupported(String cause) {
        return new IllegalArgumentException(
                "ACTOR_SCALAR_CRITICAL_MIR_UNSUPPORTED: " + cause);
    }

    public static final class Plan {
        private final Ast.Program program;
        private final Ast.ClassDecl actor;
        private final String field;
        private final long initialValue;

        // Only the verified lower() path may mint a runnable MIR plan. A
        // public record constructor would let a forged AST skip all checks.
        private Plan(Ast.Program program, Ast.ClassDecl actor,
                     String field, long initialValue) {
            this.program = Objects.requireNonNull(program);
            this.actor = Objects.requireNonNull(actor);
            this.field = Objects.requireNonNull(field);
            this.initialValue = initialValue;
        }

        public Ast.Program program() { return program; }
        public String field() { return field; }
        public long initialValue() { return initialValue; }
        public String actorType() { return actor.name(); }

        public ActorRuntime.ActorKind actorKind() {
            return switch (actor.actorKind()) {
                case PRIVATE -> ActorRuntime.ActorKind.PRIVATE;
                case SHARED -> ActorRuntime.ActorKind.SHARED;
                case UNTRUSTED -> ActorRuntime.ActorKind.UNTRUSTED;
                case NONE -> throw unsupported("target is not an actor");
            };
        }

        /**
         * Bind this verified source plan to the EXACT linked code-image AST.
         * No structurally equal replacement can acquire declaration authority.
         */
        public ActorRuntime.ActorCodeExecutor executor(
                SharedCodeImageStore.CodeImage image, LongConsumer observer) {
            Objects.requireNonNull(image, "linked source code image");
            Objects.requireNonNull(observer, "post-loan host observer");
            if (image.program() != program) {
                throw new SecurityException("scalar actor code image identity mismatch");
            }
            return new ScalarExecutor(this, image, observer);
        }
    }

    public static Plan lower(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)
            throw unsupported("source budget exceeded");
        return lower(Parser.parse(source));
    }

    public static Plan lower(Ast.Program program) {
        Objects.requireNonNull(program, "source AST");
        if (!program.imports().isEmpty() || program.modules().size() != 1)
            throw unsupported("imports or multiple modules require checked linker");
        var module = program.modules().getFirst();
        if (!module.name().equals(Parser.ROOT_MODULE)
                || !module.annotations().isEmpty()
                || !module.contracts().isEmpty()
                || module.declarations().size() != 1
                || !(module.declarations().getFirst() instanceof Ast.ClassDecl actor)) {
            throw unsupported("expected exactly one closed-world actor declaration");
        }
        if (actor.actorKind() == Ast.ActorKind.NONE
                || actor.actorConcurrency().transport() != Ast.ActorTransport.GENERIC
                || actor.actorConcurrency().requestConcurrency() != 1
                || actor.actorConcurrency().messageConcurrency() != 1
                || actor.isAbstract() || actor.constructor() != null
                || !actor.genericParameters().isEmpty() || !actor.parents().isEmpty()
                || !actor.interfaces().isEmpty() || !actor.fields().isEmpty()
                || actor.sealedStateFields().size() != 1 || actor.methods().size() != 2) {
            throw unsupported("actor shape, concurrency or field ownership unsupported");
        }

        var field = actor.sealedStateFields().getFirst();
        if (!exactType(field.type(), "int")
                || !(field.initializer() instanceof Ast.LiteralExpr literal)
                || !(literal.value() instanceof Long initial)) {
            throw unsupported("actor region must be initialized by one literal int");
        }

        var increment = actor.methods().stream().filter(m -> m.name().equals("increment"))
                .findFirst().orElseThrow(() -> unsupported("missing critical increment"));
        var receive = actor.methods().stream().filter(m -> m.name().equals("receive"))
                .findFirst().orElseThrow(() -> unsupported("missing source receive"));
        if (!increment.critical() || increment.async() || increment.suspends()
                || increment.isStatic() || increment.isAbstract()
                || !increment.parameters().isEmpty()
                || !exactType(increment.returnType(), "void")
                || !increment.genericParameters().isEmpty()) {
            throw unsupported("critical increment signature invalid");
        }
        if (receive.critical() || receive.async() || receive.suspends()
                || receive.isStatic() || receive.isAbstract() || receive.structural()
                || receive.explicitReceiverType() != null
                || !receive.genericParameters().isEmpty()
                || !receive.annotations().isEmpty()
                || !receive.effects().equals(Ast.EffectSpec.none())
                || receive.parameters().size() != 1
                || !exactType(receive.returnType(), "void")) {
            throw unsupported("receive must be synchronous and noncritical");
        }
        var mail = receive.parameters().getFirst();
        if (!mail.type().name().equals("ActorMail")
                || mail.type().arguments().size() != 1
                || !exactType(mail.type().arguments().getFirst(), "int")
                || mail.mutable() || mail.structural()) {
            throw unsupported("mailbox must carry immutable ActorMail<int>");
        }
        checkIncrementBody(increment, field.name());
        checkReceiveBody(receive);
        return new Plan(program, actor, field.name(), initial);
    }

    private static boolean exactType(Ast.TypeRef t, String expected) {
        return t.name().equals(expected) && t.arguments().isEmpty()
                && !t.inferArguments();
    }

    private static boolean member(Ast.Expr expression, String receiver, String name) {
        return expression instanceof Ast.MemberExpr m
                && m.receiver() instanceof Ast.NameExpr r
                && r.name().equals(receiver) && m.member().equals(name);
    }

    private static boolean call(Ast.Expr expression, String receiver, String method,
                                int arguments) {
        return expression instanceof Ast.CallExpr c
                && member(c.callee(), receiver, method)
                && !c.typeArgumentsPresent() && c.typeArguments().isEmpty()
                && c.arguments().size() == arguments
                && c.origin() == Ast.CallOrigin.DIRECT;
    }

    private static void checkIncrementBody(Ast.MethodDecl increment, String field) {
        List<Ast.Stmt> body = increment.body();
        if (body.size() != 2
                || !(body.getFirst() instanceof Ast.ExprStmt statement)
                || !(body.get(1) instanceof Ast.ReturnStmt r) || r.value() != null
                || !call(statement.expression(), "self", "handle_state", 1)) {
            throw unsupported("critical method body must be one scoped state loan and return");
        }
        var c = (Ast.CallExpr) statement.expression();
        if (!(c.arguments().getFirst() instanceof Ast.LambdaExpr lambda)
                || lambda.nonLexical() || lambda.expressionBody() != null
                || lambda.declaredReturnType() != null
                || lambda.parameters().size() != 1
                || lambda.blockBody() == null || lambda.blockBody().size() != 1) {
            throw unsupported("critical state loan must have one lexical block alias");
        }
        var param = lambda.parameters().getFirst();
        if (!param.mutable() || param.structural()
                || !param.type().name().equals("$infer$")
                || !param.type().arguments().isEmpty()) {
            throw unsupported("critical write requires an inferred mutable state alias");
        }
        var alias = param.name();
        if (!(lambda.blockBody().getFirst() instanceof Ast.ExprStmt assignment)
                || !(assignment.expression() instanceof Ast.AssignExpr a)
                || !member(a.target(), alias, field)
                || !(a.value() instanceof Ast.BinaryExpr sum)
                || !sum.operator().equals("+")
                || !member(sum.left(), alias, field)
                || !(sum.right() instanceof Ast.LiteralExpr literal)
                || !Long.valueOf(1L).equals(literal.value())) {
            throw unsupported("only count = count + 1 under exclusive loan is lowered");
        }
    }

    private static void checkReceiveBody(Ast.MethodDecl receive) {
        if (receive.body().size() != 2
                || !(receive.body().getFirst() instanceof Ast.ExprStmt statement)
                || !call(statement.expression(), "self", "increment", 0)
                || !(receive.body().get(1) instanceof Ast.ReturnStmt ret)
                || ret.value() != null) {
            throw unsupported("receive must call critical increment and return");
        }
    }

    private record ScalarExecutor(Plan plan, SharedCodeImageStore.CodeImage codeImage,
                                  LongConsumer observer)
            implements ActorRuntime.ActorCodeExecutor {
        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName, ActorRuntime.ActorContext<Object> context) {
            if (!plan.actorType().equals(actorTypeName)
                    || context.kind() != plan.actorKind()
                    || context.codeImage().orElse(null) != codeImage) {
                throw new SecurityException("scalar actor source/code-image mismatch");
            }
            return SealedActorStateRegion.allocateFromSealedDeclaration(plan.actor());
        }

        @Override
        public void receiveActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            if (!plan.actorType().equals(actorTypeName)
                    || !(raw instanceof SealedActorStateRegion region)
                    || !region.ownerActorId().equals(context.self().id())
                    || !mail.recipient().equals(context.self().id())
                    || !(mail.value() instanceof Long)) {
                throw new SecurityException("invalid source scalar actor mail/state authority");
            }

            // This is the ONLY instruction sequence admitted by the MIR:
            // ACQUIRE_WRITE, READ_I64, CHECKED_ADD_ONE, STORE_I64, RELEASE.
            long observed = region.write(view -> {
                long next = Math.addExact(view.readInt(plan.field()), 1);
                view.writeInt(plan.field(), next);
                return next;
            });
            // No state loan survives the mailbox turn or this observer call.
            observer.accept(observed);
        }
    }
}
