package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.nodes.CriticalActorTruffleV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.CriticalActorMirV1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Source-checked, exhaustive nominal ActorMail<ADT> ingress v3. The only
 * admitted payload is a closed-world, immutable and bounded collection
 * containing a nominal OresSymbol, a variant OresSymbol and copied primitive
 * fields. PRIVATE ActorRuntime transports copy the envelope; this is not yet
 * a zero-copy shared ADT arena or an unforgeable runtime capability.
 *
 * Source match arms authorize only one selected private critical actor
 * method, with value arguments from the variant's typed payload bindings or
 * matching primitive literals. No guest-exposed ActorContext, dynamic
 * callable, fallback handler, await, raw pointer, or method reference.
 *
 * Authentication/activation grants MUST NOT be represented by this format:
 * symbols and immutable data do not grant privilege. Broker source keeps
 * authenticated/activated false; protected operations stay denied.
 */
public final class NamespaceActorIngressV3 {
    private NamespaceActorIngressV3() { }

    private static IllegalArgumentException denied(String detail) {
        return new IllegalArgumentException("NAMESPACE_ACTOR_TYPED_ADT_V3_REJECTED: " + detail);
    }

    private record Field(String name, CriticalActorMirV1.Type type) { }
    private record Variant(OresSymbol symbol, List<Field> fields) {
        Variant { fields = List.copyOf(fields); }
    }
    private record Value(String bound, Object literal, CriticalActorMirV1.Type type) { }
    private record Route(String target, List<Value> arguments, boolean emits) {
        Route { arguments = List.copyOf(arguments); }
    }

    public static Plan compile(
            SharedCodeImageStore.CodeImage image,
            String packageIdentity, String actorName) {
        Objects.requireNonNull(image, "published CodeImage");
        Objects.requireNonNull(packageIdentity, "package identity");
        Objects.requireNonNull(actorName, "actor identity");
        if (packageIdentity.isBlank() || actorName.isBlank()) throw denied("missing identities");
        Ast.Program source = image.program();
        if (source.namespace() == null || source.namespace().isBlank()
                || !source.imports().isEmpty() || source.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(source.modules().getFirst().name())) {
            throw denied("requires one explicit namespace root, no imports");
        }
        var root = source.modules().getFirst();
        if (!root.annotations().isEmpty() || !root.contracts().isEmpty()) {
            throw denied("module annotations or contracts are not checked for execution");
        }
        Ast.StandaloneActorDecl actor = null;
        Ast.ActorStateDecl state = null;
        Ast.MailEnumDecl mailEnum = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        Map<String, Ast.CriticalActorMethodDecl> methods = new LinkedHashMap<>();
        for (Ast.Decl declaration : root.declarations()) {
            if (declaration instanceof Ast.StandaloneActorDecl a && actor == null) actor = a;
            else if (declaration instanceof Ast.ActorStateDecl a && state == null) state = a;
            else if (declaration instanceof Ast.MailEnumDecl e && mailEnum == null) mailEnum = e;
            else if (declaration instanceof Ast.ExternalActorMethodDecl m) {
                if (m.name().equals("init") && init == null) init = m;
                else if (m.name().equals("receive") && receive == null) receive = m;
                else throw denied("duplicate or unsupported external actor callback");
            } else if (declaration instanceof Ast.CriticalActorMethodDecl m) {
                if (methods.putIfAbsent(m.name(), m) != null)
                    throw denied("duplicate critical selector");
            } else throw denied("unexpected declaration in nominal actor source");
        }
        if (actor == null || state == null || mailEnum == null || init == null
                || receive == null || methods.isEmpty() || methods.size() > 32
                || !actor.name().equals(actorName)
                || actor.receiverName() != null || !actor.implementedContracts().isEmpty()
                || !state.actorName().equals(actorName)
                || actorName.equals(mailEnum.name())
                || mailEnum.visibility() != Ast.Visibility.PRIVATE
                || !init.actorName().equals(actorName)
                || !receive.actorName().equals(actorName)) {
            throw denied("nominal actor, mail enum and sealed state must be distinct and complete");
        }
        for (var m : methods.values()) {
            if (!m.actorName().equals(actorName)) throw denied("foreign critical method");
        }
        if (!init.runtimeIngress() || !receive.runtimeIngress()
                || init.visibility() != Ast.Visibility.PRIVATE
                || receive.visibility() != Ast.Visibility.PRIVATE
                || !init.parameters().isEmpty() || !voidType(init.returnType())
                || !emptyReturn(init.body())
                || receive.parameters().size() != 1 || !voidType(receive.returnType())) {
            throw denied("private runtime-only init/receive contract violation");
        }
        Ast.Param mail = receive.parameters().getFirst();
        Ast.TypeRef type = mail.type();
        if (mail.mutable() || mail.structural() || type.isBorrow()
                || type.inferArguments() || !type.name().equals("ActorMail")
                || type.arguments().size() != 1
                || !scalarType(type.arguments().getFirst(), mailEnum.name())) {
            throw denied("receive must take ActorMail<own nominal enum>");
        }

        Map<String, Ast.Program> sources = Map.of(image.codeUnitId(), source);
        var bound = ActorReceiverBinder.bindSourceSet(packageIdentity, sources);
        var layouts = ActorStateBinder.bindSourceSet(packageIdentity, sources);
        var slot = new ActorStateBinder.Slot(
                packageIdentity, source.namespace(), actorName);
        if (bound.size() != 2 || !layouts.containsKey(slot)
                || !bound.containsKey(new ActorReceiverBinder.SourceSlot(
                        packageIdentity, source.namespace(), actorName, "init", 0))
                || !bound.containsKey(new ActorReceiverBinder.SourceSlot(
                        packageIdentity, source.namespace(), actorName, "receive", 1))) {
            throw denied("source nominal actor/state/ingress binding failed");
        }
        // These fields are minted or seeded only by the trusted actor
        // runtime. User-authored mail, even when nominal and well typed,
        // is NEVER an authentication or generation capability.
        for (Ast.ProtectedStateField field : state.fields()) {
            if ((field.name().equals("authenticated")
                    || field.name().equals("activated"))
                    && (!(field.initializer() instanceof Ast.LiteralExpr initial)
                        || !Boolean.FALSE.equals(initial.value()))) {
                throw denied("runtime-only actor activation must initialize false");
            }
        }
        var typedByActor = CriticalActorMirV1.lowerSourceSet(
                packageIdentity, sources).get(slot);
        if (typedByActor == null || typedByActor.size() != methods.size()) {
            throw denied("typed critical method set incomplete");
        }
        for (var method : typedByActor) {
            rejectRuntimeOnlyWrites(method.steps());
        }
        var bundle = CriticalActorTruffleV1.compileBundle(
                image, packageIdentity, actorName);
        if (bundle.methodCount() != methods.size()) {
            throw denied("typed critical method set mismatch");
        }

        String nominal = packageIdentity + ":" + source.namespace() + "." + mailEnum.name();
        OresSymbol typeSymbol = OresSymbol.process(nominal);
        Map<String, Variant> variants = new LinkedHashMap<>();
        for (var v : mailEnum.variants()) {
            List<Field> fields = new ArrayList<>();
            for (var p : v.fields()) {
                CriticalActorMirV1.Type primitive = switch (p.type().name()) {
                    case "int" -> CriticalActorMirV1.Type.I64;
                    case "bool" -> CriticalActorMirV1.Type.BOOL;
                    default -> throw denied("non-scalar actor variant payload");
                };
                fields.add(new Field(p.name(), primitive));
            }
            if (variants.putIfAbsent(v.name(),
                    new Variant(OresSymbol.process(nominal + "." + v.name()), fields)) != null) {
                throw denied("duplicate variant");
            }
        }
        Map<String, Route> routes = compileRoutes(receive, mailEnum, variants,
                bundle.contracts());
        return new Plan(image, actorName, typeSymbol, variants, routes, bundle);
    }

