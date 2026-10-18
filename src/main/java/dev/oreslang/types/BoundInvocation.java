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
        this.assertedSourceTypes = List.copyOf(assertedSourceTypes);
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
    }

    public Ast.Expr source() { return source; }
    public Object owner() { return owner; }
    public Object selectedDeclaration() { return selected; }
    public Kind kind() { return kind; }
    public int declarationOrdinal() { return declarationOrdinal; }
    public OptionalCallAbiPreview.InvocationPreview preview() { return preview; }
    public List<Ast.TypeRef> assertedSourceTypes() { return assertedSourceTypes; }
    public Readiness readiness() { return Readiness.SHAPE_ONLY; }

    public void requireExecutable() {
        throw new IllegalStateException(
                "trusted type/ownership/effect/Option ABI lowering is not implemented");
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
        List<Ast.FunctionDecl> candidates = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        List<List<Ast.Param>> signatures = new ArrayList<>();
        for (int i = 0; i < module.declarations().size(); i++) {
            if (module.declarations().get(i) instanceof Ast.FunctionDecl f
                    && f.name().equals(name)) {
                if (!f.genericParameters().isEmpty() || !f.genericSpecs().isEmpty()
                        || f.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("generic or actor target not proved");
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
        if (!(call.callee() instanceof Ast.MemberExpr member)) {
            throw new IllegalArgumentException("method call must have a member callee");
        }
        List<Ast.MethodDecl> candidates = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        List<List<Ast.Param>> signatures = new ArrayList<>();
        for (int i = 0; i < owner.methods().size(); i++) {
            Ast.MethodDecl method = owner.methods().get(i);
            if (method.name().equals(member.member())) {
                if (!method.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException("generic method requires verified substitution");
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
