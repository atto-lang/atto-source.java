package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First typed, immutable and multi-field critical-method IR for declared actors.
 *
 * <p>Trusted compiler-internal staging only. The normal TypeChecker is still
 * fail-closed: this is not general Graal guest, native, LLVM or arbitrary source
 * execution. There is no source-callable host dispatch or authority to mint a
 * loan from user code. The accepted IR is deliberately limited to checked i64
 * arithmetic, actor-owned scalar fields, and self critical calls in receive.
 */
final class NamespaceActorCriticalIrCompiler {
    private NamespaceActorCriticalIrCompiler() { }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("NAMESPACE_ACTOR_CRITICAL_IR_UNSUPPORTED: " + reason);
    }

    /**
     * Compiler-issued, immutable descriptor. Never decoded from source or
     * supplied by a guest as an authority. The code image is an opaque,
     * nonforgeable SharedCodeImageStore object with exact AST identity.
     */
    static final class VerifiedActorDescriptor {
        private final SharedCodeImageStore.CodeImage image;
        private final String namespace;
        private final String actorName;
        private final Map<String, Integer> fields;
        private final Set<String> criticalMethods;

        private VerifiedActorDescriptor(
                SharedCodeImageStore.CodeImage image, String namespace,
                String actorName, Map<String, Integer> fields,
                Set<String> criticalMethods) {
            this.image = Objects.requireNonNull(image);
            this.namespace = Objects.requireNonNull(namespace);
            this.actorName = Objects.requireNonNull(actorName);
            this.fields = Map.copyOf(fields);
            this.criticalMethods = Set.copyOf(criticalMethods);
            if (this.namespace.isBlank() || this.actorName.isBlank()
                    || this.fields.isEmpty() || this.criticalMethods.isEmpty()
                    || image.program().namespace() == null
                    || !this.namespace.equals(image.program().namespace())) {
                throw new SecurityException("invalid compiler-issued actor descriptor");
            }
        }

        SharedCodeImageStore.CodeImage codeImage() { return image; }
        String namespace() { return namespace; }
        String actorName() { return actorName; }
        Map<String, Integer> scalarFields() { return fields; }
        Set<String> criticalMethods() { return criticalMethods; }
    }

    /** Read-only compiler metadata, never a guest-visible bearer capability. */
    static VerifiedActorDescriptor descriptor(ActorRuntime.ActorCodeExecutor executor) {
        if (!(executor instanceof Executable compiled)
                || compiled.descriptor().codeImage() != compiled.codeImage()) {
            throw new SecurityException("actor has no verified code-image descriptor");
        }
        return compiled.descriptor();
    }

    private record InitialField(String name, long value) { }

    /**
     * Immutable diagnostic record of actor-owned state after an admitted turn.
     * This is never a state alias, borrow permit, persistence record or
     * source-visible field accessor; state itself remains actor-owned.
     */
    record StateReceipt(ActorRuntime.ActorId actorId, long generation,
                        long processed, Map<String, Long> fields) {
        StateReceipt {
            Objects.requireNonNull(actorId, "actor identity");
            fields = Map.copyOf(fields);
            if (processed < 0) throw new IllegalArgumentException("negative processed count");
        }
        long require(String name) {
            Long value = fields.get(name);
            if (value == null) throw new IllegalArgumentException(
                    "checked actor has no scalar field '" + name + "'");
            return value;
        }
    }

    /** Read-only host diagnostic after drain; not available in .atto source. */
    static StateReceipt receiptAfterDrain(ActorRuntime.ActorCodeExecutor executor,
            ActorRuntime.ActorRef<Object> ref) {
        Objects.requireNonNull(ref, "actor ref");
        if (!(executor instanceof Executable compiled)) {
            throw new SecurityException("unverified actor compiler cannot mint a state receipt");
        }
        return compiled.readReceipt(ref);
    }
    private record Write(int slot, IntValue value) { }
    private record CriticalPlan(String name, List<Write> writes) {
        private CriticalPlan { writes = List.copyOf(writes); }
    }

    /** No dynamic calls, borrows, external references, await or unchecked arithmetic. */
    private sealed interface IntValue permits Constant, Parameter, Read, Add, Subtract {
        long evaluate(long[] local, long input);
    }
    private record Constant(long number) implements IntValue {
        @Override public long evaluate(long[] local, long input) { return number; }
    }
    private record Parameter() implements IntValue {
        @Override public long evaluate(long[] local, long input) { return input; }
    }
    private record Read(int slot) implements IntValue {
        @Override public long evaluate(long[] local, long input) { return local[slot]; }
    }
    private record Add(IntValue left, IntValue right) implements IntValue {
        @Override public long evaluate(long[] local, long input) {
            return Math.addExact(left.evaluate(local, input), right.evaluate(local, input));
        }
    }
    private record Subtract(IntValue left, IntValue right) implements IntValue {
        @Override public long evaluate(long[] local, long input) {
            return Math.subtractExact(left.evaluate(local, input), right.evaluate(local, input));
        }
    }

    static ActorRuntime.ActorCodeExecutor compile(SharedCodeImageStore.CodeImage image) {
        return compileInternal(image, false);
    }

    /**
     * Source-level spawn v0: allow exactly one main function to accompany
     * the fully verified namespace actor declaration. The caller MUST check
     * every main statement using a separate closed-world typed admission.
     * The ordinary compiler remains fail closed for all other programs.
     */
    static ActorRuntime.ActorCodeExecutor compileForVerifiedMain(
            SharedCodeImageStore.CodeImage image) {
        return compileInternal(image, true);
    }

    private static ActorRuntime.ActorCodeExecutor compileInternal(
            SharedCodeImageStore.CodeImage image, boolean admitMain) {
        Objects.requireNonNull(image, "immutable source image");
        Ast.Program program = image.program();
        if (program.namespace() == null || program.namespace().isBlank()
                || program.modules().size() != 1
                || !Parser.ROOT_MODULE.equals(program.modules().getFirst().name())) {
            throw reject("only one explicit namespace root unit is supported");
        }

        Ast.StandaloneActorDecl actor = null;
        Ast.ActorStateDecl state = null;
        Ast.ExternalActorMethodDecl init = null;
        Ast.ExternalActorMethodDecl receive = null;
        List<Ast.ExternalActorMethodDecl> critical = new ArrayList<>();
        boolean sawEntry = false;
        for (Ast.Decl declaration : program.modules().getFirst().declarations()) {
            if (declaration instanceof Ast.StandaloneActorDecl a) {
                if (actor != null) throw reject("duplicate actor");
                actor = a;
            } else if (declaration instanceof Ast.ActorStateDecl s) {
                if (state != null) throw reject("duplicate state");
                state = s;
            } else if (admitMain && declaration instanceof Ast.FunctionDecl fn
                    && fn.name().equals("main") && !sawEntry) {
                // Only the closed-world source-spawn compiler validates the
                // main body. Nothing in this routine executes its AST.
                sawEntry = true;
            } else if (declaration instanceof Ast.ExternalActorMethodDecl m) {
                if (m.name().equals("init")) {
                    if (init != null) throw reject("duplicate init");
                    init = m;
                } else if (m.name().equals("receive")) {
                    if (receive != null) throw reject("duplicate receive");
                    receive = m;
                } else if (m.criticalMethod()) {
                    critical.add(m);
                } else {
                    throw reject("noncritical actor methods are not lowered");
                }
            } else {
                throw reject("unsupported namespace declaration");
            }
        }
        if (admitMain && !sawEntry) throw reject("source spawn requires checked main");
        if (actor == null || state == null || init == null || receive == null
                || critical.isEmpty() || critical.size() > 8
                || actor.actorKind() != Ast.ActorKind.SHARED
                || actor.receiverName() != null
                || !actor.implementedContracts().isEmpty()
                || !state.actorName().equals(actor.name())
                || state.fields().size() > 8) {
            throw reject("requires one plain actor, 1..8 scalar fields and 1..8 critical methods");
        }
        // Binding is a prerequisite, never sufficient authority for runtime.
        ActorReceiverBinder.bind(program);
        ActorStateBinder.bindSourceSet("critical-ir-internal@1",
                Map.of("src/internal-critical-ir.atto", program));

        Map<String, Integer> slots = new LinkedHashMap<>();
        List<InitialField> fields = new ArrayList<>();
        for (Ast.ProtectedStateField field : state.fields()) {
            if (!scalar(field.type())
                    || !(field.initializer() instanceof Ast.LiteralExpr literal)
                    || !(literal.value() instanceof Long n)
                    || slots.putIfAbsent(field.name(), fields.size()) != null) {
                throw reject("scalar state requires distinct int fields with constant i64 initializers");
            }
            fields.add(new InitialField(field.name(), n));
        }
        if (!ingress(actor.name(), init, "init") || !init.parameters().isEmpty()
                || !bareReturn(init.body())
                || !ingress(actor.name(), receive, "receive")
                || receive.parameters().size() != 1) {
            throw reject("private runtime-only init/receive are required");
        }
        Ast.Param mail = receive.parameters().getFirst();
        if (!mail.type().name().equals("ActorMail")
                || mail.type().isBorrow() || mail.type().inferArguments()
                || mail.type().arguments().size() != 1
                || !scalar(mail.type().arguments().getFirst())) {
            throw reject("receive requires owned ActorMail<int>");
        }

        Map<String, CriticalPlan> methods = new HashMap<>();
        for (Ast.ExternalActorMethodDecl method : critical) {
            if (method.runtimeIngress() || !method.criticalMethod()
                    || method.visibility() != Ast.Visibility.PRIVATE
                    || !method.actorName().equals(actor.name())
                    || !type(method.returnType(), "void")
                    || method.parameters().size() != 1
                    || !scalar(method.parameters().getFirst().type())
                    || method.body().isEmpty() || method.body().size() > 4) {
                throw reject("critical method requires one int parameter, void result and 1..4 writes");
            }
            String param = method.parameters().getFirst().name();
            List<Write> writes = new ArrayList<>();
            for (Ast.Stmt stmt : method.body()) {
                if (!(stmt instanceof Ast.ExprStmt exprStmt)
                        || !(exprStmt.expression() instanceof Ast.AssignExpr assign)) {
                    throw reject("critical method body must consist only of state writes");
                }
                int target = fieldSlot(assign.target(), method.receiverName(), slots);
                IntValue value = lowerValue(assign.value(), method.receiverName(), param, slots, 0);
                writes.add(new Write(target, value));
            }
            if (methods.putIfAbsent(method.name(), new CriticalPlan(method.name(), writes)) != null) {
                throw reject("duplicate critical method");
            }
        }

        List<CriticalPlan> calls = new ArrayList<>();
        int outputFieldSlot = -1;
        List<Ast.Stmt> statements = receive.body();
        if (statements.size() < 2 || statements.size() > 10
                || !(statements.getLast() instanceof Ast.ReturnStmt end)
                || end.value() != null) {
            throw reject("receive requires 1..8 checked critical calls, optional final self.send(state.field), then return");
        }
        for (int i = 0; i < statements.size() - 1; i++) {
            if (!(statements.get(i) instanceof Ast.ExprStmt exprStmt)
                    || !(exprStmt.expression() instanceof Ast.CallExpr call)
                    || call.origin() != Ast.CallOrigin.DIRECT
                    || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                    || call.arguments().size() != 1
                    || !(call.callee() instanceof Ast.MemberExpr member)
                    || !isName(member.receiver(), receive.receiverName())) {
                throw reject("receive may only invoke own checked critical methods or final owned scalar output");
            }
            if (member.member().equals("send")) {
                // This is the actor's own output stream, not an ActorRef
                // mailbox send. Only its private physical turn can emit.
                // Never expose Owned, a state alias, or a diagnostic receipt.
                if (i != statements.size() - 2 || calls.isEmpty()) {
                    throw reject("source actor output must follow critical calls and precede return");
                }
                outputFieldSlot = fieldSlot(
                        call.arguments().getFirst(), receive.receiverName(), slots);
                continue;
            }
            if (!(call.arguments().getFirst() instanceof Ast.MemberExpr value)
                    || !isName(value.receiver(), mail.name())
                    || !value.member().equals("value")) {
                throw reject("critical method argument must be owned ActorMail<int>.value");
            }
            CriticalPlan plan = methods.get(member.member());
            if (plan == null) throw reject("receive calls an unverified method");
            if (calls.size() >= 8) throw reject("receive critical call budget exceeded");
            calls.add(plan);
        }
        if (calls.isEmpty() || !new HashSet<>(methods.keySet()).containsAll(
                calls.stream().map(CriticalPlan::name).toList())) {
            throw reject("critical method call graph is not statically closed");
        }
        long[] initial = fields.stream().mapToLong(InitialField::value).toArray();
        VerifiedActorDescriptor descriptor = new VerifiedActorDescriptor(
                image, program.namespace(), actor.name(),
                slots, methods.keySet());
        return new Executable(image, actor.name(), initial,
                Map.copyOf(slots), List.copyOf(calls), outputFieldSlot,
                new ConcurrentHashMap<>(), descriptor);
    }

    private static IntValue lowerValue(Ast.Expr value, String receiver, String param,
            Map<String, Integer> slots, int depth) {
        if (depth > 12) throw reject("critical expression recursion budget exceeded");
        if (value instanceof Ast.LiteralExpr lit && lit.value() instanceof Long n)
            return new Constant(n);
        if (isName(value, param)) return new Parameter();
        if (value instanceof Ast.MemberExpr) {
            return new Read(fieldSlot(value, receiver, slots));
        }
        if (value instanceof Ast.BinaryExpr binary) {
            IntValue left = lowerValue(binary.left(), receiver, param, slots, depth + 1);
            IntValue right = lowerValue(binary.right(), receiver, param, slots, depth + 1);
            if (binary.operator().equals("+")) return new Add(left, right);
            if (binary.operator().equals("-")) return new Subtract(left, right);
        }
        throw reject("only checked i64 literals, fields, parameters and +/- are allowed");
    }

    private static int fieldSlot(Ast.Expr value, String receiver, Map<String, Integer> slots) {
        if (!(value instanceof Ast.MemberExpr field)
                || !(field.receiver() instanceof Ast.MemberExpr state)
                || !isName(state.receiver(), receiver)
                || !state.member().equals("state")
                || !slots.containsKey(field.member())) {
            throw reject("critical reads and writes require this-actor state fields");
        }
        return slots.get(field.member());
    }
    private static boolean isName(Ast.Expr value, String expected) {
        return value instanceof Ast.NameExpr name && name.name().equals(expected);
    }
    private static boolean type(Ast.TypeRef ref, String name) {
        return ref.name().equals(name) && ref.arguments().isEmpty()
                && !ref.isBorrow() && !ref.inferArguments();
    }
    private static boolean scalar(Ast.TypeRef ref) { return type(ref, "int"); }
    private static boolean ingress(String actor, Ast.ExternalActorMethodDecl method, String name) {
        return method.name().equals(name) && method.actorName().equals(actor)
                && method.runtimeIngress() && !method.criticalMethod()
                && method.visibility() == Ast.Visibility.PRIVATE
                && type(method.returnType(), "void");
    }
    private static boolean bareReturn(List<Ast.Stmt> stmts) {
        return stmts.size() == 1 && stmts.getFirst() instanceof Ast.ReturnStmt ret
                && ret.value() == null;
    }

    private static final class Owned implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorRef<Object> actor;
        private final long[] values;
        private WriteLoan activeLoan;

        private Owned(ActorRuntime.ActorRef<Object> actor, long[] initial) {
            this.actor = Objects.requireNonNull(actor);
            this.values = initial.clone();
        }
        @Override public ActorRuntime.ActorId ownerActorId() { return actor.id(); }

        private void apply(CriticalPlan plan, long input,
                ActorRuntime.ActorContext<Object> context) {
            if (activeLoan != null)
                throw new SecurityException("reentrant critical mutable loan");
            WriteLoan loan = new WriteLoan(this, context);
            activeLoan = loan;
            try {
                // Stage all writes, then commit atomically on successful checked
                // evaluation. Overflow cannot partially mutate owned state.
                long[] next = values.clone();
                for (Write write : plan.writes()) {
                    loan.verify(context);
                    next[write.slot()] = write.value().evaluate(next, input);
                }
                loan.verify(context);
                System.arraycopy(next, 0, values, 0, next.length);
            } finally {
                loan.close();
                activeLoan = null;
            }
        }
    }

    /** Compiler-private, nonescaping physical-turn mutable authority. */
    private static final class WriteLoan {
        private final Owned owner;
        private final ActorRuntime.ActorRef<Object> actor;
        private final ActorRuntime runtime;
        private final Thread carrier;
        private boolean live = true;

        private WriteLoan(Owned owner, ActorRuntime.ActorContext<Object> context) {
            this.owner = owner;
            this.actor = context.self();
            this.runtime = context.runtime();
            this.carrier = Thread.currentThread();
            verify(context);
        }
        private void verify(ActorRuntime.ActorContext<Object> context) {
            if (!live || owner.activeLoan != this && owner.activeLoan != null
                    || owner.actor != actor || context.self() != actor
                    || runtime != context.runtime() || carrier != Thread.currentThread()
                    || ActorRuntime.currentActorRuntime() != runtime
                    || !actor.id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("critical loan requires live owner turn and carrier");
            }
        }
        private void close() { live = false; }
    }

    private record Executable(SharedCodeImageStore.CodeImage codeImage,
            String actorName, long[] initial, Map<String, Integer> slots,
            List<CriticalPlan> calls, int outputFieldSlot,
            ConcurrentHashMap<ActorRuntime.ActorId, StateReceipt> receipts,
            VerifiedActorDescriptor descriptor)
            implements ActorRuntime.ActorCodeExecutor {
        private StateReceipt snapshotOf(Owned owned, long processed) {
            Map<String, Long> frozen = new LinkedHashMap<>();
            for (var entry : slots.entrySet()) {
                frozen.put(entry.getKey(), owned.values[entry.getValue()]);
            }
            return new StateReceipt(owned.actor.id(), owned.actor.generation(),
                    processed, frozen);
        }

        private StateReceipt readReceipt(ActorRuntime.ActorRef<Object> ref) {
            StateReceipt receipt = receipts.get(ref.id());
            if (receipt == null
                    || !receipt.actorId().equals(ref.id())
                    || receipt.generation() != ref.generation()
                    || ref.kind() != ActorRuntime.ActorKind.SHARED
                    || !ref.isAlive()) {
                throw new SecurityException(
                        "state receipt requires same live actor incarnation");
            }
            return receipt;
        }

        private void requireTurn(String name, ActorRuntime.ActorContext<Object> context) {
            if (descriptor.codeImage() != codeImage
                    || !descriptor.actorName().equals(actorName)
                    || !actorName.equals(name)
                    || context.kind() != ActorRuntime.ActorKind.SHARED
                    || context.codeImage().orElse(null) != codeImage
                    || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !context.self().id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("namespace critical IR requires matching live actor turn");
            }
        }
        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String name, ActorRuntime.ActorContext<Object> context) {
            requireTurn(name, context);
            if (context.currentMail().isPresent()) throw new SecurityException("init is activation only");
            Owned owned = new Owned(context.self(), initial);
            StateReceipt prior = receipts.putIfAbsent(
                    owned.actor.id(), snapshotOf(owned, 0L));
            if (prior != null) {
                throw new SecurityException("actor state receipt identity reused");
            }
            return owned;
        }
        @Override public void receiveActor(String name, ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            requireTurn(name, context);
            if (!(state instanceof Owned owned) || owned.actor != context.self()
                    || !owned.actor.id().equals(mail.recipient())
                    || !mail.equals(context.currentMail().orElse(null))
                    || !(mail.value() instanceof Long input)) {
                throw new SecurityException("mail must be a current owned ActorMail<int>");
            }
            for (CriticalPlan plan : calls) owned.apply(plan, input, context);
            // Emit only an immutable scalar computed on the physical actor
            // turn, after all checked state writes have committed.
            // Runtime validates output admission and owns the frozen transport.
            if (outputFieldSlot >= 0) {
                context.runtime().emitCurrentActorOutput(owned.values[outputFieldSlot]);
            }
            StateReceipt previous = receipts.get(owned.actor.id());
            if (previous == null
                    || previous.generation() != owned.actor.generation()) {
                throw new SecurityException("actor state receipt generation changed");
            }
            long next = Math.addExact(previous.processed(), 1L);
            receipts.put(owned.actor.id(), snapshotOf(owned, next));
        }

        @Override
        public void tearDownActor(String name,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorContext<Object> context) {
            requireTurn(name, context);
            if (!(state instanceof Owned owned) || owned.actor != context.self()) {
                throw new SecurityException("actor state retirement identity mismatch");
            }
            receipts.remove(owned.actor.id());
        }
    }
}