    private static void rejectRuntimeOnlyWrites(List<CriticalActorMirV1.Step> steps) {
        for (var step : steps) {
            if (step instanceof CriticalActorMirV1.SetField write
                    && Set.of("authenticated", "activated", "generation",
                            "connection_generation", "connection_id",
                            "channel_id", "frame_max").contains(write.field())) {
                throw denied("critical user method cannot mint runtime-only identity/authority");
            }
            if (step instanceof CriticalActorMirV1.Branch branch) {
                rejectRuntimeOnlyWrites(branch.yes());
                rejectRuntimeOnlyWrites(branch.no());
            } else if (step instanceof CriticalActorMirV1.WriteLoan loan) {
                rejectRuntimeOnlyWrites(loan.body());
            }
        }
    }

    private static boolean scalarType(Ast.TypeRef type, String name) {
        return type.name().equals(name) && !type.inferArguments()
                && !type.isBorrow() && type.arguments().isEmpty();
    }
    private static boolean voidType(Ast.TypeRef type) {
        return scalarType(type, "void");
    }
    private static boolean emptyReturn(List<Ast.Stmt> statements) {
        return statements.size() == 1
                && statements.getFirst() instanceof Ast.ReturnStmt r
                && r.value() == null;
    }
    private static Ast.CallExpr exactCall(Ast.Expr expr) {
        if (!(expr instanceof Ast.CallExpr c) || c.origin() != Ast.CallOrigin.DIRECT
                || c.typeArgumentsPresent() || !c.typeArguments().isEmpty()) {
            throw denied("actor receive may only call an exact private method");
        }
        return c;
    }
    private static boolean member(Ast.Expr e, String name, String field) {
        return e instanceof Ast.MemberExpr m && m.member().equals(field)
                && m.receiver() instanceof Ast.NameExpr n && n.name().equals(name);
    }
    private static Map<String, Route> compileRoutes(
            Ast.ExternalActorMethodDecl receive, Ast.MailEnumDecl enumDecl,
            Map<String, Variant> variants,
            Map<String, CriticalActorMirV1.Method> methods) {
        List<Ast.Stmt> body = receive.body();
        if (body.size() != 2 || !(body.getFirst() instanceof Ast.MatchStmt match)
                || !match.ordered() || match.procedural()
                || !(body.getLast() instanceof Ast.ReturnStmt ret)
                || ret.value() != null
                || !member(match.subject(), receive.parameters().getFirst().name(), "value")
                || match.arms().size() != variants.size()) {
            throw denied("receive must exhaustively match exactly its typed mailbox variants");
        }
        Map<String, Route> routes = new LinkedHashMap<>();
        for (var arm : match.arms()) {
            if (arm.guard() != null
                    || !(arm.pattern() instanceof Ast.ConstructorPattern p)
                    || !p.constructor().startsWith(enumDecl.name() + ".")) {
                throw denied("mail arms require qualified nominal constructors without guards");
            }
            String variantName = p.constructor().substring(enumDecl.name().length() + 1);
            Variant v = variants.get(variantName);
            if (v == null || p.arguments().size() != v.fields().size()) {
                throw denied("variant payload arity mismatch or foreign variant");
            }
            Map<String, CriticalActorMirV1.Type> bindings = new LinkedHashMap<>();
            for (int i = 0; i < p.arguments().size(); i++) {
                if (!(p.arguments().get(i) instanceof Ast.BindingPattern pattern)
                        || bindings.putIfAbsent(pattern.name(), v.fields().get(i).type()) != null
                        || pattern.name().equals(receive.receiverName())
                        || pattern.name().equals(receive.parameters().getFirst().name())) {
                    throw denied("variant fields must bind uniquely without shadowing");
                }
            }
            if (arm.body().size() != 1 || !(arm.body().getFirst() instanceof Ast.ExprStmt st)) {
                throw denied("each variant may invoke exactly one non-suspending critical method");
            }
            Ast.CallExpr outer = exactCall(st.expression());
            boolean emits = member(outer.callee(), receive.receiverName(), "send");
            Ast.CallExpr critical;
            if (emits) {
                if (outer.arguments().size() != 1) throw denied("send needs one checked result");
                critical = exactCall(outer.arguments().getFirst());
            } else critical = outer;
            if (!(critical.callee() instanceof Ast.MemberExpr method)
                    || !(method.receiver() instanceof Ast.NameExpr owner)
                    || !owner.name().equals(receive.receiverName())) {
                throw denied("critical call must use the nominal actor receiver");
            }
            CriticalActorMirV1.Method contract = methods.get(method.member());
            if (contract == null || contract.parameters().size() != critical.arguments().size()
                    || (contract.returns() == CriticalActorMirV1.Type.VOID) == emits) {
                throw denied("unknown method, wrong arity or unverified output type");
            }
            List<Value> arguments = new ArrayList<>();
            int i = 0;
            for (var expected : contract.parameters().values()) {
                Ast.Expr argument = critical.arguments().get(i++);
                if (argument instanceof Ast.NameExpr variable
                        && bindings.get(variable.name()) == expected) {
                    arguments.add(new Value(variable.name(), null, expected));
                } else if (argument instanceof Ast.LiteralExpr lit
                        && (expected == CriticalActorMirV1.Type.I64
                                && lit.value() instanceof Long
                                || expected == CriticalActorMirV1.Type.BOOL
                                && lit.value() instanceof Boolean)) {
                    arguments.add(new Value(null, lit.value(), expected));
                } else {
                    throw denied("argument must be a checked variant field or typed literal");
                }
            }
            if (routes.putIfAbsent(variantName,
                    new Route(method.member(), arguments, emits)) != null) {
                throw denied("duplicate variant route");
            }
        }
        if (!routes.keySet().equals(variants.keySet())) {
            throw denied("non-exhaustive actor mail match; no wildcard fallback");
        }
        return Collections.unmodifiableMap(routes);
    }

