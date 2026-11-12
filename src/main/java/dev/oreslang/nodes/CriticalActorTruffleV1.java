package dev.oreslang.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorLinearArena;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorStateLoans;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.CriticalActorMirV1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Experimental Truffle executable for a closed-world namespace-actor
 * critical method. The normal TypeChecker execution gate stays CLOSED.
 *
 * This compiles the already typed MIR to Truffle child nodes. Unlike a host
 * reference interpreter, no guest AST is reparsed or dispatched in a mutable
 * host-owned broker object. Every persistent field lives in an actor-owned
 * direct-byte arena. A method is callable only from an admitted physical
 * ActorRuntime mailbox turn with the exact runtime-minted ActorInboxMail,
 * actor identity and whitelisted CodeImage.
 *
 * Not yet general 'external mtd receive' lowering: the compiler's trusted
 * ActorCodeExecutor may select this target only after mailbox admission. No
 * arbitrary guest source receives a critical method function or state handle.
 * NativeAOT, hot-upgrade and inter-process isolation remain unproven.
 */
public final class CriticalActorTruffleV1 {
    private CriticalActorTruffleV1() { }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException("CRITICAL_ACTOR_EXECUTION_V1_REJECTED: " + reason);
    }

    public static Executable compile(
            SharedCodeImageStore.CodeImage image, String packageIdentity,
            String actor, String methodName) {
        Objects.requireNonNull(image, "immutable actor image");
        Objects.requireNonNull(packageIdentity, "package identity");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(methodName, "method");
        Ast.Program program = image.program();
        Map<ActorStateBinder.Slot, ActorStateBinder.Binding> bindings =
                ActorStateBinder.bindSourceSet(packageIdentity,
                        Map.of(image.codeUnitId(), program));
        var allMethods = CriticalActorMirV1.lowerSourceSet(packageIdentity,
                Map.of(image.codeUnitId(), program));
        ActorStateBinder.Slot selected = new ActorStateBinder.Slot(
                packageIdentity, program.namespace(), actor);
        var schema = bindings.get(selected);
        if (schema == null) throw unsupported("missing actor-owned sealed state");
        var methods = allMethods.get(selected);
        if (methods == null) throw unsupported("missing actor critical method");
        List<CriticalActorMirV1.Method> candidates =
                methods.stream().filter(m -> m.name().equals(methodName)).toList();
        // Method overloads cannot be guessed from mail payload shape.
        if (candidates.size() != 1) throw unsupported("critical method identity ambiguous");
        CriticalActorMirV1.Method mir = candidates.getFirst();
        if (mir.steps().stream().noneMatch(CriticalActorMirV1.WriteLoan.class::isInstance)) {
            throw unsupported("write loan missing");
        }
        if (schema.schema().fields().size() > 128 || mir.steps().size() > 4096) {
            throw unsupported("critical scalar layout or MIR budget exceeded");
        }
        Layout layout = new Layout(schema.schema(), mir);
        RootCallTarget target = new CriticalRoot(mir, layout).getCallTarget();
        return new Executable(image, actor, methodName, layout, target);
    }

    public static final class Executable {
        private final SharedCodeImageStore.CodeImage image;
        private final String actorName;
        private final String methodName;
        private final Layout layout;
        private final RootCallTarget target;

        private Executable(SharedCodeImageStore.CodeImage image, String actorName,
                           String methodName, Layout layout, RootCallTarget target) {
            this.image = image;
            this.actorName = actorName;
            this.methodName = methodName;
            this.layout = layout;
            this.target = target;
        }

        public String actorName() { return actorName; }
        public String methodName() { return methodName; }

        /**
         * May be called only by ActorCodeExecutor.initializeActor after the
         * runtime installed the real actor's context and code image.
         */
        public OwnedState initializeActor(ActorRuntime.ActorContext<Object> context) {
            Objects.requireNonNull(context, "actor context");
            requireContext(context, null, false);
            ActorLinearArena arena = new ActorLinearArena(
                    Math.multiplyExact(layout.initial.length, Long.BYTES),
                    layout.initial.length);
            try {
                long[] handles = new long[layout.initial.length];
                for (int i = 0; i < handles.length; i++) {
                    handles[i] = arena.allocateOwned(Long.BYTES);
                    if (handles[i] == ActorLinearArena.INVALID_HANDLE
                            || !arena.putLong(handles[i], 0, layout.initial[i])) {
                        throw new IllegalStateException("actor state allocation exhausted");
                    }
                }
                return new OwnedState(context.self().id(), image, arena, handles);
            } catch (RuntimeException | Error failure) {
                arena.close();
                throw failure;
            }
        }

        /**
         * Only the trusted mailbox code executor may call this. The caller
         * must supply the *same object* issued by ActorRuntime.currentMail(),
         * and never a newly constructed record with equivalent fields.
         */
        public Object invokeFromMailbox(OwnedState state,
                                        ActorRuntime.ActorInboxMail<Object> mail,
                                        ActorRuntime.ActorContext<Object> context,
                                        Object... arguments) {
            Objects.requireNonNull(state, "actor state");
            Objects.requireNonNull(mail, "runtime-issued mail");
            Objects.requireNonNull(arguments, "critical arguments");
            requireContext(context, mail, true);
            if (!state.ownerActorId().equals(context.self().id()) || state.image != image
                    || state.retired) {
                throw new SecurityException("foreign, retired or stale actor state");
            }
            if (arguments.length != layout.parameterTypes.length) {
                throw unsupported("critical argument count mismatch");
            }
            for (int i = 0; i < arguments.length; i++) {
                if (layout.parameterTypes[i] == CriticalActorMirV1.Type.I64) {
                    if (!(arguments[i] instanceof Long)) {
                        throw unsupported("critical i64 argument must be an owned long");
                    }
                } else if (!(arguments[i] instanceof Boolean)) {
                    throw unsupported("critical bool argument must be an owned boolean");
                }
            }
            return target.call(state, context, mail, arguments);
        }

        private void requireContext(ActorRuntime.ActorContext<Object> context,
                                    ActorRuntime.ActorInboxMail<Object> mail,
                                    boolean requireMail) {
            if (context.codeImage().orElse(null) != image
                    || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !context.self().id().equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("critical method requires exact actor runtime and CodeImage");
            }
            if (requireMail && (context.currentMail().orElse(null) != mail
                    || mail.recipient() != context.self().id())) {
                throw new SecurityException("critical method requires original runtime mailbox envelope");
            }
            if (!requireMail && context.currentMail().isPresent()) {
                throw new SecurityException("actor state initialization must precede mailbox dispatch");
            }
        }
    }

    /** Persistent scalar storage belongs to exactly one actor and never escapes to guest source. */
    public static final class OwnedState
            implements ActorRuntime.ActorOwnedGuestState, AutoCloseable {
        private final ActorRuntime.ActorId owner;
        private final SharedCodeImageStore.CodeImage image;
        private final ActorLinearArena arena;
        private final long[] handles;
        private final ActorStateLoans loans = new ActorStateLoans(0);
        private volatile boolean retired;

        private OwnedState(ActorRuntime.ActorId owner,
                           SharedCodeImageStore.CodeImage image,
                           ActorLinearArena arena, long[] handles) {
            this.owner = Objects.requireNonNull(owner);
            this.image = image;
            this.arena = arena;
            this.handles = handles;
        }

        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }

        private void require(ActorRuntime.ActorInboxMail<Object> mail,
                             ActorRuntime.ActorContext<Object> context,
                             ActorStateLoans.ExclusiveLoan loan) {
            if (retired || ActorRuntime.currentActorRuntime() != context.runtime()
                    || !owner.equals(ActorRuntime.currentActorId().orElse(null))
                    || context.currentMail().orElse(null) != mail
                    || mail.recipient() != owner
                    || !loan.heldBy(mail)) {
                throw new SecurityException("actor state access without live generation-fenced write loan");
            }
        }

        private ActorStateLoans.ExclusiveLoan begin(
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            if (retired || context.self().id() != owner) {
                throw new SecurityException("actor retired or has foreign state");
            }
            var grant = loans.acquireAsync(mail);
            // ActorRuntime serializes this turn. Any queued grant would park
            // an actor carrier and is rejected, never joined while pending.
            if (!grant.isDone()) {
                grant.cancel(false);
                throw new IllegalStateException("critical state loan is busy");
            }
            var loan = grant.join();
            require(mail, context, loan);
            return loan;
        }

        private long[] snapshot(ActorRuntime.ActorInboxMail<Object> mail,
                                ActorRuntime.ActorContext<Object> context,
                                ActorStateLoans.ExclusiveLoan loan) {
            require(mail, context, loan);
            long[] copy = new long[handles.length];
            for (int i = 0; i < handles.length; i++) {
                copy[i] = arena.getLong(handles[i], 0);
            }
            return copy;
        }

        private void commit(ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context,
                            ActorStateLoans.ExclusiveLoan loan,
                            long[] values) {
            require(mail, context, loan);
            if (values.length != handles.length)
                throw new SecurityException("critical field layout changed");
            for (int i = 0; i < handles.length; i++) {
                if (!arena.putLong(handles[i], 0, values[i])) {
                    throw new IllegalStateException("actor state arena retired during commit");
                }
            }
        }

        /**
         * ActorCodeExecutor.tearDownActor runs actor-affine after mailbox
         * quiescence. Never reclaim a live loan or an outstanding physical turn.
         */
        @Override public void close() {
            if (retired) return;
            if (!owner.equals(ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("foreign actor cannot reclaim state");
            }
            if (loans.activeCount() != 0 || loans.pendingCount() != 0) {
                throw new IllegalStateException("actor state is not loan-quiescent");
            }
            retired = true;
            loans.close();
            arena.close();
        }
    }

    private static final class Layout {
        private final Map<String, Integer> fields = new LinkedHashMap<>();
        private final Map<String, Integer> locals = new LinkedHashMap<>();
        private final long[] initial;
        private final CriticalActorMirV1.Type[] fieldTypes;
        private final CriticalActorMirV1.Type[] parameterTypes;

        Layout(Ast.ActorStateDecl schema, CriticalActorMirV1.Method method) {
            var defs = schema.fields();
            initial = new long[defs.size()];
            fieldTypes = new CriticalActorMirV1.Type[defs.size()];
            for (int i = 0; i < defs.size(); i++) {
                var field = defs.get(i);
                fields.put(field.name(), i);
                var primitive = field.type().name();
                fieldTypes[i] = primitive.equals("bool")
                        ? CriticalActorMirV1.Type.BOOL : CriticalActorMirV1.Type.I64;
                Object init = ((Ast.LiteralExpr) field.initializer()).value();
                initial[i] = init instanceof Boolean value
                        ? (value ? 1L : 0L) : (Long) init;
            }
            parameterTypes = new CriticalActorMirV1.Type[method.parameters().size()];
            int index = 0;
            for (Map.Entry<String, CriticalActorMirV1.Type> p : method.parameters().entrySet()) {
                locals.put(p.getKey(), index);
                parameterTypes[index++] = p.getValue();
            }
            collectLocals(method.steps());
            if (locals.size() > 256) throw unsupported("local slot budget exceeded");
        }

        private void collectLocals(List<CriticalActorMirV1.Step> steps) {
            for (var step : steps) {
                if (step instanceof CriticalActorMirV1.Declare decl) {
                    locals.computeIfAbsent(decl.name(), ignored -> locals.size());
                } else if (step instanceof CriticalActorMirV1.Branch branch) {
                    collectLocals(branch.yes());
                    collectLocals(branch.no());
                } else if (step instanceof CriticalActorMirV1.WriteLoan write) {
                    collectLocals(write.body());
                }
            }
        }
    }

    private static final class StateFrame {
        final OwnedState actor;
        final ActorRuntime.ActorInboxMail<Object> mail;
        final ActorRuntime.ActorContext<Object> context;
        final Object[] locals;
        long[] staged;
        ActorStateLoans.ExclusiveLoan loan;
        boolean returned;
        Object result;
        StateFrame(OwnedState actor, ActorRuntime.ActorInboxMail<Object> mail,
                   ActorRuntime.ActorContext<Object> context, Object[] args, int count) {
            this.actor = actor;
            this.mail = mail;
            this.context = context;
            locals = Arrays.copyOf(args, count);
        }
        Object field(int index, CriticalActorMirV1.Type type) {
            if (loan == null || staged == null)
                throw new SecurityException("sealed field read outside write loan");
            actor.require(mail, context, loan);
            long value = staged[index];
            if (type == CriticalActorMirV1.Type.BOOL) {
                if (value != 0L && value != 1L)
                    throw new SecurityException("corrupt actor bool field encoding");
                return value == 1L;
            }
            return value;
        }
        void put(int index, CriticalActorMirV1.Type type, Object value) {
            if (loan == null || staged == null)
                throw new SecurityException("sealed field mutation outside write loan");
            actor.require(mail, context, loan);
            staged[index] = type == CriticalActorMirV1.Type.BOOL
                    ? ((Boolean) value ? 1L : 0L) : (Long) value;
        }
    }

    private abstract static class ValueNode extends Node {
        abstract Object value(StateFrame frame);
    }
    private abstract static class StepNode extends Node {
        abstract void run(StateFrame frame);
    }

    private static final class ConstantNode extends ValueNode {
        private final Object value;
        ConstantNode(Object value) { this.value = value; }
        @Override Object value(StateFrame frame) { return value; }
    }
    private static final class LocalNode extends ValueNode {
        private final int slot;
        LocalNode(int slot) { this.slot = slot; }
        @Override Object value(StateFrame frame) {
            Object v = frame.locals[slot];
            if (v == null) throw new IllegalStateException("uninitialized scalar local");
            return v;
        }
    }
    private static final class FieldNode extends ValueNode {
        private final int index;
        private final CriticalActorMirV1.Type type;
        FieldNode(int index, CriticalActorMirV1.Type type) {
            this.index = index;
            this.type = type;
        }
        @Override Object value(StateFrame frame) { return frame.field(index, type); }
    }
    private static final class UnaryNode extends ValueNode {
        private final String op;
        @Child private ValueNode input;
        UnaryNode(String op, ValueNode input) {
            this.op = op;
            this.input = input;
        }
        @Override Object value(StateFrame frame) {
            Object x = input.value(frame);
            return switch (op) {
                case "not" -> !((Boolean) x);
                case "neg_checked" -> Math.negateExact((Long) x);
                default -> throw new IllegalStateException("unverified unary MIR");
            };
        }
    }
    private static final class BinaryNode extends ValueNode {
        private final String op;
        @Child private ValueNode left;
        @Child private ValueNode right;
        BinaryNode(String op, ValueNode left, ValueNode right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }
        @Override Object value(StateFrame frame) {
            Object l = left.value(frame);
            if (op.equals("and") && !((Boolean) l)) return false;
            if ((op.equals("or") || op.equals("|")) && ((Boolean) l)) return true;
            Object r = right.value(frame);
            return switch (op) {
                case "add_checked" -> Math.addExact((Long) l, (Long) r);
                case "sub_checked" -> Math.subtractExact((Long) l, (Long) r);
                case "and" -> (Boolean) l && (Boolean) r;
                case "or", "|" -> (Boolean) l || (Boolean) r;
                case "==", "eq" -> l.equals(r);
                case "!=", "neq" -> !l.equals(r);
                case "<" -> (Long) l < (Long) r;
                case ">" -> (Long) l > (Long) r;
                case "<=" -> (Long) l <= (Long) r;
                case ">=" -> (Long) l >= (Long) r;
                default -> throw new IllegalStateException("unverified binary MIR");
            };
        }
    }
    private static final class LocalWriteNode extends StepNode {
        private final int slot;
        @Child private ValueNode value;
        LocalWriteNode(int slot, ValueNode value) {
            this.slot = slot;
            this.value = value;
        }
        @Override void run(StateFrame frame) { frame.locals[slot] = value.value(frame); }
    }
    private static final class FieldWriteNode extends StepNode {
        private final int index;
        private final CriticalActorMirV1.Type type;
        @Child private ValueNode value;
        FieldWriteNode(int index, CriticalActorMirV1.Type type, ValueNode value) {
            this.index = index;
            this.type = type;
            this.value = value;
        }
        @Override void run(StateFrame frame) { frame.put(index, type, value.value(frame)); }
    }
    private static final class BlockNode extends StepNode {
        @Children private StepNode[] statements;
        BlockNode(List<CriticalActorMirV1.Step> source, Layout layout) {
            statements = source.stream().map(step -> compileStep(step, layout))
                    .toArray(StepNode[]::new);
        }
        @Override void run(StateFrame frame) {
            for (StepNode statement : statements) {
                if (frame.returned) break;
                statement.run(frame);
            }
        }
    }
    private static final class BranchNode extends StepNode {
        @Child private ValueNode condition;
        @Child private BlockNode yes;
        @Child private BlockNode no;
        BranchNode(CriticalActorMirV1.Branch branch, Layout layout) {
            condition = compileExpression(branch.condition(), layout);
            yes = new BlockNode(branch.yes(), layout);
            no = new BlockNode(branch.no(), layout);
        }
        @Override void run(StateFrame frame) {
            if ((Boolean) condition.value(frame)) yes.run(frame);
            else no.run(frame);
        }
    }
    private static final class WriteLoanNode extends StepNode {
        @Child private BlockNode body;
        WriteLoanNode(CriticalActorMirV1.WriteLoan write, Layout layout) {
            body = new BlockNode(write.body(), layout);
        }
        @Override void run(StateFrame frame) {
            if (frame.loan != null || frame.staged != null)
                throw new SecurityException("re-entrant critical write loan");
            try (var live = frame.actor.begin(frame.mail, frame.context)) {
                frame.loan = live;
                frame.staged = frame.actor.snapshot(frame.mail, frame.context, live);
                body.run(frame);
                if (frame.returned) throw new SecurityException("return under state loan");
                frame.actor.commit(frame.mail, frame.context, live, frame.staged);
            } finally {
                frame.loan = null;
                frame.staged = null;
            }
        }
    }
    private static final class ReturnNode extends StepNode {
        @Child private ValueNode value;
        ReturnNode(CriticalActorMirV1.Return ret, Layout layout) {
            value = ret.value() == null ? null : compileExpression(ret.value(), layout);
        }
        @Override void run(StateFrame frame) {
            if (frame.loan != null) throw new SecurityException("return while state loan live");
            frame.result = value == null ? null : value.value(frame);
            frame.returned = true;
        }
    }

    private static ValueNode compileExpression(CriticalActorMirV1.Expr expr, Layout layout) {
        if (expr instanceof CriticalActorMirV1.Literal literal)
            return new ConstantNode(literal.value());
        if (expr instanceof CriticalActorMirV1.Local local)
            return new LocalNode(slot(layout.locals, local.name()));
        if (expr instanceof CriticalActorMirV1.Field field)
            return new FieldNode(slot(layout.fields, field.name()), field.type());
        if (expr instanceof CriticalActorMirV1.Unary unary)
            return new UnaryNode(unary.op(), compileExpression(unary.operand(), layout));
        if (expr instanceof CriticalActorMirV1.Binary binary)
            return new BinaryNode(binary.op(), compileExpression(binary.left(), layout),
                    compileExpression(binary.right(), layout));
        throw unsupported("unverified critical MIR expression");
    }

    private static int slot(Map<String, Integer> map, String name) {
        Integer i = map.get(name);
        if (i == null) throw unsupported("unbound typed MIR slot: " + name);
        return i;
    }

    private static StepNode compileStep(CriticalActorMirV1.Step step, Layout layout) {
        if (step instanceof CriticalActorMirV1.Declare d)
            return new LocalWriteNode(slot(layout.locals, d.name()),
                    compileExpression(d.initialValue(), layout));
        if (step instanceof CriticalActorMirV1.SetLocal d)
            return new LocalWriteNode(slot(layout.locals, d.name()),
                    compileExpression(d.value(), layout));
        if (step instanceof CriticalActorMirV1.SetField d) {
            int i = slot(layout.fields, d.field());
            return new FieldWriteNode(i, layout.fieldTypes[i],
                    compileExpression(d.value(), layout));
        }
        if (step instanceof CriticalActorMirV1.Branch b) return new BranchNode(b, layout);
        if (step instanceof CriticalActorMirV1.WriteLoan w) return new WriteLoanNode(w, layout);
        if (step instanceof CriticalActorMirV1.Return r) return new ReturnNode(r, layout);
        throw unsupported("unverified critical MIR step");
    }

    private static final class CriticalRoot extends RootNode {
        @Child private BlockNode body;
        private final int localCount;
        private final int parameterCount;

        CriticalRoot(CriticalActorMirV1.Method method, Layout layout) {
            super(null); // internal Truffle root; no guest language registration
            body = new BlockNode(method.steps(), layout);
            localCount = layout.locals.size();
            parameterCount = method.parameters().size();
        }
        @Override public String getName() { return "atto-critical-mir-v1"; }
        @Override public boolean isInternal() { return true; }
        @Override public Object execute(VirtualFrame virtualFrame) {
            Object[] arguments = virtualFrame.getArguments();
            if (arguments.length != 4
                    || !(arguments[0] instanceof OwnedState actor)
                    || !(arguments[1] instanceof ActorRuntime.ActorContext<?>)
                    || !(arguments[2] instanceof ActorRuntime.ActorInboxMail<?>)
                    || !(arguments[3] instanceof Object[] parameters)
                    || parameters.length != parameterCount) {
                throw new SecurityException("untrusted or malformed critical actor entry");
            }
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorContext<Object> context =
                    (ActorRuntime.ActorContext<Object>) arguments[1];
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorInboxMail<Object> mail =
                    (ActorRuntime.ActorInboxMail<Object>) arguments[2];
            StateFrame frame = new StateFrame(actor, mail, context, parameters, localCount);
            body.run(frame);
            if (!frame.returned) throw new IllegalStateException("critical method fell through");
            return frame.result;
        }
    }
}
