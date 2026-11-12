package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.nodes.CriticalActorTruffleV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.CriticalActorMirV1;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Closed-world compiler-generated namespace actor ingress v1.
 *
 * Unlike the test-only ActorCodeExecutor bootstrap, this pass reads the
 * actual external mtd init/receive AST, validates each effect and emits the
 * only runtime code executor authorized for that pair of source callbacks.
 * No guest source receives an ActorContext, mailbox callback, Truffle target,
 * code-image capability, or direct sealed-state reference.
 *
 * First deliberately restricted executable subset:
 *  - one declared actor with a sealed int/bool state schema
 *  - private external init(): void { return; }
 *  - private external receive(ActorMail<int> m): void {
 *        actor.send(actor.criticalMethod(m.value)); return;
 *    }
 *  - one private critical method with one owned int input and int result.
 *
 * All other ingress bodies and source declarations fail closed. Full
 * TypeChecker guest compilation is unchanged and continues to reject the
 * still-unimplemented general namespace-actor language.
 */
public final class NamespaceActorIngressV1 {
    private NamespaceActorIngressV1() { }

    private static IllegalArgumentException denied(String why) {
        return new IllegalArgumentException("NAMESPACE_ACTOR_INGRESS_V1_UNSUPPORTED: " + why);
    }

    /**
     * Trusted compiler service entrypoint. The image must be immutable and
     * published by the shared code-image store; the package and actor keys
     * have to match the nominal source receiver and attached state schema.
     */
    public static Plan compile(
            SharedCodeImageStore.CodeImage image, String packageIdentity,
            String actorName) {
        Objects.requireNonNull(image, "runtime code image");
        Objects.requireNonNull(packageIdentity, "authenticated package identity");
        Objects.requireNonNull(actorName, "actor identity");
        if (packageIdentity.isBlank() || actorName.isBlank()) {
            throw denied("package and actor identities are required");
        }
        Ast.Program program = image.program();
        if (!program.imports().isEmpty() || program.modules().size() != 1
                || program.namespace() == null || program.namespace().isBlank()) {
            throw denied("only one explicit namespace without imports is executable");
        }
        Ast.ModuleDecl root = program.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(root.name())
                || !root.annotations().isEmpty()
                || !root.contracts().isEmpty()
                || root.declarations().size() != 5) {
            throw denied("unexpected module metadata or additional executable declarations");
        }

        Ast.StandaloneActorDecl actor = null;
        Ast.ActorStateDecl state = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        Ast.CriticalActorMethodDecl critical = null;

        for (Ast.Decl decl : root.declarations()) {
            if (decl instanceof Ast.StandaloneActorDecl value) {
                if (actor != null) throw denied("ambiguous nominal actor");
                actor = value;
            } else if (decl instanceof Ast.ActorStateDecl value) {
                if (state != null) throw denied("ambiguous sealed state schema");
                state = value;
            } else if (decl instanceof Ast.ExternalActorMethodDecl method) {
                if (method.name().equals("init") && init == null) init = method;
                else if (method.name().equals("receive") && receive == null) receive = method;
                else throw denied("external actor methods other than init/receive are not executable");
            } else if (decl instanceof Ast.CriticalActorMethodDecl method) {
                if (critical != null) throw denied("multiple critical methods require dispatch linking");
                critical = method;
            } else {
                throw denied("unsupported declaration adjacent to actor code");
            }
        }
        if (actor == null || state == null || init == null || receive == null || critical == null
                || !actor.name().equals(actorName)
                || !state.actorName().equals(actorName)
                || !critical.actorName().equals(actorName)
                || !init.actorName().equals(actorName)
                || !receive.actorName().equals(actorName)) {
            throw denied("actor/state/ingress declaration identities do not match");
        }
        if (actor.receiverName() != null || !actor.implementedContracts().isEmpty()) {
            throw denied("actor receiver aliases and unverified contracts are unsupported");
        }

        // Reuse the existing typed nominal receiver binder and state checker:
        // a hand-constructed AST must not mint an ingress capability.
        Map<String, Ast.Program> units = Map.of(image.codeUnitId(), program);
        var methods = ActorReceiverBinder.bindSourceSet(packageIdentity, units);
        var layouts = ActorStateBinder.bindSourceSet(packageIdentity, units);
        var key = new ActorStateBinder.Slot(packageIdentity, program.namespace(), actorName);
        if (!layouts.containsKey(key) || methods.size() != 2
                || !methods.containsKey(new ActorReceiverBinder.SourceSlot(
                        packageIdentity, program.namespace(), actorName, "init", 0))
                || !methods.containsKey(new ActorReceiverBinder.SourceSlot(
                        packageIdentity, program.namespace(), actorName, "receive", 1))) {
            throw denied("actor package must bind exactly one state, init and receive");
        }
        if (!init.runtimeIngress() || !receive.runtimeIngress()
                || init.visibility() != Ast.Visibility.PRIVATE
                || receive.visibility() != Ast.Visibility.PRIVATE) {
            throw denied("unforgeable runtime-only private ingress metadata required");
        }
        if (init.parameters().size() != 0 || !voidType(init.returnType())
                || !emptyReturn(init.body())) {
            throw denied("init v1 must be an empty zero-argument void activation");
        }
        if (receive.parameters().size() != 1 || !voidType(receive.returnType())) {
            throw denied("receive requires exactly one typed ActorMail parameter");
        }
        Ast.Param mailParam = receive.parameters().getFirst();
        Ast.TypeRef envelope = mailParam.type();
        if (mailParam.mutable() || mailParam.structural()
                || envelope.inferArguments() || !envelope.name().equals("ActorMail")
                || envelope.arguments().size() != 1
                || !simpleType(envelope.arguments().getFirst(), "int")) {
            throw denied("receive v1 requires an immutable ActorMail<int> input");
        }