    public static final class Plan {
        private final SharedCodeImageStore.CodeImage image;
        private final String actor;
        private final OresSymbol nominal;
        private final Map<String, Variant> variants;
        private final Map<String, Route> routes;
        private final CriticalActorTruffleV1.Bundle bundle;

        private Plan(SharedCodeImageStore.CodeImage image, String actor, OresSymbol nominal,
                     Map<String, Variant> variants, Map<String, Route> routes,
                     CriticalActorTruffleV1.Bundle bundle) {
            this.image = image;
            this.actor = actor;
            this.nominal = nominal;
            this.variants = Map.copyOf(variants);
            this.routes = routes;
            this.bundle = bundle;
        }

        public int variantCount() { return variants.size(); }
        public int criticalMethodCount() { return bundle.methodCount(); }

        /**
         * Test/host ingress constructor for immutable copied primitive data.
         * This is NOT an AuthGrant or ChannelActivationGrant. A host-supplied
         * value is validated again after ActorRuntime isolation at receive.
         */
        public List<Object> message(String variantName, Object... payload) {
            Variant v = variants.get(variantName);
            if (v == null) throw denied("unknown nominal mail variant");
            Objects.requireNonNull(payload, "mail fields");
            if (payload.length != v.fields().size()) {
                throw denied("mail variant arity mismatch");
            }
            List<Object> fields = new ArrayList<>();
            for (int i=0; i<payload.length; i++) {
                Object arg = payload[i];
                if (!matches(v.fields().get(i).type(), arg)) {
                    throw denied("mail variant payload type mismatch");
                }
                fields.add(arg);
            }
            return List.of(nominal, v.symbol(), List.copyOf(fields));
        }

