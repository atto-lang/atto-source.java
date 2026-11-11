package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, typed, portable instruction lowering of a previously checked
 * virtual-actor protected state contract. No instruction is executable by
 * itself; the normal production type checker still rejects virtual actors
 * until actor-owned memory, activation, invocation and CONTROL are wired.
 *
 * The only admitted body has a single, non-suspending exclusive write-loan
 * region. Source tokens are checked by VirtualActorProtectedMirV1 first.
 * This IR never evaluates a guest expression or owns a live state pointer.
 */
public final class VirtualActorPortableMirV1 {
    private VirtualActorPortableMirV1() { }

    /** A fixed, backend-neutral instruction vocabulary; no calls or await. */
    public enum Opcode {
        BEGIN_EXCLUSIVE_WRITE,
        LOAD_STATE_I64,
        LOAD_ARGUMENT_I64,
        ADD_I64_CHECKED,
        STORE_STATE_I64,
        END_EXCLUSIVE_WRITE,
        RETURN_VOID
    }

    /** Typed result of a virtual register, not a host-language value. */
    public enum ResultType { LOAN, I64, VOID }

    /** Instructions can only be emitted by this lowering pass. */
    public static final class Instruction {
        private final Opcode opcode;
        private final String subject;
        private final ResultType resultType;

        private Instruction(Opcode opcode, String subject, ResultType resultType) {
            this.opcode = Objects.requireNonNull(opcode);
            this.subject = Objects.requireNonNull(subject);
            this.resultType = Objects.requireNonNull(resultType);
        }

        public Opcode opcode() { return opcode; }
        public String subject() { return subject; }
        public ResultType resultType() { return resultType; }
    }

    /** Immutable statically resolved selector, not an actor reference. */
    public static final class StaticCallSite {
        private final String actor;
        private final String method;
        private final int imageLocalSlot;
        private final Ast.TypeRef parameterType;
        private final Ast.TypeRef returnType;

        private StaticCallSite(String actor, String method, int imageLocalSlot,
                               Ast.TypeRef parameterType, Ast.TypeRef returnType) {
            this.actor = actor;
            this.method = method;
            this.imageLocalSlot = imageLocalSlot;
            this.parameterType = parameterType;
            this.returnType = returnType;
        }

        public String actor() { return actor; }
        public String method() { return method; }
        public int imageLocalSlot() { return imageLocalSlot; }
        public Ast.TypeRef parameterType() { return parameterType; }
        public Ast.TypeRef returnType() { return returnType; }
    }

    /** One checked, turn-scoped method body and its image-local slot. */
    public static final class Method {
        private final int slot;
        private final VirtualActorProtectedMirV1.CheckedAdd checked;
        private final List<Instruction> instructions;

        private Method(int slot, VirtualActorProtectedMirV1.CheckedAdd checked) {
            this.slot = slot;
            this.checked = Objects.requireNonNull(checked);
            String field = checked.field();
            String argument = checked.parameter();
            this.instructions = List.of(
                    new Instruction(Opcode.BEGIN_EXCLUSIVE_WRITE, field, ResultType.LOAN),
                    new Instruction(Opcode.LOAD_STATE_I64, field, ResultType.I64),
                    new Instruction(Opcode.LOAD_ARGUMENT_I64, argument, ResultType.I64),
                    new Instruction(Opcode.ADD_I64_CHECKED, "state+argument", ResultType.I64),
                    new Instruction(Opcode.STORE_STATE_I64, field, ResultType.VOID),
                    new Instruction(Opcode.END_EXCLUSIVE_WRITE, field, ResultType.VOID),
                    new Instruction(Opcode.RETURN_VOID, "", ResultType.VOID));
        }

        public int slot() { return slot; }
        public String name() { return checked.method(); }
        public Ast.TypeRef parameterType() { return checked.parameterType(); }
        public Ast.TypeRef returnType() { return checked.returnType(); }
        public List<Instruction> instructions() { return instructions; }