        var lowered = CriticalActorMirV1.lowerSourceSet(packageIdentity, units).get(key);
        if (lowered == null || lowered.size() != 1) {
            throw denied("exactly one checked critical scalar method is required");
        }
        var typed = lowered.getFirst();
        if (!typed.name().equals(critical.name()) || typed.parameters().size() != 1
                || typed.parameters().values().iterator().next() != CriticalActorMirV1.Type.I64
                || typed.returns() != CriticalActorMirV1.Type.I64) {
            throw denied("receive must invoke one verified int->int critical method");
        }
        boolean emitResult = checkedReceiveBody(receive, critical, mailParam.name());
        var executable = CriticalActorTruffleV1.compile(
                image, packageIdentity, actorName, critical.name());
        return new Plan(image, actorName, executable, emitResult);
    }

    private static boolean voidType(Ast.TypeRef type) {
        return simpleType(type, "void");
    }
    private static boolean simpleType(Ast.TypeRef type, String name) {
        return type.name().equals(name) && !type.inferArguments()
                && !type.isBorrow() && type.arguments().isEmpty();
    }
    private static boolean emptyReturn(List<Ast.Stmt> body) {
        return body.size() == 1 && body.getFirst() instanceof Ast.ReturnStmt ret
                && ret.value() == null;
    }

    private static boolean isCall(Ast.Expr value, String receiver, String name) {
        return value instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr target
                && target.name().equals(receiver)
                && member.member().equals(name);
    }
    private static Ast.CallExpr directCall(Ast.Expr value) {
        if (!(value instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()) {
            throw denied("ingress may call only exact verified non-generic methods");
        }
        return call;
    }

    /**
     * Compile a literal AST of the source receive body, not a host-selected
     * callback. This is not a general expression evaluator.
     */
    private static boolean checkedReceiveBody(
            Ast.ExternalActorMethodDecl method, Ast.CriticalActorMethodDecl critical,
            String mailName) {
        List<Ast.Stmt> body = method.body();
        if (body.size() != 2 || !(body.getFirst() instanceof Ast.ExprStmt statement)
                || !(body.getLast() instanceof Ast.ReturnStmt ret)
                || ret.value() != null) {
            throw denied("receive v1 requires one critical method call and terminal return");
        }

        Ast.CallExpr outer = directCall(statement.expression());
        if (!isCall(outer.callee(), method.receiverName(), "send")
                || outer.arguments().size() != 1) {
            throw denied("receive must emit the checked result through actor.send");
        }
        Ast.CallExpr inner = directCall(outer.arguments().getFirst());
        if (!isCall(inner.callee(), method.receiverName(), critical.name())
                || inner.arguments().size() != 1) {
            throw denied("receive cannot invoke an arbitrary or guest-accessible method");
        }
        if (!(inner.arguments().getFirst() instanceof Ast.MemberExpr value)
                || !(value.receiver() instanceof Ast.NameExpr mail)
                || !mail.name().equals(mailName)
                || !value.member().equals("value")) {
            throw denied("critical input must be a copied value from the current ActorMail<int>");
        }
        // Future v2 may support return-only receive without output.
        return true;
    }

    public static final class Plan {
        private final SharedCodeImageStore.CodeImage image;
        private final String actorName;
        private final CriticalActorTruffleV1.Executable compiled;
        private final boolean emitResult;

        private Plan(SharedCodeImageStore.CodeImage image, String actorName,
                     CriticalActorTruffleV1.Executable compiled, boolean emitResult) {
            this.image = image;
            this.actorName = actorName;
            this.compiled = compiled;
            this.emitResult = emitResult;
        }

        /**
         * Runtime-issued actor identity and mailbox are mandatory. No
         * hand-written ActorCodeExecutor or callback object is supplied.
         */
        public ActorRuntime.ActorRef<Object> spawn(
                ActorRuntime runtime, IsolatePolicy policy) {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(policy, "actor policy");
            return runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, policy,
                    new GeneratedIngress(image, actorName, compiled, emitResult),
                    actorName);
        }
    }

    private static final class GeneratedIngress implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final String actorName;
        private final CriticalActorTruffleV1.Executable compiled;
        private final boolean emitResult;

        private GeneratedIngress(
                SharedCodeImageStore.CodeImage image, String actorName,
                CriticalActorTruffleV1.Executable compiled, boolean emitResult) {
            this.image = image;
            this.actorName = actorName;
            this.compiled = compiled;
            this.emitResult = emitResult;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName, ActorRuntime.ActorContext<Object> context) {
            requireActor(actorTypeName);
            return compiled.initializeActor(context);
        }

        @Override public void receiveActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            requireActor(actorTypeName);
            if (!(raw instanceof CriticalActorTruffleV1.OwnedState owned)
                    || !(mail.value() instanceof Long value)) {
                throw new SecurityException("untrusted actor state or untyped mailbox payload");
            }
            Object result = compiled.invokeFromMailbox(owned, mail, context, value);
            // The source AST says actor.send(actor.critical(mail.value)).
            // Emit after the Truffle write-loan scope has fully exited.
            if (emitResult) context.runtime().emitCurrentActorOutput(result);
        }

        @Override public void tearDownActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorContext<Object> context) {
            requireActor(actorTypeName);
            ((CriticalActorTruffleV1.OwnedState) raw).close();
        }

        private void requireActor(String name) {
            if (!actorName.equals(name)) {
                throw new SecurityException("foreign actor ingress dispatch identity");
            }
        }
    }
}