        public ActorRuntime.ActorRef<Object> spawn(
                ActorRuntime runtime, IsolatePolicy policy) {
            Objects.requireNonNull(runtime, "actor runtime");
            Objects.requireNonNull(policy, "actor isolate policy");
            return runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    policy, new Generated(image, actor, nominal, variants, routes, bundle),
                    actor);
        }
    }

    private static boolean matches(CriticalActorMirV1.Type type, Object arg) {
        return type == CriticalActorMirV1.Type.I64 && arg instanceof Long
                || type == CriticalActorMirV1.Type.BOOL && arg instanceof Boolean;
    }

    private static final class Generated implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final String actor;
        private final OresSymbol nominal;
        private final Map<String, Variant> variants;
        private final Map<String, Route> routes;
        private final CriticalActorTruffleV1.Bundle bundle;

        private Generated(SharedCodeImageStore.CodeImage image, String actor,
                          OresSymbol nominal, Map<String, Variant> variants,
                          Map<String, Route> routes, CriticalActorTruffleV1.Bundle bundle) {
            this.image = image;
            this.actor = actor;
            this.nominal = nominal;
            this.variants = variants;
            this.routes = routes;
            this.bundle = bundle;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorType, ActorRuntime.ActorContext<Object> context) {
            requireActor(actorType);
            return bundle.initializeActor(context);
        }

        @Override public void receiveActor(
                String actorType, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> envelope,
                ActorRuntime.ActorContext<Object> context) {
            requireActor(actorType);
            if (!(raw instanceof CriticalActorTruffleV1.OwnedState state)
                    || !(envelope.value() instanceof List<?> tagged) || tagged.size() != 3
                    || !nominal.equals(tagged.get(0))
                    || !(tagged.get(1) instanceof OresSymbol variant)
                    || !(tagged.get(2) instanceof List<?> fields)) {
                throw new SecurityException("invalid nominal ActorMail ADT ingress");
            }
            Variant selected = null;
            String name = null;
            for (var item : variants.entrySet()) {
                if (item.getValue().symbol().equals(variant)) {
                    selected = item.getValue();
                    name = item.getKey();
                    break;
                }
            }
            if (selected == null || selected.fields().size() != fields.size()) {
                throw new SecurityException("foreign/unrecognized actor mail variant");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i=0; i<fields.size(); i++) {
                Field field = selected.fields().get(i);
                Object arg = fields.get(i);
                if (!matches(field.type(), arg)) {
                    throw new SecurityException("nominal actor mail field has wrong scalar type");
                }
                values.put(field.name(), arg);
            }
            Route route = routes.get(name);
            if (route == null) throw new SecurityException("unmatched enum variant");
            Object[] args = new Object[route.arguments().size()];
            for (int i=0; i<args.length; i++) {
                Value arg = route.arguments().get(i);
                if (arg.bound() != null) {
                    if (!values.containsKey(arg.bound())) throw new SecurityException(
                            "unbound variant field");
                    args[i] = values.get(arg.bound());
                } else args[i] = arg.literal();
            }
            Object result = bundle.invokeFromMailbox(
                    route.target(), state, envelope, context, args);
            // Output requires its own bounded actor channel, after the loan
            // commits and releases. It cannot leak the mutable state arena.
            if (route.emits()) context.runtime().emitCurrentActorOutput(result);
        }

        @Override public void tearDownActor(
                String actorType, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorContext<Object> context) {
            requireActor(actorType);
            ((CriticalActorTruffleV1.OwnedState) raw).close();
        }

        private void requireActor(String name) {
            if (!actor.equals(name)) throw new SecurityException("foreign actor ingress");
        }
    }
}
