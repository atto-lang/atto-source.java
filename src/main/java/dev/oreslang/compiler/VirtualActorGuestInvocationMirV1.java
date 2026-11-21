package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded guest-source AST -> typed logical invocation MIR.
 *
 * This admits the syntax of a single closed virtual-actor program including
 * its guest `resolve<T>` and awaited typed methods. It DOES NOT execute the
 * program, allocate an actor or authorize backend dispatch. Production
 * TypeChecker retains VIRTUAL_ACTOR_MIR_UNSUPPORTED until generation-fenced
 * runtime and exceptional state-loan lowering are verified.
 *
 * No source-token scanning, dynamic lookup, Java reflection or host-managed
 * guest arithmetic is used: all source operations are checked AST nodes.
 */
public final class VirtualActorGuestInvocationMirV1 {
    private VirtualActorGuestInvocationMirV1() { }

    public enum Op {
        RESOLVE_LOGICAL_IDENTITY_ONLY,
        INVOKE_GENERATION_FENCED_I64,
        AWAIT_ASYNC_RECEIPT,
        RETURN_I64
    }

    /** Read-only source-authored call. Selector is a type identity, not a permit. */
    public static final class Invoke {
        private final String selector;
        private final long argument;
        private final VirtualActorLogicalCallMirV1.CallPlan checkedCall;

        private Invoke(VirtualActorLogicalCallMirV1.CallPlan checkedCall, long argument) {
            this.checkedCall = Objects.requireNonNull(checkedCall);
            this.selector = checkedCall.methodSelector();
            this.argument = argument;
        }

        public String selector() { return selector; }
        public long argument() { return argument; }
        public VirtualActorLogicalCallMirV1.CallPlan checkedCall() { return checkedCall; }
    }

    /** Declarative operations. Not runnable bytecode or runtime permissions. */
    public static final class ProgramPlan {
        private final VirtualActorLogicalCallMirV1.ProgramPlan checkedActor;
        private final String logicalKey;
        private final String referenceBinding;
        private final List<Invoke> invocations;
        private final List<Op> operations;

        private ProgramPlan(VirtualActorLogicalCallMirV1.ProgramPlan checkedActor,
                            String logicalKey, String referenceBinding,
                            List<Invoke> invocations) {
            this.checkedActor = Objects.requireNonNull(checkedActor);
            this.logicalKey = Objects.requireNonNull(logicalKey);
            this.referenceBinding = Objects.requireNonNull(referenceBinding);
            this.invocations = List.copyOf(invocations);
            List<Op> sequence = new ArrayList<>(1 + this.invocations.size() * 2 + 1);
            sequence.add(Op.RESOLVE_LOGICAL_IDENTITY_ONLY);
            for (Invoke ignored : this.invocations) {
                sequence.add(Op.INVOKE_GENERATION_FENCED_I64);
                sequence.add(Op.AWAIT_ASYNC_RECEIPT);
            }
            sequence.add(Op.RETURN_I64);
            this.operations = List.copyOf(sequence);
        }

        public VirtualActorLogicalCallMirV1.ProgramPlan checkedActor() { return checkedActor; }
        public String logicalKey() { return logicalKey; }
        public String referenceBinding() { return referenceBinding; }
        public List<Invoke> invocations() { return invocations; }
        public List<Op> operations() { return operations; }
        public String nominalId() { return checkedActor.reference().nominalId(); }
    }

    /**
     * Admit exactly two declarations, one protected actor and one asynchronous
     * entrypoint in a closed root module. The normal compiler keeps rejecting
     * this program after prechecking the plan.
     */
    public static ProgramPlan lower(Ast.Program program) {
        Objects.requireNonNull(program, "guest virtual actor program");
        if (!program.imports().isEmpty() || program.modules().size() != 1)
            throw unsupported("one closed module and no imports required");
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(module.name())
                || !module.annotations().isEmpty()
                || !module.contracts().isEmpty()
                || module.declarations().size() != 2)
            throw unsupported("exactly one virtual actor and async main required");

