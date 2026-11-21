package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shape-only resolved invocation IR. This is not an executable capability:
 * semantic types, ownership, effects, imports and runtime ABI are unproved.
 */
public final class BoundInvocation {
    public enum Readiness { SHAPE_ONLY }
    public enum Kind { MODULE_FUNCTION, CLASS_METHOD, CLASS_CONSTRUCTOR }
    private final Ast.Expr source;
    private final Object owner;
    private final Object selected;
    private final Kind kind;
    private final int declarationOrdinal;
    private final OptionalCallAbiPreview.InvocationPreview preview;
    private final OptionalCallEvaluationSchedule.Schedule schedule;
    private final List<Ast.TypeRef> assertedSourceTypes;

    private BoundInvocation(Kind kind, Ast.Expr source, Object owner,
                            Object selected, int ordinal,
                            OptionalCallAbiPreview.InvocationPreview preview,
                            List<Ast.TypeRef> assertedSourceTypes) {
        this.kind = Objects.requireNonNull(kind);
        this.source = Objects.requireNonNull(source);
        this.owner = Objects.requireNonNull(owner);
        this.selected = Objects.requireNonNull(selected);
        this.declarationOrdinal = ordinal;
        this.preview = Objects.requireNonNull(preview);
        // The canonical schedule rechecks source evaluation identity and
        // unique formal ABI destinations before any consumer can inspect IR.
        this.schedule = OptionalCallEvaluationSchedule.from(preview);
        this.assertedSourceTypes = List.copyOf(assertedSourceTypes);
        if (preview.arguments().sourceExpressions().size() != this.assertedSourceTypes.size()) {
            throw new IllegalArgumentException("every evaluated source must have one claimed type");
        }
        if ((kind == Kind.CLASS_CONSTRUCTOR)
                != (preview.kind() == OptionalCallAbiPreview.InvocationKind.CLASS_CONSTRUCTOR)) {
            throw new IllegalArgumentException("bound call kind disagrees with invocation preview");
        }
        if (ordinal < 0) throw new IllegalArgumentException("negative declaration ordinal");
        boolean owned = switch (kind) {
            case MODULE_FUNCTION -> owner instanceof Ast.ModuleDecl module
                    && ordinal < module.declarations().size()
                    && module.declarations().get(ordinal) == selected
                    && selected instanceof Ast.FunctionDecl;
            case CLASS_METHOD -> owner instanceof Ast.ClassDecl klass
                    && ordinal < klass.methods().size()
                    && klass.methods().get(ordinal) == selected
                    && selected instanceof Ast.MethodDecl;
            case CLASS_CONSTRUCTOR -> owner instanceof Ast.ClassDecl klass
                    && ordinal == 0 && klass.constructor() == selected
                    && selected instanceof Ast.ConstructorDecl;
        };
        if (!owned) {
            throw new IllegalArgumentException("selected declaration not owned by parsed owner");
        }
        verifySelectedSourceSnapshot(kind, source, selected, preview, schedule);
    }

