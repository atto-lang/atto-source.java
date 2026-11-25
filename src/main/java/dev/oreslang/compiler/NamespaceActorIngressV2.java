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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2 closed-world compiler lowering for 1..32 checked critical actor
 * methods, all sharing ONE actor-owned private memory arena and a single
 * runtime-issued mailbox/exclusive state-loan authority.
 *
 * The real AttoRMQ actors currently declare ActorMail<ConnectionMail> and
 * ActorMail<ChannelMail> with empty, fail-closed receive bodies. This
 * compiler accepts those as NO-OP ingress, never inventing activation.
 *
 * To prove actual critical method execution, a separate controlled
 * ActorMail<int> fixture may use literal opcode branches selecting compiled
 * methods. This is NOT the broker's ADT ingress or authentication.
 */
public final class NamespaceActorIngressV2 {
    private NamespaceActorIngressV2() { }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("NAMESPACE_ACTOR_MULTIMETHOD_V2_UNSUPPORTED: " + reason);
    }

    public static Plan compile(
            SharedCodeImageStore.CodeImage image, String packageIdentity, String actorName) {
        Objects.requireNonNull(image, "published immutable CodeImage");
        Objects.requireNonNull(packageIdentity, "package identity");
        Objects.requireNonNull(actorName, "actor name");
        if (packageIdentity.isBlank() || actorName.isBlank()) throw reject("missing actor identity");
        Ast.Program source = image.program();
        if (source.namespace() == null || source.namespace().isBlank()
                || !source.imports().isEmpty() || source.modules().size() != 1) {
            throw reject("only single explicit namespace source without imports");
        }
        var root = source.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(root.name())
                || !root.annotations().isEmpty() || !root.contracts().isEmpty()) {
            throw reject("unsupported module");
        }
        Ast.StandaloneActorDecl actor = null;
        Ast.ActorStateDecl state = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        Map<String, Ast.CriticalActorMethodDecl> methods = new LinkedHashMap<>();
        for (Ast.Decl decl : root.declarations()) {
            if (decl instanceof Ast.StandaloneActorDecl a && actor == null) actor = a;
            else if (decl instanceof Ast.ActorStateDecl a && state == null) state = a;
            else if (decl instanceof Ast.ExternalActorMethodDecl a) {
                if (a.name().equals("init") && init == null) init = a;
                else if (a.name().equals("receive") && receive == null) receive = a;
                else throw reject("unexpected or duplicate external method");
            } else if (decl instanceof Ast.CriticalActorMethodDecl a) {
                if (methods.putIfAbsent(a.name(), a) != null)
                    throw reject("duplicate critical selector");
            } else throw reject("unknown declaration in actor compilation unit");
        }
        if (actor == null || state == null || init == null || receive == null
                || methods.isEmpty() || methods.size() > 32
                || !actor.name().equals(actorName) || actor.receiverName() != null
                || !actor.implementedContracts().isEmpty()
                || !state.actorName().equals(actorName)
                || !init.actorName().equals(actorName)
                || !receive.actorName().equals(actorName)) {
            throw reject("incomplete or mismatched actor declaration");
        }
        for (Ast.CriticalActorMethodDecl method : methods.values()) {
            if (!method.actorName().equals(actorName)) throw reject("foreign method");
        }
        if (!init.runtimeIngress() || !receive.runtimeIngress()
                || init.visibility() != Ast.Visibility.PRIVATE
                || receive.visibility() != Ast.Visibility.PRIVATE
                || !init.parameters().isEmpty() || !isVoid(init.returnType())
                || !emptyReturn(init.body())
                || receive.parameters().size() != 1
                || !isVoid(receive.returnType())) {
            throw reject("private runtime-only init/receive contract mismatch");
        }
        var envelope = receive.parameters().getFirst().type();
        if (!envelope.name().equals("ActorMail") || envelope.isBorrow()
                || envelope.inferArguments() || envelope.arguments().size() != 1
                || receive.parameters().getFirst().structural()
                || receive.parameters().getFirst().mutable()) {
            throw reject("untrusted mailbox shape");
        }

        Map<String, Ast.Program> units = Map.of(image.codeUnitId(), source);
        Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding> bound =
                ActorReceiverBinder.bindSourceSet(packageIdentity, units);
        var states = ActorStateBinder.bindSourceSet(packageIdentity, units);
        var slot = new ActorStateBinder.Slot(packageIdentity, source.namespace(), actorName);
        if (bound.size() != 2 || !states.containsKey(slot)
                || !bound.containsKey(new ActorReceiverBinder.SourceSlot(
                        packageIdentity, source.namespace(), actorName, "init", 0))
                || !bound.containsKey(new ActorReceiverBinder.SourceSlot(
                        packageIdentity, source.namespace(), actorName, "receive", 1))) {
            throw reject("nominal receiver or attached state was not bound");
        }

        var bundle = CriticalActorTruffleV1.compileBundle(image, packageIdentity, actorName);
        if (bundle.methodCount() != methods.size()) throw reject("typed method count mismatch");

        boolean noop = emptyReturn(receive.body());
        Map<Long, List<Action>> routes;
        if (noop) {
            // ADT payloads are accepted only while receive is exactly no-op.
            // This deliberately does not activate ConnectionMail or ChannelMail.
            routes = Map.of();
        } else {
            if (!simple(envelope.arguments().getFirst(), "int")) {
                throw reject("executable dispatch requires ActorMail<int> v2 proof fixture");
            }
            routes = compileLiteralRoutes(receive, bundle.contracts());
        }
        return new Plan(image, actorName, bundle, routes, noop);
    }

    private static boolean simple(Ast.TypeRef type, String name) {
        return type.name().equals(name) && !type.isBorrow()
                && !type.inferArguments() && type.arguments().isEmpty();
    }
    private static boolean isVoid(Ast.TypeRef type) { return simple(type, "void"); }
    private static boolean emptyReturn(List<Ast.Stmt> body) {
        return body.size() == 1 && body.getFirst() instanceof Ast.ReturnStmt ret
                && ret.value() == null;
    }
    private static Ast.CallExpr call(Ast.Expr e) {
        if (!(e instanceof Ast.CallExpr c) || c.origin() != Ast.CallOrigin.DIRECT
                || c.typeArgumentsPresent() || !c.typeArguments().isEmpty()) {
            throw reject("only direct, non-generic, checked actor method calls");
        }
        return c;
    }
    private static boolean receiverMember(Ast.Expr e, String receiver, String member) {
        return e instanceof Ast.MemberExpr m
                && m.member().equals(member)
                && m.receiver() instanceof Ast.NameExpr n
                && n.name().equals(receiver);
    }

    private record Arg(CriticalActorMirV1.Type type, boolean fromMail, Object literal) { }
    private record Action(String method, List<Arg> args, boolean emitResult,
                          Long emitLiteral) {
        Action { args = List.copyOf(args); }
    }

    private static Map<Long, List<Action>> compileLiteralRoutes(
            Ast.ExternalActorMethodDecl receive,
            Map<String, CriticalActorMirV1.Method> contracts) {
        var body = receive.body();
        if (body.size() != 2 || !(body.getFirst() instanceof Ast.IfStmt decisions)
                || !(body.getLast() instanceof Ast.ReturnStmt ret)
                || ret.value() != null || !decisions.elseBody().isEmpty()
                || decisions.branches().isEmpty() || decisions.branches().size() > 32) {
            throw reject("receive needs bounded if/elif literal opcodes and terminal return");
        }
        Map<Long, List<Action>> routes = new LinkedHashMap<>();
        String mailName = receive.parameters().getFirst().name();
        for (Ast.IfBranch branch : decisions.branches()) {
            if (!(branch.condition() instanceof Ast.BinaryExpr guard)
                    || !(guard.operator().equals("eq") || guard.operator().equals("=="))
                    || !(guard.left() instanceof Ast.MemberExpr m)
                    || !(m.receiver() instanceof Ast.NameExpr n)
                    || !n.name().equals(mailName) || !m.member().equals("value")
                    || !(guard.right() instanceof Ast.LiteralExpr literal)
                    || !(literal.value() instanceof Long opcode)) {
                throw reject("route guard must be mail.value eq <int literal>");
            }
            if (branch.body().isEmpty() || branch.body().size() > 2) {
                throw reject("route allows only checked invocation plus optional output");
            }
            List<Action> actions = new ArrayList<>();
            for (int i = 0; i < branch.body().size(); i++) {
                if (!(branch.body().get(i) instanceof Ast.ExprStmt statement)) {
                    throw reject("route cannot declare arbitrary local state or suspend");
                }
                Ast.CallExpr outer = call(statement.expression());
                if (receiverMember(outer.callee(), receive.receiverName(), "send")) {
                    if (outer.arguments().size() != 1) throw reject("send requires one scalar");
                    Ast.Expr payload = outer.arguments().getFirst();
                    if (payload instanceof Ast.LiteralExpr value) {
                        if (!(value.value() instanceof Long) || actions.isEmpty()
                                || i != branch.body().size()-1) {
                            throw reject("constant acknowledgment only after void critical call");
                        }
                        Action last = actions.removeLast();
                        if (last.emitResult() || last.emitLiteral() != null)
                            throw reject("duplicate actor output");
                        actions.add(new Action(last.method(), last.args(), false,
                                (Long) value.value()));
                    } else {
                        if (!actions.isEmpty() || branch.body().size() != 1)
                            throw reject("scalar method output must be only action");
                        actions.add(parseCriticalCall(
                                call(payload), receive, contracts, mailName, true));
                    }
                } else {
                    if (i != 0) throw reject("void critical operation must be first");
                    actions.add(parseCriticalCall(
                            outer, receive, contracts, mailName, false));
                }
            }
            if (actions.size() != 1) throw reject("route requires one critical method");
            if (routes.putIfAbsent(opcode, List.copyOf(actions)) != null) {
                throw reject("duplicate mailbox opcode");
            }
        }
        return Collections.unmodifiableMap(routes);
    }

    private static Action parseCriticalCall(
            Ast.CallExpr call, Ast.ExternalActorMethodDecl receive,
            Map<String, CriticalActorMirV1.Method> contracts, String mailName,
            boolean emitResult) {
        if (!(call.callee() instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr owner)
                || !owner.name().equals(receive.receiverName())) {
            throw reject("only the declared private actor receiver may call a critical method");
        }
        var contract = contracts.get(member.member());
        if (contract == null || call.arguments().size() != contract.parameters().size()) {
            throw reject("method selector or argument count not checked");
        }
        if (emitResult && contract.returns() == CriticalActorMirV1.Type.VOID
                || !emitResult && contract.returns() != CriticalActorMirV1.Type.VOID) {
            throw reject("void/result output mismatch");
        }
        List<Arg> args = new ArrayList<>();
        int i = 0;
        for (var expected : contract.parameters().values()) {
            Ast.Expr source = call.arguments().get(i++);
            if (source instanceof Ast.MemberExpr value
                    && value.receiver() instanceof Ast.NameExpr mail
                    && mail.name().equals(mailName) && value.member().equals("value")) {
                if (expected != CriticalActorMirV1.Type.I64) {
                    throw reject("ActorMail<int> cannot implicitly convert to bool");
                }
                args.add(new Arg(expected, true, null));
            } else if (source instanceof Ast.LiteralExpr constant) {
                Object v = constant.value();
                if (expected == CriticalActorMirV1.Type.I64 && v instanceof Long
                        || expected == CriticalActorMirV1.Type.BOOL && v instanceof Boolean) {
                    args.add(new Arg(expected, false, v));
                } else throw reject("scalar literal must match checked parameter type");
            } else {
                throw reject("critical argument must be copied mailbox value or typed literal");
            }
        }
        return new Action(member.member(), args, emitResult, null);
    }

    public static final class Plan {
        private final SharedCodeImageStore.CodeImage image;
        private final String actor;
        private final CriticalActorTruffleV1.Bundle bundle;
        private final Map<Long, List<Action>> routes;
        private final boolean noop;

        private Plan(SharedCodeImageStore.CodeImage image, String actor,
                     CriticalActorTruffleV1.Bundle bundle,
                     Map<Long, List<Action>> routes, boolean noop) {
            this.image = image;
            this.actor = actor;
            this.bundle = bundle;
            this.routes = routes;
            this.noop = noop;
        }

        public int criticalMethodCount() { return bundle.methodCount(); }
        public boolean failClosedReceive() { return noop; }

        public ActorRuntime.ActorRef<Object> spawn(
                ActorRuntime runtime, IsolatePolicy policy) {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(policy, "spawn isolate policy");
            return runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    policy, new Generated(image, actor, bundle, routes, noop), actor);
        }
    }

    private static final class Generated implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final String actor;
        private final CriticalActorTruffleV1.Bundle bundle;
        private final Map<Long, List<Action>> routes;
        private final boolean noop;

        private Generated(SharedCodeImageStore.CodeImage image, String actor,
                          CriticalActorTruffleV1.Bundle bundle,
                          Map<Long, List<Action>> routes, boolean noop) {
            this.image = image;
            this.actor = actor;
            this.bundle = bundle;
            this.routes = routes;
            this.noop = noop;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName, ActorRuntime.ActorContext<Object> context) {
            verifyActor(actorTypeName);
            return bundle.initializeActor(context);
        }

        @Override public void receiveActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            verifyActor(actorTypeName);
            if (!(raw instanceof CriticalActorTruffleV1.OwnedState state)) {
                throw new SecurityException("unbound owned state");
            }
            if (noop) return; // broker ADT entry remains fail-closed
            if (!(mail.value() instanceof Long op)) {
                throw new SecurityException("untrusted non-i64 mailbox opcode");
            }
            List<Action> route = routes.get(op);
            if (route == null) return; // unrecognized opcode has no guest authority
            for (Action action : route) {
                Object[] args = new Object[action.args().size()];
                for (int i=0; i<args.length; i++) {
                    Arg arg = action.args().get(i);
                    args[i] = arg.fromMail() ? op : arg.literal();
                }
                Object result = bundle.invokeFromMailbox(
                        action.method(), state, mail, context, args);
                if (action.emitResult()) context.runtime().emitCurrentActorOutput(result);
                if (action.emitLiteral() != null)
                    context.runtime().emitCurrentActorOutput(action.emitLiteral());
            }
        }

        @Override public void tearDownActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorContext<Object> context) {
            verifyActor(actorTypeName);
            ((CriticalActorTruffleV1.OwnedState) raw).close();
        }

        private void verifyActor(String name) {
            if (!actor.equals(name)) throw new SecurityException("cross-actor ingress");
        }
    }
}