        Ast.VirtualActorDecl actor = null;
        Ast.FunctionDecl main = null;
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.VirtualActorDecl value && actor == null) actor = value;
            else if (decl instanceof Ast.FunctionDecl fn
                    && fn.name().equals("main") && main == null) main = fn;
            else throw unsupported("unrecognized or duplicate declaration");
        }
        if (actor == null || main == null)
            throw unsupported("actor and main declarations are mandatory");

        VirtualActorLogicalCallMirV1.ProgramPlan checkedActor =
                VirtualActorLogicalCallMirV1.lower(
                        program.namespace(), module.name(), actor);
        if (main.kind() != Ast.CallableKind.FNC
                || main.visibility() != Ast.Visibility.PUBLIC
                || main.actorKind() != Ast.ActorKind.NONE
                || !(main.async() || main.suspends())
                || main.generator() || main.structural() || main.nonLexical()
                || main.trapped() || !main.genericParameters().isEmpty()
                || !main.parameters().isEmpty() || !main.annotations().isEmpty()
                || !main.effects().isEmpty()
                || !Ast.TypeRef.simple("int").equals(main.returnType()))
            throw unsupported("main must be a plain async/suspends fnc returning int");

        List<Ast.Stmt> statements = main.body();
        if (statements.size() < 3 || statements.size() > 34)
            throw unsupported("one resolve, 1..32 awaited calls and return required");

        if (!(statements.getFirst() instanceof Ast.BindingStmt binding)
                || binding.kind() != Ast.BindingKind.CONST
                || binding.mutableReferent() || binding.inferredTypeName() != null)
            throw unsupported("virtual reference must be a nonmutable const binding");

        Ast.TypeRef declared = binding.declaredType();
        if (declared == null || !declared.name().equals("VirtualActorRef")
                || declared.inferArguments() || declared.arguments().size() != 1
                || !declared.arguments().getFirst().equals(
                        Ast.TypeRef.simple(actor.name())))
            throw unsupported("binding needs exact VirtualActorRef<Actor> type");

        String refName = binding.name();
        if (refName == null || !refName.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw unsupported("invalid virtual reference binding");
        String key = checkedResolve(binding.initializer(), actor.name());

        List<Invoke> calls = new ArrayList<>();
        for (int i = 1; i < statements.size() - 1; i++) {
            if (!(statements.get(i) instanceof Ast.ExprStmt statement)
                    || !(statement.expression() instanceof Ast.AwaitExpr await))
                throw unsupported("all virtual calls must await their receipt");
            if (!(await.expression() instanceof Ast.CallExpr call)
                    || call.origin() != Ast.CallOrigin.DIRECT
                    || call.typeArgumentsPresent()
                    || !(call.callee() instanceof Ast.MemberExpr target)
                    || !(target.receiver() instanceof Ast.NameExpr receiver)
                    || !receiver.name().equals(refName)
                    || call.arguments().size() != 1
                    || !(call.arguments().getFirst() instanceof Ast.LiteralExpr scalar)
                    || !(scalar.value() instanceof Long value))
                throw unsupported("only directly awaited typed i64 method calls are admitted");

            VirtualActorLogicalCallMirV1.CallPlan checked;
            try {
                checked = checkedActor.reference().checkCall(
                        target.member(), List.of(Ast.TypeRef.simple("int")));
            } catch (IllegalArgumentException mismatch) {
                throw unsupported("unbound or mistyped virtual method", mismatch);
            }
            if (!checked.requiredNominalId().equals(checkedActor.reference().nominalId())
                    || !Ast.TypeRef.simple("void").equals(checked.methodReturn()))
                throw unsupported("typed nominal result mismatch");
            calls.add(new Invoke(checked, value));
        }

        if (!(statements.getLast() instanceof Ast.ReturnStmt returned)
                || !(returned.value() instanceof Ast.LiteralExpr scalar)
                || !(scalar.value() instanceof Long result) || result != 0)
            throw unsupported("closed async entrypoint must return literal int 0");

        return new ProgramPlan(checkedActor, key, refName, calls);
    }

    private static String checkedResolve(Ast.Expr initializer, String actor) {
        if (!(initializer instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT
                || !call.typeArgumentsPresent()
                || call.typeArguments().size() != 1
                || !call.typeArguments().getFirst().equals(Ast.TypeRef.simple(actor))
                || !(call.callee() instanceof Ast.MemberExpr method)
                || !method.member().equals("resolve")
                || !(method.receiver() instanceof Ast.NameExpr owner)
                || !owner.name().equals("VirtualActorRef")
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.LiteralExpr literal)
                || !(literal.value() instanceof String key)
                || !key.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw unsupported("resolve requires typed VirtualActorRef.resolve<Actor>(literal-key)");
        return key;
    }

    private static IllegalArgumentException unsupported(String detail) {
        return new IllegalArgumentException(
                "VIRTUAL_ACTOR_GUEST_INVOKE_MIR_UNSUPPORTED: " + detail);
    }

    private static IllegalArgumentException unsupported(String detail, Throwable cause) {
        return new IllegalArgumentException(
                "VIRTUAL_ACTOR_GUEST_INVOKE_MIR_UNSUPPORTED: " + detail, cause);
    }
}