    /**
     * Recheck that the immutable schedule and ABI slots refer to the original
     * parsed call items and the exact selected declaration. Do not trust a
     * self-consistent but unrelated supplied preview or formal permutation.
     * This is provenance validation only, NOT a type/loan/effect proof.
     */
    private static void verifySelectedSourceSnapshot(
            Kind kind, Ast.Expr source, Object selected,
            OptionalCallAbiPreview.InvocationPreview preview,
            OptionalCallEvaluationSchedule.Schedule schedule) {
        List<Ast.Param> formals;
        List<Ast.CallArgument> callItems;
        if (kind == Kind.CLASS_CONSTRUCTOR) {
            if (!(source instanceof Ast.StagedNewExpr construction)
                    || construction.actorSpawn()
                    || preview.kind() != OptionalCallAbiPreview.InvocationKind.CLASS_CONSTRUCTOR
                    || !construction.type().equals(preview.constructedType())) {
                throw new IllegalArgumentException("constructor source and ABI plan disagree");
            }
            formals = ((Ast.ConstructorDecl) selected).parameters();
            callItems = construction.arguments();
        } else {
            if (!(source instanceof Ast.StagedCallExpr call)
                    || preview.kind() != OptionalCallAbiPreview.InvocationKind.CALL
                    || call.callee() != preview.calleeExpression()) {
                throw new IllegalArgumentException("original callee and ABI plan disagree");
            }
            formals = kind == Kind.MODULE_FUNCTION
                    ? ((Ast.FunctionDecl) selected).parameters()
                    : ((Ast.MethodDecl) selected).parameters();
            callItems = call.arguments();
        }
        var plan = OptionalCallShapeBinder.bindParsedFormals(formals, callItems);
        List<OptionalCallAbiPreview.Slot> slots = preview.arguments().abiSlots();
        if (plan.variadicParameterIndex() != -1 || slots.size() != formals.size()
                || !schedule.abiSlots().equals(slots)) {
            throw new IllegalArgumentException("selected declaration has incompatible full ABI schedule");
        }
        java.util.Map<Integer, Integer> explicitOmissions = new java.util.HashMap<>();
        for (var item : plan.omittedCallItemBindings()) {
            explicitOmissions.put(item.parameterIndex(), item.callItemIndex());
        }
        for (int i = 0; i < formals.size(); i++) {
            Ast.Param formal = formals.get(i);
            OptionalCallAbiPreview.Slot slot = slots.get(i);
            int sourceIndex = plan.sourceIndex(i);
            int callItemIndex = sourceIndex < 0
                    ? explicitOmissions.getOrDefault(i, -1)
                    : plan.callItemIndex(sourceIndex);
            if (slot.formalIndex() != i || !slot.formalName().equals(formal.name())
                    || !slot.calleeType().equals(formal.calleeType())
                    || slot.evaluatedSourceIndex() != sourceIndex
                    || slot.originalCallItemIndex() != callItemIndex) {
                throw new IllegalArgumentException(
                        "ABI slot differs from selected declaration or source call-item origin");
            }
        }
    }

    public Ast.Expr source() { return source; }
    public Object owner() { return owner; }
    public Object selectedDeclaration() { return selected; }
    public Kind kind() { return kind; }
    public int declarationOrdinal() { return declarationOrdinal; }
    public OptionalCallAbiPreview.InvocationPreview preview() { return preview; }
    /** Compiler-only schedule; its INVOKE step does not authorize execution. */
    public OptionalCallEvaluationSchedule.Schedule schedule() { return schedule; }
    public List<Ast.TypeRef> assertedSourceTypes() { return assertedSourceTypes; }
    public Readiness readiness() { return Readiness.SHAPE_ONLY; }

    public void requireExecutable() {
        throw new IllegalStateException(
                "trusted type/ownership/effect/Option ABI lowering is not implemented");
    }

    /**
     * Narrow self-derived source-type facts for literal-only call arguments.
     * Unlike assertedSourceTypes, these originate from the literal AST itself.
     * Non-literals (including NameExpr("None"), variables and side-effectful
     * calls) MUST be resolved by the real lexical TypeChecker, not guessed.
     *
     * This is still SHAPE_ONLY because scope/visibility, effects, ownership and
     * runtime Option<T> ABI proofs do not follow from knowing a literal's type.
     */
    public static BoundInvocation bindLiteralModuleFunction(
            Ast.ModuleDecl module, Ast.StagedCallExpr call) {
        Objects.requireNonNull(call, "call");
        return bindModuleFunction(module, call, checkedLiteralTypes(call.arguments()));
    }

    public static BoundInvocation bindLiteralClassMethod(
            Ast.ClassDecl owner, Ast.StagedCallExpr call) {
        Objects.requireNonNull(call, "call");
        return bindClassMethod(owner, call, checkedLiteralTypes(call.arguments()));
    }

    public static BoundInvocation bindLiteralClassConstructor(
            Ast.ClassDecl owner, Ast.StagedNewExpr call) {
        Objects.requireNonNull(call, "call");
        return bindClassConstructor(owner, call, checkedLiteralTypes(call.arguments()));
    }