        private void verify(String field, int expectedSlot) {
            if (slot != expectedSlot || checked.arity() != 1
                    || !checked.field().equals(field)
                    || !Ast.TypeRef.simple("int").equals(parameterType())
                    || !Ast.TypeRef.simple("void").equals(returnType())) {
                throw new IllegalStateException("invalid virtual actor portable MIR signature");
            }
            Opcode[] expectedOps = Opcode.values();
            ResultType[] expectedTypes = {
                    ResultType.LOAN, ResultType.I64, ResultType.I64,
                    ResultType.I64, ResultType.VOID, ResultType.VOID,
                    ResultType.VOID
            };
            String[] expectedSubjects = {
                    field, field, checked.parameter(), "state+argument",
                    field, field, ""
            };
            if (instructions.size() != expectedOps.length)
                throw new IllegalStateException("unbalanced virtual state loan");
            for (int i = 0; i < expectedOps.length; i++) {
                Instruction op = instructions.get(i);
                if (op.opcode() != expectedOps[i] || op.resultType() != expectedTypes[i]
                        || !op.subject().equals(expectedSubjects[i])) {
                    throw new IllegalStateException(
                            "unverified virtual actor instruction at index " + i);
                }
            }
        }
    }

    /**
     * Compiler-owned typed code image. Slots are only valid in this image:
     * hot upgrades must re-resolve methods against the current generation.
     */
    public static final class Image {
        private final VirtualActorProtectedMirV1.Plan checkedPlan;
        private final List<Method> methods;

        private Image(VirtualActorProtectedMirV1.Plan checkedPlan, List<Method> methods) {
            this.checkedPlan = Objects.requireNonNull(checkedPlan);
            this.methods = List.copyOf(methods);
        }

        public String actor() { return checkedPlan.declaration(); }
        public VirtualActorProtectedMirV1.ScalarState state() { return checkedPlan.state(); }
        public List<Method> methods() { return methods; }

        /**
         * Compile-time call selection. Rejects ActorRef, ActivationRef,
         * inferred/borrowed/unrelated receiver types and wrong argument types.
         * Does not resolve a tenant, allocate an activation or send a message.
         */
        public StaticCallSite resolveCall(
                Ast.TypeRef receiver, String name, List<Ast.TypeRef> arguments) {
            Objects.requireNonNull(receiver, "receiver type");
            if (!receiver.name().equals("VirtualActorRef")
                    || receiver.inferArguments() || receiver.arguments().size() != 1
                    || !receiver.arguments().getFirst()
                            .equals(Ast.TypeRef.simple(actor()))) {
                throw new IllegalArgumentException(
                        "VIRTUAL_ACTOR_REF_TYPE_MISMATCH: expected VirtualActorRef["
                                + actor() + "]");
            }
            var selected = checkedPlan.referenceType().requireCall(name, arguments);
            for (Method method : methods) {
                if (method.name().equals(selected.method())) {
                    return new StaticCallSite(actor(), method.name(), method.slot(),
                            method.parameterType(), method.returnType());
                }
            }
            throw new IllegalStateException("checked virtual method missing from code image");
        }

        /** Fail closed on future changes to op order, region or type shape. */
        public void verify() {
            if (methods.isEmpty() || methods.size() > 64
                    || !state().type().equals(Ast.TypeRef.simple("int"))) {
                throw new IllegalStateException("invalid protected actor image");
            }
            String previous = null;
            for (int i = 0; i < methods.size(); i++) {
                Method method = methods.get(i);
                if (previous != null && previous.compareTo(method.name()) >= 0)
                    throw new IllegalStateException("duplicate or unordered method slot");
                method.verify(state().name(), i);
                previous = method.name();
            }
        }
    }

    /**
     * Source -> checked protected plan -> deterministic typed instructions.
     * This is deliberately NOT executable admission.
     */
    public static Image lower(Ast.VirtualActorDecl actor) {
        var checked = VirtualActorProtectedMirV1.analyze(actor);
        var sorted = new ArrayList<>(checked.methods());
        sorted.sort(Comparator.comparing(VirtualActorProtectedMirV1.CheckedAdd::method));
        List<Method> methods = new ArrayList<>(sorted.size());
        for (var method : sorted)
            methods.add(new Method(methods.size(), method));
        Image image = new Image(checked, methods);
        image.verify();
        return image;
    }
}