    private static List<Ast.TypeRef> checkedLiteralTypes(List<Ast.CallArgument> items) {
        List<Ast.TypeRef> types = new ArrayList<>();
        for (Ast.CallArgument item : items) {
            Objects.requireNonNull(item, "source call item");
            if (item.omitted()) continue;
            if (item.spread() || !(item.value() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException(
                        "nonliteral argument requires lexical compiler type and effect proof");
            }
            // Reuse the same independent AST literal type oracle as
            // OptionalCallAbiPreview; separate implementations can drift.
            types.add(OptionalCallLiteralStage.exactScalarLiteralType(literal));
        }
        return List.copyOf(types);
    }

    /**
     * Resolve only a direct or explicitly module-qualified function in the
     * real parsed owner. This does NOT prove the caller can access the module.
     */
    public static BoundInvocation bindModuleFunction(
            Ast.ModuleDecl module, Ast.StagedCallExpr call,
            List<Ast.TypeRef> assertedSourceTypes) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(call, "call");
        // Expansion and contract adherence can affect module ownership and
        // visibility. This non-executable binder must not infer either from
        // an unchecked, possibly synthesized ModuleDecl.
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException(
                    "module annotations/contracts require checked lexical expansion");
        }
        String name;
        if (call.callee() instanceof Ast.NameExpr direct) {
            name = direct.name();
        } else if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr qualifier
                && qualifier.name().equals(module.name())) {
            name = member.member();
        } else {
            throw new IllegalArgumentException("unproved indirect or foreign callee");
        }
        // For app.target(...), the name "app" must resolve to the actual
        // module namespace, not a same-spelled callable or value declaration.
        // This preview has no checked namespace resolution to disambiguate it.
        boolean moduleQualified = call.callee() instanceof Ast.MemberExpr;
        List<Ast.FunctionDecl> candidates = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        List<List<Ast.Param>> signatures = new ArrayList<>();
        for (int i = 0; i < module.declarations().size(); i++) {
            Ast.Decl declaration = module.declarations().get(i);
            // A module field or type can participate in value/callable name
            // resolution. Shape-only binding cannot decide that a same-name
            // declaration is irrelevant without the real lexical resolver.
            String competingName = null;
            if (declaration instanceof Ast.FieldDecl field) {
                competingName = field.name();
            } else if (declaration instanceof Ast.ClassDecl klass) {
                competingName = klass.name();
            } else if (declaration instanceof Ast.InterfaceDecl iface) {
                competingName = iface.name();
            } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                competingName = alias.name();
            }
            if (name.equals(competingName)
                    || (moduleQualified && module.name().equals(competingName))) {
                throw new IllegalArgumentException(
                        "module callable or qualifier name collides with an unproved declaration");
            }
            if (declaration instanceof Ast.FunctionDecl f
                    && moduleQualified && f.name().equals(module.name())) {
                throw new IllegalArgumentException(
                        "module qualifier collides with an unproved callable value");
            }
            if (declaration instanceof Ast.FunctionDecl f
                    && f.name().equals(name)) {
                if (!f.genericParameters().isEmpty() || !f.genericSpecs().isEmpty()
                        || f.actorKind() != Ast.ActorKind.NONE
                        || f.kind() != Ast.CallableKind.FNC
                        || f.async() || f.suspends() || f.generator()
                        || f.structural() || f.nonLexical() || f.trapped()
                        || !f.annotations().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic, actor, effectful, routine or annotated function target not proved");
                }
                // A synthetic module callable can be constructed with a
                // reserved constructor self receiver even though normal
                // source parsing would not produce it. Never treat that
                // receiver binder as a guest-supplied named ABI slot.
                if (f.parameters().stream().anyMatch(p -> "self".equals(p.name()))) {
                    throw new IllegalArgumentException(
                            "module function cannot bind constructor self as a value argument");
                }
                candidates.add(f);
                ordinals.add(i);
                signatures.add(f.parameters());
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no declaration in selected module");
        }
        var selection = OptionalCallShapeBinder.selectParsed(signatures, call.arguments());
        Ast.FunctionDecl declaration = candidates.get(selection.signatureIndex());
        OptionalCallShapeBinder.verifyPlanAgainstSource(
                OptionalCallShapeBinder.declaredParameterShapes(declaration.parameters()),
                call.arguments(), selection.plan());
        var preview = OptionalCallAbiPreview.prepareStagedCallExact(
                call, declaration.parameters(), assertedSourceTypes);
        return new BoundInvocation(Kind.MODULE_FUNCTION, call, module, declaration,
                ordinals.get(selection.signatureIndex()), preview, assertedSourceTypes);
    }

    /** Direct, non-inherited class member. Receiver type/effects are not proved. */
    public static BoundInvocation bindClassMethod(
            Ast.ClassDecl owner, Ast.StagedCallExpr call,
            List<Ast.TypeRef> assertedSourceTypes) {
        requireSimpleClass(owner);
        Objects.requireNonNull(call, "call");
        if (!(call.callee() instanceof Ast.MemberExpr member)
                || member.receiver() == null) {
            throw new IllegalArgumentException(
                    "method call requires a non-null member receiver expression");
        }
        // AST producers may construct a class with a field and method of
        // identical spelling, bypassing parser/TypeChecker member resolution.
        // Do not guess that box.member selects the method rather than field.
        for (Ast.FieldDecl field : owner.fields()) {
            if (field.name().equals(member.member())) {
                throw new IllegalArgumentException(
                        "method name collides with unproved class field resolution");
            }
        }
        List<Ast.MethodDecl> candidates = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        List<List<Ast.Param>> signatures = new ArrayList<>();
        for (int i = 0; i < owner.methods().size(); i++) {
            Ast.MethodDecl method = owner.methods().get(i);
            if (method.name().equals(member.member())) {
                if (!method.genericParameters().isEmpty()
                        || method.isStatic() || method.isAbstract()
                        || method.async() || method.suspends() || method.structural()
                        || method.explicitReceiverType() != null
                        || !method.annotations().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic, static, abstract, effectful, receiver-specialized or annotated method requires separate proof");
                }
                candidates.add(method);
                ordinals.add(i);
                signatures.add(method.parameters());
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("method not declared by selected class");
        }
        var selection = OptionalCallShapeBinder.selectParsed(signatures, call.arguments());
        Ast.MethodDecl selected = candidates.get(selection.signatureIndex());
        OptionalCallShapeBinder.verifyPlanAgainstSource(
                OptionalCallShapeBinder.declaredParameterShapes(selected.parameters()),
                call.arguments(), selection.plan());
        var preview = OptionalCallAbiPreview.prepareStagedCallExact(
                call, selected.parameters(), assertedSourceTypes);
        return new BoundInvocation(Kind.CLASS_METHOD, call, owner, selected,
                ordinals.get(selection.signatureIndex()), preview, assertedSourceTypes);
    }

    /** Explicit class constructor only; actor spawning and synthesized ABI gated. */
    public static BoundInvocation bindClassConstructor(
            Ast.ClassDecl owner, Ast.StagedNewExpr call,
            List<Ast.TypeRef> assertedSourceTypes) {
        requireSimpleClass(owner);
        Objects.requireNonNull(call, "call");
        if (call.actorSpawn() || !call.type().name().equals(owner.name())
                || call.type().inferArguments() || !call.type().arguments().isEmpty()) {
            throw new IllegalArgumentException("unproved actor, generic or foreign constructor");
        }
        Ast.ConstructorDecl constructor = owner.constructor();
        if (constructor == null) {
            throw new IllegalArgumentException("synthetic constructor requires separate proof");
        }
        if (!constructor.annotations().isEmpty()) {
            throw new IllegalArgumentException(
                    "annotated constructor requires independent authorization proof");
        }
        // An optional leading constructor self is a receiver binder, never a
        // guest value argument. Until the constructor parser and checker issue
        // a verified receiver-erasure proof, do not manufacture an ABI slot
        // for self (including malformed nonleading synthetic self binders).
        if (constructor.parameters().stream().anyMatch(p -> "self".equals(p.name()))) {
            throw new IllegalArgumentException(
                    "constructor self receiver requires checked receiver erasure");
        }
        var preview = OptionalCallAbiPreview.prepareStagedConstructionExact(
                call, constructor.parameters(), assertedSourceTypes);
        return new BoundInvocation(Kind.CLASS_CONSTRUCTOR, call, owner, constructor, 0,
                preview, assertedSourceTypes);
    }

    private static void requireSimpleClass(Ast.ClassDecl owner) {
        Objects.requireNonNull(owner, "class owner");
        if (owner.isAbstract() || !owner.genericParameters().isEmpty()
                || !owner.parents().isEmpty() || !owner.interfaces().isEmpty()
                || owner.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "abstract, generic, inherited, interface or actor class has no binding proof");
        }
    }
}
